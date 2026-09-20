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

# 3. 重建索引，并检查响应里的 warnings 是否为空
curl -X POST http://localhost:8080/api/ingest
```

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
