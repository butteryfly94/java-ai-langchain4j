# 小智聊天助手：LangChain4j 医疗知识库助手

一个前后端分离的 AI 医疗助手示例项目。系统基于 LangChain4j 构建对话与 RAG 能力，支持知识库检索、会话记忆、预约挂号工具调用、引用来源返回，以及按问题复杂度自动选择普通 RAG 或 Agentic RAG。

## 项目结构

```text
.
├── java-ai-langchain4j/   # Spring Boot 后端与知识库处理管线
├── xiaozhi-ui/            # Vue 3 + Vite 前端
├── docker-compose.yml     # Elasticsearch 开发环境
└── README.md
```

## 主要能力

- 普通 RAG：查询扩展、向量检索与 BM25 检索并行召回、RRF 融合、重排序。
- Agentic RAG：模型可自主调用知识库检索、预约挂号及 MCP 文件系统工具。
- 对话记忆：使用 MongoDB 持久化会话历史。
- 知识库摄入：支持 Markdown、文本及 MinerU 生成的 `*_content_list.json`；支持增量重建与 Elasticsearch 别名原子切换。
- 引用溯源：对话流结束时追加命中的来源信息。
- 前端聊天界面：Vue 3、Element Plus 和流式响应展示。

## 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 18+ 与 npm（前端；启用 Agentic RAG 的 MCP 时也需要）
- Docker Desktop（用于 Elasticsearch）
- MongoDB 及 MySQL 8
- 阿里云百炼 API Key

## 快速启动

### 1. 启动 Elasticsearch

在仓库根目录执行：

```powershell
docker compose up -d
```

Elasticsearch 默认暴露在 `http://localhost:9200`。

### 2. 准备数据库

后端默认连接以下本地服务，按实际环境修改 `java-ai-langchain4j/src/main/resources/application.properties`：

| 服务 | 默认连接 |
| --- | --- |
| MongoDB | `mongodb://localhost:27017/chat_memory_db` |
| MySQL | `jdbc:mysql://localhost:3306/guiguxiaozhi` |
| Elasticsearch | `localhost:9200` |

MySQL 用于预约挂号相关工具；MongoDB 用于聊天记忆。若只做基础验证，仍建议先保证三项服务均可连接。

### 3. 配置模型密钥

PowerShell 当前会话中设置百炼 API Key：

```powershell
$env:DASH_SCOPE_API_KEY = "你的百炼 API Key"
```

不要把密钥写入 `application.properties` 或提交到仓库。

### 4. 启动后端

```powershell
cd java-ai-langchain4j
mvn spring-boot:run
```

后端默认监听 `http://localhost:8080`。

### 5. 摄入知识库

将知识文档放入 `java-ai-langchain4j/knowledge/`，再调用摄入接口：

```powershell
Invoke-RestMethod -Method Post http://localhost:8080/api/ingest
```

默认使用增量摄入。若需要忽略指纹账本并强制全量重建：

```powershell
Invoke-RestMethod -Method Post "http://localhost:8080/api/ingest?force=true"
```

响应中的 `warnings` 不应被忽略：非空通常意味着某份文档可能没有被正确解析。

### 6. 启动前端

新开一个终端：

```powershell
cd xiaozhi-ui
npm install
npm run dev
```

访问 `http://localhost:5173`。开发服务器已将 `/api` 请求代理到后端 `http://localhost:8080`。

## 接口说明

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/xiaozhi/chat` | 对话入口；根据问题复杂度自动选择普通 RAG 或 Agentic RAG，流式返回文本。 |
| `POST` | `/api/xiaozhi/agentic-chat` | 强制使用 Agentic RAG；MCP 关闭时返回提示。 |
| `POST` | `/api/ingest` | 增量摄入知识库。可使用 `force=true` 强制全量重建。 |

对话请求示例：

```powershell
$body = @{ memoryId = 1; message = "神经内科的门诊时间是什么？" } | ConvertTo-Json
Invoke-WebRequest `
  -Method Post `
  -Uri http://localhost:8080/api/xiaozhi/chat `
  -ContentType "application/json" `
  -Body $body
```

`memoryId` 用于关联同一个用户的会话记忆。流式正文结束后，服务可能追加 `---SOURCES---` 标记及 JSON 格式的引用来源。

## 知识库文档与 PDF

- 可直接入库：`.md`、`.txt`、MinerU 输出的 `*_content_list.json`。
- PDF 原件放在 `java-ai-langchain4j/pdf-source/`，不要直接放入 `knowledge/`。
- 使用 `java-ai-langchain4j/tools/pdf2md.py` 将 PDF 转为 MinerU 结构化结果，再摄入生成的内容。

关于 PDF 转换、表格解析和索引回滚，参见 [pdf-source/README.md](java-ai-langchain4j/pdf-source/README.md)。

## Agentic RAG 与 MCP

`rag.mcp.enabled=true` 时，后端会经由 `npx` 启动 `@modelcontextprotocol/server-filesystem`，向模型提供 `knowledge/` 目录的文件读取能力。因此启用该功能前需安装 Node.js，并确保网络或本地 npm 缓存可取得该包。

若不需要 MCP，可在 `application.properties` 设置：

```properties
rag.mcp.enabled=false
```

此时自动路由会降级为普通 RAG，`/api/xiaozhi/agentic-chat` 不可用。

## 常用配置

配置文件位于 `java-ai-langchain4j/src/main/resources/application.properties`：

- `langchain4j.*`：模型、流式模型与向量模型。
- `rag.knowledge-dir`：知识库目录。
- `rag.ingestion.incremental`：是否启用增量摄入。
- `rag.retrieval.*`：混合检索、RRF 与重排序参数。
- `rag.router.*`：普通 RAG / Agentic RAG 自动路由阈值。
- `rag.tool.*`：工具调用轮次与重复调用保护。

## 验证与测试

后端测试：

```powershell
cd java-ai-langchain4j
mvn test
```

前端生产构建：

```powershell
cd xiaozhi-ui
npm run build
```

部分集成测试和实际对话依赖本地数据库、Elasticsearch 与模型密钥；请在环境已准备好时执行。
