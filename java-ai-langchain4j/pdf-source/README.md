# pdf-source/

存放待转换的 PDF 原件。**这个目录不参与索引**——里面的 PDF 不会被摄入管线读取。

## 为什么要单独放一个目录

摄入管线刻意不接收 `.pdf`（见 `KnowledgeIngestionService.loadTextDocuments`）。
如果 PDF 直接放在 `knowledge/` 里，会被 Apache PDFBox 抽取入库，而 PDFBox 对表格
无能为力：它按字形坐标拼文本，多列表格会按列交错成
`科室 心内科 神经内科 门诊时间 周一至周五 ...` 这种无法阅读的内容，**且不报错**。

更糟的是，如果你同时用 MinerU 转出了 `.md`，同一份文档就会以两种形态进库
（MinerU 的好版本 + PDFBox 的垃圾版本），检索表现为"同一份文档时对时错"，
极难排查。

所以流程是单向的：**PDF 进 `pdf-source/`，转换产物进 `knowledge/`**。

## 使用

```bash
# 1. 把 PDF 放进来
cp /path/to/就诊须知.pdf pdf-source/

# 2. 转换（需要先 pip install "mineru[core]"）
python tools/pdf2md.py

# 3. 摄入索引，并检查响应里的 warnings 是否为空
#    默认增量：只有新增/修改过的文档会重新切分与向量化，未变文档的向量从上一次
#    索引直接复用（不产生 embedding 调用）。转换出新文件时用这个即可。
curl -X POST http://localhost:8080/api/ingest
```

如果改动的是**切分逻辑或其参数**（而非文档内容），增量会自动识别出来并全量重灌
（靠配置指纹，见 `DocumentFingerprintStore`），不需要手工传 `force`。
只有当你想强制忽略账本、把所有文档重新灌一遍时才用：

```bash
curl -X POST "http://localhost:8080/api/ingest?force=true"
```

响应里几个容易看错的字段：

| 字段 | 含义 |
|---|---|
| `documentsChanged` / `documentsSkipped` | 本次重灌了几份 / 跳过了几份 |
| `children` | 本次**真正重新向量化**的子块数（决定 embedding 花了多少钱） |
| `childrenCarriedOver` | 从上一版索引搬运、**未产生 embedding 调用**的子块数 |
| `warnings` | 非空说明有文档可能没被正确解析，需人工核对 |

索引总量应等于 `children + childrenCarriedOver`。若两者都比预期小，
说明有文档被判定为"未变化"但实际应该更新——这时用 `force=true` 兜底。

转换后 `knowledge/` 下会出现两个文件：

| 文件 | 用途 |
|---|---|
| `就诊须知.md` | **给人核对**解析质量，不进管线 |
| `就诊须知_content_list.json` | **给切分器用**，带块类型才能正确处理表格 |

## 注意事项

**不要用 `-m txt`**。该模式快，但明确不处理图片和表格，而且**不报错**——
表格会静默消失，比 PDFBox 抽出乱码更难发现。默认的 `auto` 会逐页判断，
混合语料就用它。

**扫描件先预处理**。300 DPI + 纠偏的投入产出比远高于换模型。扫描件的表格走的是
`版面检测 → 表格分类 → 表格OCR → 结构识别` 四步，OCR 错字会同时削弱向量检索和
BM25 精确匹配（药品名错一个字就匹配不上）。

**转换是幂等的**。脚本会跳过已有 `_content_list.json` 的文件；要强制重转加 `--force`。

**转换失败会显式报错并返回非零退出码**，不会产出一份残缺文件蒙混过关。

## 索引运维（别名与回滚）

检索侧读的是**别名**（`knowledge-children` / `knowledge-parents`），
每次摄入会新建一对物理索引（如 `knowledge-children-20260920193000-1`），
写完才把别名原子切过去。这意味着：

- **摄入期间检索始终可用**。旧索引在切换前一直在服务，不存在"重建时知识库空了"的窗口。
- **写入失败不影响线上**。半成品索引会被清理，别名仍指向旧索引。
- **可以手工回滚**：把别名切回上一个物理索引即可。

```bash
# 看当前别名指向哪个物理索引
curl -s "localhost:9200/_cat/aliases/knowledge-children?h=alias,index"

# 看有哪些历史物理索引（按时间排序）
curl -s "localhost:9200/_cat/indices/knowledge-children-*?h=index&s=index"

# 回滚到上一个（把 <旧索引名> 换成实际名字，两个别名都要切）
curl -X POST localhost:9200/_aliases -H 'Content-Type: application/json' -d '{
  "actions": [
    {"remove": {"index": "knowledge-children-20260920193000-1", "alias": "knowledge-children"}},
    {"add":    {"index": "<上一个索引名>",                     "alias": "knowledge-children"}}
  ]
}'

# 确认新索引没问题后，删除旧索引释放磁盘
curl -X DELETE localhost:9200/knowledge-children-20260920193000-1
```

`rag.ingestion.keep-old-indices=true`（默认）会保留旧索引以便回滚；
若磁盘紧张可设为 `false` 让每次摄入后自动删除上一版。
**注意：自动删除后就没有回滚点了**，生产环境建议保留。

指纹账本存在 `knowledge-documents` 索引里，记录每份文档的内容hash与切分配置指纹。
如果怀疑增量判定出错（比如改了文档但没被识别），删掉账本索引
（`curl -X DELETE localhost:9200/knowledge-documents`）后跑一次摄入，
就会退化为全量重灌。
