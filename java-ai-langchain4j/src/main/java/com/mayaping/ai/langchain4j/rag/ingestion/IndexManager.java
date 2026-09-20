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
 *      首次运行会在"创建别名"这一步直接失败（invalid_alias_name_exception）。
 *      注意这里的 remove 动作救不了：冲突的是**索引名本身**，不是别名归属，
 *      同一请求里先 remove 再 add 依然报错。唯一可行的做法是先把旧索引**改名让位**
 *      （_reindex 到 &lt;alias&gt;-legacy-&lt;时间戳&gt; 再删原索引），数据留在 legacy 索引里不丢。
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
        // 遗留的同名普通索引必须先让出名字，否则下面的 add 会被 ES 拒绝
        if (isPlainIndexNamed(alias)) {
            migrateLegacyPlainIndex(alias);
        }

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
     * 把改造前遗留的同名普通索引改名让位。
     *
     * 为什么是改名而不是删除：这个索引里装的是**当前唯一一份**知识库向量，
     * 而调用方此刻已经完成了搬运/重灌（publish 里先 carryOver 再切别名），
     * 但"搬运成功"这件事在跨版本、跨 mapping 的场景下并不绝对可靠。
     * 直接删掉换来的只是省一点磁盘，代价是唯一副本消失且不可回滚。
     * 数据安全问题不该由"省磁盘"来定价，所以默认保留，由人确认后再删除。
     */
    private void migrateLegacyPlainIndex(String alias) throws IOException {
        String legacyName = alias + "-legacy-" + LocalDateTime.now().format(STAMP) + "-" + SEQ.incrementAndGet();

        log.warn("检测到 {} 是改造前遗留的普通索引（与别名同名，ES 不允许二者共存）。"
                        + "本次发布先把它改名为 {} 让出名字，原数据保留不删除；"
                        + "确认新索引可检索后可手工 DELETE {}",
                alias, legacyName, legacyName);

        var response = client.reindex(r -> r
                .source(s -> s.index(alias))
                .dest(d -> d.index(legacyName))
                .waitForCompletion(true));

        if (!response.failures().isEmpty()) {
            // 改名失败就中止发布：此时旧索引仍在原位、数据完好，别名尚未切换，
            // 线上检索不受影响。继续走下去会删掉唯一副本，这个代价不可接受
            throw new IOException(String.format(
                    "遗留索引 %s 改名（reindex 到 %s）有 %d 条失败，已中止发布以避免删除唯一数据副本",
                    alias, legacyName, response.failures().size()));
        }

        client.indices().delete(d -> d.index(alias));
        log.info("遗留索引改名完成：{}（{} 条文档）→ {}", alias, response.total(), legacyName);
    }

    /**
     * 查询别名当前指向的物理索引。
     *
     * @return 当前指向该别名的索引名列表；别名不存在时返回空列表
     */
    private List<String> aliasHolders(String alias) throws IOException {
        try {
            var response = client.indices().getAlias(g -> g.name(alias));
            return List.copyOf(response.result().keySet());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 别名不存在。同名普通索引存在时这里也返回空——它不会被 updateAliases
                // 的 remove 摘掉（它不是别名），而是在 applyAliasSwap 里先被改名让位
                return List.of();
            }
            throw e;
        }
    }

    /** 该名字是否为别名（区别于同名普通索引） */
    private boolean isAliasNamed(String name) throws IOException {
        try {
            client.indices().getAlias(g -> g.name(name));
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** 该名字是否为普通索引（同名别名优先判定为别名，不会两者同时成立） */
    private boolean isPlainIndexNamed(String name) throws IOException {
        if (isAliasNamed(name)) {
            return false;
        }
        return client.indices().exists(e -> e.index(name)).value();
    }

    /**
     * 删除旧物理索引。别名指向的索引不会被删（调用方需自行过滤），
     * 这里只负责删除，不负责判断该不该删。
     */
    public void deleteIndex(String indexName) throws IOException {
        // 别名不能删：ES 的 DELETE <别名> 会把别名指向的物理索引一并删掉。
        // 这个分支真实可达——改造前的环境里 previousChildren 就是别名同名的普通索引，
        // 它被改名让位后，同一个名字变成了指向**新索引**的别名，
        // 此时若按名字删就会把刚发布的新索引删掉（数据丢失且查询立刻变空）
        if (isAliasNamed(indexName)) {
            log.warn("{} 当前是别名（指向 {}），拒绝删除以免连带删除其指向的物理索引",
                    indexName, aliasHolders(indexName));
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

    /**
     * 探测某个字段在索引里的实际路径，用于拼出可在查询中使用的精确字段名。
     *
     * ## 为什么需要"探测"而不是写死字段名
     *
     * children 索引的 mapping **不是我们定义的**——它由 ES 在 langchain4j 首次写入时
     * 动态映射（dynamic mapping）自动创建。因此字段落在哪一层、被推断成什么类型，
     * 取决于 langchain4j 写入时的JSON结构，我们只能推断而无法保证：
     *
     *   - langchain4j 把元数据整体塞进一个 metadata 对象里 → 路径是 metadata.docId
     *     若是扁平写入，路径则是 docId
     *   - 字符串会被动态映射成 text（分词）。terms 查询不做分词，
     *     直接查 text 字段匹配中文文件名会落空 → 必须用 .keyword 子字段
     *
     * 这两种情况一旦猜错**不会报错**，只会让 terms 条件匹配不到任何文档，
     * 排除逻辑形同虚设，旧数据被整份搬运并与新数据叠加成重复子块。
     * 所以这里不猜——去 mapping 里实际看一眼，按真实存在的路径返回。
     *
     * @param indexName 要探测的索引
     * @param bareField 不带路径的字段名（如 docId）
     * @return 可直接用于查询的字段名；两种路径都不存在时返回 null（调用方需降级）
     */
    public String resolveKeywordField(String indexName, String bareField) throws IOException {
        var mapping = client.indices().getMapping(g -> g.index(indexName));

        for (var entry : mapping.result().entrySet()) {
            var properties = entry.getValue().mappings().properties();

            // 情况一：嵌套在 metadata 对象里（langchain4j 的写入形态）
            Property metadataWrapper = properties.get("metadata");
            if (metadataWrapper != null && metadataWrapper.isObject()) {
                Property nested = metadataWrapper.object().properties().get(bareField);
                if (nested != null) {
                    String resolved = "metadata." + bareField + (isTextLike(nested) ? ".keyword" : "");
                    log.info("字段探测：{} 实际路径为 {}（索引 {}）", bareField, resolved, indexName);
                    return resolved;
                }
            }

            // 情况二：顶层字段（我们自己的 parents 索引就是这种）
            Property direct = properties.get(bareField);
            if (direct != null) {
                String resolved = isTextLike(direct) ? bareField + ".keyword" : bareField;
                log.info("字段探测：{} 实际路径为 {}（索引 {}）", bareField, resolved, indexName);
                return resolved;
            }
        }

        log.warn("字段探测：索引 {} 中找不到 {}，既不在 metadata 下也不在顶层。"
                + "搬运将退化为不排除任何文档（可能产生重复子块）。"
                + "若该索引尚未写入任何数据，属正常情况", indexName, bareField);
        return null;
    }

    /**
     * 判断字段是否被动态映射成 text 或 wildcard（需要用 .keyword 子字段做精确匹配）。
     *
     * keyword 字段可直接用于 terms；text 字段被分词后不能，必须走它的 keyword 子字段。
     */
    private static boolean isTextLike(Property property) {
        return property.isText() || property.isWildcard();
    }

    /**
     * 当前可作搬运来源的索引名；没有可搬运来源时返回 null。
     *
     * 两种情况：
     *   - 正常环境：别名指向的物理索引
     *   - 改造前遗留环境：别名还不存在，向量在**同名普通索引**里。
     *     这里必须把它认出来（返回索引名本身），否则首次发布时搬运步骤会认为
     *     "没有上一版索引"而从零重灌——虽然结果正确，但会白白重付一遍全部 embedding。
     *     注意此时**不能**走别名切换路径，它会在 applyAliasSwap 里被改名让位。
     */
    public String currentPhysicalIndex(String alias) throws IOException {
        List<String> holders = aliasHolders(alias);
        if (!holders.isEmpty()) {
            return holders.get(0);
        }
        return isPlainIndexNamed(alias) ? alias : null;
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
