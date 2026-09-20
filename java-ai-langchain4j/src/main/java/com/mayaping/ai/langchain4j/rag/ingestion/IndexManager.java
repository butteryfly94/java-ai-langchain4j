package com.mayaping.ai.langchain4j.rag.ingestion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 索引与别名的生命周期管理：让"重建索引"不再意味着"检索不可用"。
 *
 * ## 为什么需要这个类
 *
 * 改造前的摄入是「先删旧索引 → 再重建」，注释里也如实写着「全量重建（幂等：先删旧索引再重建）」。
 * 这在 4 份文档时看不出问题，但它有一个随规模线性恶化的缺陷：
 *
 *   deleteIndex(children)     ← 从这一刻起，所有 /chat 检索都命中空索引
 *   ...重建：逐份文档切分 + 按10条一批串行调 embedding...
 *   createIndex / 写入完成     ← 到这里才恢复
 *
 * 中间那段窗口不是"降级"，是**知识库整体消失**。用户问什么都会得到
 * "知识库中未检索到相关信息"，而系统日志一切正常。窗口长度正比于文档数
 * （embedding 是 10 条一批的串行调用），千份文档量级下就是分钟到小时级。
 *
 * ## 解法：写新索引 + 原子切换别名
 *
 * ES 的别名（alias）可以一次请求内完成「把别名从旧索引摘除、挂到新索引」，
 * 这是一个**原子操作**，没有中间态：
 *
 *   knowledge-children  ──(写入期间仍指向 v1，检索照常)──▶ knowledge-children-20260920...
 *                                  ↓ 写完
 *   _aliases: remove v1 + add v2    ← 原子，读请求要么看到全 v1、要么看到全 v2
 *
 * 于是重建的可用性问题从"架构问题"降级为"运维问题"——旧索引要不要删是你的选择，
 * 删错了也只是浪费磁盘，不会让线上检索中断。
 *
 * ## 别名的自动接管
 *
 * 别名可能处于三种状态，本类都要能处理（尤其是第三种，它是最容易漏的）：
 *   1. 别名不存在，也没有同名物理索引 → 全新环境，创建物理索引并挂别名
 *   2. 别名存在，指向某个物理索引   → 正常切换
 *   3. **同名物理索引存在，但只是普通索引、不是别名** → 改造前遗留的索引。
 *      这是最坑的一种：ES 里索引名与别名**不能同名**，若不做迁移，
 *      首次运行会在"创建别名"这一步直接失败。
 *      迁移策略是把旧索引原地转成别名的一个指向（_aliases 操作对普通索引同样有效）。
 */
@Component
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    /**
     * 物理索引名后缀。用时间戳而非自增序号，是为了让"哪次重建"在 ES 索引列表里一眼可辨，
     * 排查问题时不必再去翻日志对时间。
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 同一秒内多次创建时避免重名（实践中不会发生，但重名会导致 ES 直接报错，代价不对称） */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final ElasticsearchClient client;

    public IndexManager(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 一次索引发布：新建物理索引 → 重灌 → 原子切别名。
     *
     * 调用方必须用 try-with-resources 包住，保证失败时新索引被清理，
     * 否则半成品索引会留在 ES 里，下次重建又看不到它、又占着磁盘。
     */
    public IndexPublication beginPublication(String aliasName, Map<String, Property> mappings) throws IOException {
        String physicalName = physicalNameFor(aliasName);

        // children 索引的 kNN 向量 mapping 由 langchain4j 在首次写入时自动创建，
        // 这里传 null 表示"交给框架建"，只有 parents/documents 需要显式 mapping
        if (mappings != null) {
            client.indices().create(c -> c.index(physicalName).mappings(m -> m.properties(mappings)));
        } else {
            client.indices().create(c -> c.index(physicalName));
        }
        log.info("已创建物理索引 {}（别名 {} 将在写完并刷新后接管）", physicalName, aliasName);

        return new IndexPublication(aliasName, physicalName);
    }

    /**
     * 把别名原子地切到新索引。返回前会先 refresh，保证切过去立刻可检索。
     */
    public void publish(IndexPublication publication) throws IOException {
        String alias = publication.aliasName();
        String physical = publication.physicalName();

        // 切换前先刷新：否则别名已经指过去了，但最后一批文档还在 ES 的内存缓冲里，
        // 表现为"重建完成但刚更新的内容查不到"，且过几秒又自己好了——这类偶发最难排查
        refresh(physical);

        applyAliasSwap(alias, physical);

        publication.markPublished();
        log.info("别名切换完成：{} → {}", alias, physical);
    }

    /**
     * 别名切换的原子操作。
     *
     * 关键点：remove 与 add 必须在**同一个 _aliases 请求**里，
     * 分两次调用会出现"别名短暂不存在"的子状态，那时的检索直接报 index_not_found。
     */
    private void applyAliasSwap(String alias, String newPhysical) throws IOException {
        List<String> currentHolders = aliasHolders(alias);

        client.indices().updateAliases(u -> {
            // 先摘掉当前所有指向该别名的索引（正常情况下只有一个）
            for (String holder : currentHolders) {
                u.actions(a -> a.remove(r -> r.index(holder).alias(alias)));
            }
            // 再挂到新索引
            u.actions(a -> a.add(add -> add.index(newPhysical).alias(alias)));
            return u;
        });
    }

    /**
     * 查询别名当前指向的物理索引。
     *
     * 注意这里要处理"同名普通索引"这个遗留状态：如果 knowledge-children 是个
     * 普通索引而不是别名，getAlias 会返回 404。此时把索引名本身当作持有者返回，
     * 使 remove 动作能把它摘掉——ES 允许对普通索引执行 remove alias 之外的
     * alias 操作，这一步等价于把旧索引原地"改造成"别名的一个历史指向。
     *
     * @return 当前指向该别名的索引名列表；别名不存在（或指向普通索引）时返回空列表
     */
    private List<String> aliasHolders(String alias) throws IOException {
        try {
            var response = client.indices().getAlias(g -> g.name(alias));
            return List.copyOf(response.result().keySet());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 别名不存在。但同名普通索引可能存在——它是 ES 里"索引与别名不能同名"的
                // 冲突来源，必须在这里被识别出来，否则后续 add alias 会直接失败
                boolean plainIndexExists = client.indices().exists(x -> x.index(alias)).value();
                if (plainIndexExists) {
                    log.warn("检测到 {} 是改造前遗留的普通索引（非别名），"
                            + "本次发布将把它转为别名并指向新索引。旧索引不会被删除，"
                            + "确认新索引可检索后可手工 DELETE {}", alias, alias);
                    return List.of(alias);
                }
                return List.of();
            }
            throw e;
        }
    }

    /**
     * 删除旧物理索引。别名指向的索引不会被删（调用方需自行过滤），
     * 这里只负责删除，不负责判断该不该删。
     */
    public void deleteIndex(String indexName) throws IOException {
        if (aliasHolders(indexName).contains(indexName)) {
            log.warn("{} 当前正被同名别名指向，拒绝删除以免影响线上检索", indexName);
            return;
        }
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            client.indices().delete(d -> d.index(indexName));
            log.info("已删除旧物理索引 {}", indexName);
        }
    }

    /**
     * 列出某别名的历史物理索引（按名排序，时间戳后缀使其天然有序）。
     * 供运维清理旧索引使用——不提供自动清理，因为"保留几个版本"是个策略问题，
     * 不该由工具替你做决定。
     */
    public List<String> physicalIndicesOf(String aliasPrefix) throws IOException {
        var response = client.indices().get(g -> g.index(aliasPrefix + "*"));
        // aliasHolders 会抛 IOException，不能在 stream 的 lambda 里调用（lambda 不允许
        // 抛受检异常），所以先取出来再用集合判断
        Set<String> activeIndices = Set.copyOf(aliasHolders(aliasPrefix));
        return response.result().keySet().stream()
                .filter(name -> !name.equals(aliasPrefix))
                .filter(name -> !activeIndices.contains(name))
                .sorted()
                .toList();
    }

    public void refresh(String indexName) throws IOException {
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            client.indices().refresh(r -> r.index(indexName));
        }
    }

    /** 该别名当前指向的物理索引名；未初始化时返回 null */
    public String currentPhysicalIndex(String alias) throws IOException {
        List<String> holders = aliasHolders(alias);
        return holders.isEmpty() ? null : holders.get(0);
    }

    private String physicalNameFor(String alias) {
        return alias + "-" + LocalDateTime.now().format(STAMP) + "-" + SEQ.incrementAndGet();
    }

    /**
     * 一次进行中的索引发布。
     *
     * 用 try-with-resources 语义保证：如果发布过程中任何一步失败（切分异常、
     * embedding 超限、网络抖动），半成品索引会被删除，而别名始终指向**旧索引**——
     * 也就是说失败的发布对线上是无感的，这正是我们要的语义。
     */
    public final class IndexPublication implements AutoCloseable {

        private final String aliasName;
        private final String physicalName;
        private boolean published;

        private IndexPublication(String aliasName, String physicalName) {
            this.aliasName = aliasName;
            this.physicalName = physicalName;
        }

        public String aliasName() {
            return aliasName;
        }

        public String physicalName() {
            return physicalName;
        }

        private void markPublished() {
            this.published = true;
        }

        @Override
        public void close() throws IOException {
            if (published) {
                return;
            }
            // 未成功发布就退出：清理半成品，别名仍指向旧索引，线上不受影响
            log.warn("索引发布未完成，清理半成品 {}（别名 {} 保持指向旧索引，线上检索未受影响）",
                    physicalName, aliasName);
            try {
                if (client.indices().exists(e -> e.index(physicalName)).value()) {
                    client.indices().delete(d -> d.index(physicalName));
                }
            } catch (Exception e) {
                log.warn("清理半成品索引 {} 失败，请手工删除：{}", physicalName, e.getMessage());
            }
        }
    }
}
