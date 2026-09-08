# third-brain-think — 生产级 RAG + Agent 学习参考实现

> 基于 LangChain4j + Spring AI 的多模块 RAG 工程（前身 `rag-demo`），将文档处理、混合检索、Agent 编排与答案验证的完整能力汇聚到一个可运行、可学习的参考实现中。适合想系统学习 RAG 与 Agent 工程化落地的 Java 开发者。
>
> 最短上手路径：备齐 MySQL + Redis + Milvus + DashScope API Key，`mvn clean package -DskipTests` 后运行 `third-brain-think-starter` 即可，详见[快速开始](#快速开始)。

**文档导航**：[项目结构](./docs/project-map.md) · [架构说明](./docs/architecture.md) · [变更历史](./CHANGELOG.md) · [Agent 工作指南](./AGENTS.md)

---

## 目录

- [项目定位](#项目定位)
- [技术栈](#技术栈)
- [能力全景](#能力全景)
- [架构设计](#架构设计)
- [工程结构](#工程结构)
- [快速开始](#快速开始)
- [API 参考](#api-参考)
- [核心组件详解](#核心组件详解)
- [Agent 模块](#agent-模块)
- [RAG 管道](#rag-管道)
- [文档处理管道](#文档处理管道)
- [Chat Memory](#chat-memory)
- [扩展开发指南](#扩展开发指南)
- [Roadmap](#roadmap)

---

## 项目定位

```
third-brain-think 不是生产服务，而是一个"精华提炼"的学习工程：
  know-engine  ──── 持久化 Memory / 版本管理 / 进度推送（独立模块，独立运行）
  rag          ──── 混合检索 / RRF / 重排 / 多格式加载（主工程）
  ragcore      ──── RAG 组件教学演示（reader/splitter/es/neo4j/minio/router）
  agentx       ──── BIRD Text-to-SQL 评测 / Judge / Diagnose（+ agentx-core 框架库）
  general-agent ─── ReAct Agent / Spring AI 工具调用
        ↓
  third-brain-think ── 统一在一个工程里，可运行、可调试、可学习
```

---

## 技术栈

| 层次 | 技术 | 版本 |
|---|---|---|
| LLM 框架 | LangChain4j | 1.13.0（know-engine 隔离使用 1.11.0） |
| LLM 框架 | Spring AI | 1.1.4 |
| LLM 框架 | Spring AI Alibaba（DashScope） | 1.1.2.2（ragcore 教学端点） |
| Web 框架 | Spring Boot | 3.5.6 |
| ORM | MyBatis-Plus | 3.5.9 |
| 向量存储 | Milvus（必须） | 2.x |
| 全文检索 / 向量存储（可选） | Elasticsearch 8.x | BM25 关键词检索 + embedding store；`elasticsearch.enable=false` 时不启动 |
| 图数据库（可选） | Neo4j | ragcore 图谱教学演示，驱动懒连接 |
| 对象存储（可选） | MinIO | 8.5.1，ragcore 文件教学演示 |
| 评测模型（可选） | DeepSeek | agentx BIRD/Judge/Diagnose 评测 |
| 评测数据库（内嵌） | SQLite（sqlite-jdbc） | 3.46.1.3，BIRD 评测数据 |
| 关系数据库 | MySQL 8 | — |
| 缓存 | Redis | — |
| Excel 解析 | EasyExcel | 3.3.4 |
| PDF 解析 | Apache PDFBox | 3.0.1 |
| 文档解析 | Apache Tika | — |

---

## 能力全景

### 文档处理
| 能力 | 状态 | 说明 |
|---|---|---|
| PDF 加载（文字+图片） | ✅ | `PdfMultimodalProcessor` — qwen-vl 多模态描述图片 |
| Word/DOCX 加载 | ✅ | `DocxImageExtractor` — 提取内嵌图片 |
| Markdown 加载 | ✅ | — |
| TXT 加载 | ✅ | — |
| Excel/CSV 加载 | ✅ | `ExcelSplitter` — KEY_VALUE / HTML_TABLE 两种输出模式 |
| 标题层级分割 | ✅ | `MarkdownHeaderSplitter` |
| Brother/Parent Chunk 分割 | ✅ | `MarkdownHeaderBrotherTextSplitter` |
| 重叠段落分割 | ✅ | `OverlapParagraphSplitter` |
| 图像感知分割 | ✅ | `ImageAwareSplitter` |
| 文档版本管理 | ✅ | SHA-256 去重 + activate/deactivate |

### RAG 检索管道
| 能力 | 状态 | 说明 |
|---|---|---|
| 向量检索 | ✅ | `VectorContentRetriever` |
| BM25 关键词检索 | ✅ | ES 启用时：`KeywordContentRetriever`（BM25）；ES 关闭时：`MysqlKeywordContentRetriever`（LIKE 降级） |
| 元数据过滤检索 | ✅ | `MetadataContentRetriever` |
| Brother/Parent 上下文扩展 | ✅ | `BrotherAwareRetriever` |
| RRF 融合排序 | ✅ | `RRFContentAggregator` |
| API 重排序 | ✅ | `ReRankingContentAggregator` — DashScope gte-rerank-v2 |
| 意图识别路由 | ✅ | `IntentRecognitionService` — RAG vs 普通对话 |
| 查询改写/扩展 | ✅ | `QueryTransformerFactory` — 改写 / 压缩 / 多角度 |
| SSE 进度事件 | ✅ | `ProgressAwareContentRetriever/Aggregator` |
| RAG 引用追踪 | ✅ | `[REFERENCE]:` SSE 事件 + DB 持久化 |

### Chat & Memory
| 能力 | 状态 | 说明 |
|---|---|---|
| 流式 SSE 对话 | ✅ | `ChatController` + `EnhancedChatService` |
| Redis + MySQL 双写 Memory | ✅ | `DatabaseChatMemoryStore` |
| 对话 & 消息持久化 | ✅ | `ChatConversation` + `ChatMessage` |
| RAG 引用持久化 | ✅ | `chat_message.rag_references` |
| evictCache 防污染 | ✅ | 每轮对话前清 Redis，防上轮 RAG 内容污染意图识别 |

### Agent 能力
| 能力 | 状态 | 说明 |
|---|---|---|
| ReAct Agent（流式+同步） | ✅ | `RagReactAgent` — Spring AI，最大 8 轮 |
| RAG 知识库检索工具 | ✅ | `RagToolService.searchKnowledgeBase()` |
| 天气查询工具（示例） | ✅ | `WeatherTool.getWeather()` |
| 自我反思 Agent | ✅ | `RagReflectionAgent` — `POST /agent/reflection/chat` |
| Plan-and-Execute Agent | ✅ | `RagPlanExecuteAgent` — `POST /agent/plan-execute/chat` |
| Human-in-the-Loop (HITL) | ✅ | `RagHITLReactAgent` — 挂起/恢复状态机，`/agent/hitl/chat` + `/agent/hitl/resume` |
| 答案验证 | ✅ | `ObjectiveVerificationEngine` + `SubjectiveAssessor` + `VerificationAdvisor` |
| Web 搜索工具 | ✅ | `WebSearchTool` — Tavily API，未配置时降级 mock |
| 计算 / 代码执行工具 | ✅ | `CalculatorTool`、`CodeExecutorTool`（Groovy 沙箱校验 `GroovyCodeVerifier`） |
| Think-tag 解析 | ✅ | `ThinkTagParser` + `StreamThinkTagFilter` — 过滤 `<think>` 推理块 |

### ragcore 教学组件（独立演示端点，不接主链路）
| 能力 | 状态 | 说明 |
|---|---|---|
| 多格式 Reader 策略 | ✅ | pdf/tika/jsoup/markdown/json/text + `PdfMultimodalProcessor` |
| 教学切分器 | ✅ | Markdown 标题 / Word 标题 / 重叠段落 / 多模态 |
| ES 原生客户端检索 | ✅ | `ElasticSearchService`（独立于主工程 ES 链路） |
| Neo4j 图谱检索 | ✅ | `MovieGraphRepository` + `GraphService`（电影-导演演示数据） |
| MinIO 文件存储 | ✅ | `MinioService`（懒初始化） |
| 查询路由 / 改写 | ✅ | `QueryRouteService`（VECTOR/GRAPH/RELATIONAL 模拟）、`QueryRewriteService` |

### agentx 评测体系
| 能力 | 状态 | 说明 |
|---|---|---|
| BIRD Text-to-SQL 评测 | ✅ | `BirdEvalService` — ReactAgent + SQLite 工具（list/describe/execute/verify） |
| Agent 会话质量 Judge | ✅ | `AgentEvalJudgeService` — 基于 trace 的 LLM 评审 |
| 执行轨迹 Diagnose | ✅ | `AgentDiagnoseService` — 失败轨迹诊断 |
| Trace 查询 | ✅ | `TraceQueryService` + `agentx_conversation/session/trace` 三表 |

### know-engine（独立模块）
| 能力 | 状态 | 说明 |
|---|---|---|
| 知识引擎 | ✅ | 文档管理 / 分段 / 嵌入 / RAG modules，`KnowEngineApplication` 独立运行（auth/dingtalk 已裁剪） |

---

## 架构设计

```
┌──────────────────────────────────────────────────────────┐
│                    数据存储层                              │
│  MySQL          Redis          Milvus         ES（可选）  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐  │
│  │chat_conv │  │memory    │  │向量存储  │  │BM25索引 │  │
│  │chat_msg  │  │(TTL 1h)  │  │(必须)    │  │embedding│  │
│  │knowledge │  └──────────┘  └──────────┘  │store    │  │
│  │_document │  关键词检索降级：                │(可选)   │  │
│  │knowledge │  ES关闭→MySQL LIKE             └─────────┘  │
│  │_segment  │                                            │
│  │_version  │                                            │
│  └──────────┘                                            │
└──────────────────────────────────────────────────────────┘
```

完整的模块关系、数据流与 Mermaid 架构图见 [docs/architecture.md](./docs/architecture.md)。

---

## 工程结构

Maven 多模块工程，分层遵循 COLA 风格（详见 [docs/project-map.md](./docs/project-map.md)）：

```
third-brain-think/
├── third-brain-think-client          # 对外 DTO + ragcore 图谱模型
├── third-brain-think-domain          # 常量与领域模型（SplitType、RagReference）+ agentx 端口
├── third-brain-think-infrastructure  # 基础设施层
│   ├── third-brain-think-integration #   文档 loader/splitter、rerank、ragcore 集成、agentx sqlite
│   └── third-brain-think-persistence #   MyBatis-Plus entity/mapper、版本管理、agentx 三表
├── third-brain-think-application     # RAG 管道、Agent、检索、对话服务、ragcore 服务、agentx 评测
├── third-brain-think-interfaces      # REST Controller、全局异常处理、ragcore/agentx 端点
├── third-brain-think-starter         # 启动模块（RagDemoApplication + 配置）
├── third-brain-think-agentx-core     # 智能体框架核心库（com.agentx.ai，纯库）
└── third-brain-think-know-engine     # 知识引擎独立模块（独立运行，starter 不依赖）
```

---

## 快速开始

### 前置要求

| 依赖 | 是否必须 | 说明 |
|---|---|---|
| Java 21+ | ✅ 必须 | — |
| Maven 3.8+ | ✅ 必须 | — |
| MySQL 8 | ✅ 必须 | 对话和文档元数据持久化；ES 关闭时亦作为关键词检索降级来源 |
| Redis | ✅ 必须 | Chat Memory 缓存 |
| Milvus 2.x | ✅ 必须 | 向量存储（不可替换为 InMemory） |
| DashScope API Key | ✅ 必须 | Chat / Embedding / Rerank 模型 |
| Elasticsearch 8.x | 可选 | BM25 关键词检索；关闭时自动降级为 MySQL LIKE 检索，精度有限，生产建议开启 |
| Neo4j 5.x | 可选 | ragcore 图谱教学端点；驱动懒连接，无服务不影响启动 |
| MinIO | 可选 | ragcore 文件存储教学端点；懒初始化，无服务不影响启动 |
| DeepSeek API Key | 可选 | agentx BIRD/Judge/Diagnose 评测端点；为空不影响启动，调用评测时报错 |

> **最小启动**：MySQL + Redis + Milvus + DashScope API Key。
> 开启 ES：将 `application.yml` 中 `elasticsearch.enable` 改为 `true`，并确保 ES 服务可用。
> Neo4j / MinIO / DeepSeek / SQLite（BIRD 评测）均为可选，仅在调用对应教学/评测端点时需要。

### 1. 启动基础服务

```bash
# MySQL（库名与 application.yml 中 spring.datasource.url 保持一致，当前为 ardm）
docker run -d --name mysql8 \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=ardm \
  mysql:8.0

# Redis
docker run -d --name redis -p 6379:6379 redis:7

# Milvus（必须）
docker run -d --name milvus-standalone \
  -p 19530:19530 \
  -p 9091:9091 \
  milvusdb/milvus:v2.4.0 milvus run standalone

# Elasticsearch（可选，开启后 BM25 关键词检索生效；关闭时自动降级为 MySQL LIKE）
# docker run -d --name es8 \
#   -p 9200:9200 \
#   -e "discovery.type=single-node" \
#   -e "xpack.security.enabled=false" \
#   docker.elastic.co/elasticsearch/elasticsearch:8.11.0
```

### 2. 初始化数据库

```bash
# 执行建表脚本
mysql -u root -proot ardm < third-brain-think-starter/src/main/resources/db/migration.sql
```

### 3. 配置 API Key

编辑 `third-brain-think-starter/src/main/resources/application.yml`（推荐改用环境变量 `DASHSCOPE_API_KEY`，避免明文入库）：

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: sk-xxxx          # DashScope API Key
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model-name: qwen-plus
    embedding-model:
      api-key: sk-xxxx
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      model-name: text-embedding-v3

spring:
  ai:
    openai:
      api-key: sk-xxxx          # Spring AI ChatModel（Agent 用）
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      chat:
        options:
          model: qwen-plus

rag:
  rerank:
    api-key: sk-xxxx            # Rerank API Key
```

可选（agentx 评测，未配置时应用可正常启动）：

```yaml
spring:
  ai:
    deepseek:
      api-key: sk-xxxx          # 或环境变量 DEEPSEEK_API_KEY
```

### 4. 编译运行

```bash
# 在项目根目录（多模块全量构建）
mvn clean package -DskipTests

# 运行
java -Xms1g -Xmx2g -jar third-brain-think-starter/target/third-brain-think-starter-1.0.0-SNAPSHOT.jar
```

访问：**http://localhost:8088**

---

## API 参考

### 文档管理

```bash
# 上传文档（支持 pdf/docx/md/txt/xlsx/xls/csv）
curl -X POST http://localhost:8088/api/document/upload \
  -F "file=@doc.pdf" \
  -F "title=示例文档" \
  -F "uploadUser=hollis" \
  -F "description=测试" \
  -F "knowledgeBaseType=DOCUMENT_SEARCH"

# 手动切分文档
curl -X POST "http://localhost:8088/api/document/split/1?splitType=HYBRID&chunkSize=500&overlap=50"

# 列出文档版本
curl http://localhost:8088/api/document/{docId}/versions

# 激活指定版本
curl -X POST http://localhost:8088/api/document/versions/{versionId}/activate

# 停用指定版本
curl -X POST http://localhost:8088/api/document/versions/{versionId}/deactivate

# 批量上传 / 批量删除 / 状态查询 / 分段查询 / 重试嵌入 / 删除文档
curl -X POST http://localhost:8088/api/document/knowledge/batch-upload
curl -X DELETE http://localhost:8088/api/document/knowledge/batch
curl http://localhost:8088/api/document/knowledge/status
curl http://localhost:8088/api/document/knowledge/{docId}/segments
curl -X POST http://localhost:8088/api/document/knowledge/{docId}/retry-embed
curl -X DELETE http://localhost:8088/api/document/{docId}
```

### 流式 RAG 对话

```bash
# SSE 流式对话（推荐）
curl -X POST http://localhost:8088/chat/send \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"conv-001","question":"Tesla Model 3的续航里程？"}'
```

**SSE 事件流格式**：
```
data: [PROGRESS]:正在分析意图...
data: [PROGRESS]:正在转换查询...
data: [PROGRESS]:正在检索向量库...
data: [PROGRESS]:正在检索关键词库...
data: [PROGRESS]:正在检索关联片段...
data: [PROGRESS]:正在排序筛选结果...
data: [PROGRESS]:正在生成回答...
data: Tesla Model 3 标准续航版
data: 续航里程约 491 公里
data: [REFERENCE]:[{"docId":"xxx","chunkContent":"...","score":0.94}]
data: [DONE]
```

### Agent 对话

```bash
# 同步 Agent 对话（ReAct 多轮工具调用）
curl -X POST http://localhost:8088/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"agent-001","question":"北京今天天气怎么样？同时查一下知识库里有没有关于Tesla的信息"}'

# 流式 Agent 对话（SSE）
curl -X POST http://localhost:8088/agent/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"agent-001","question":"帮我查一下Tesla续航并结合北京气温给个出行建议"}'

# 自我反思 Agent
curl -X POST http://localhost:8088/agent/reflection/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"agent-002","question":"Tesla Model 3 的续航里程是多少？"}'

# Plan-and-Execute Agent
curl -X POST http://localhost:8088/agent/plan-execute/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"agent-003","question":"先查 Tesla 续航，再结合天气给出出行建议"}'

# HITL Agent（interrupted=true 时取 pendingToolCalls，人工审批后恢复）
curl -X POST http://localhost:8088/agent/hitl/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"agent-004","question":"查询知识库中的 Tesla 资料"}'

# HITL 恢复执行（回传挂起时的 pendingToolCalls / checkpointMessages / context）
curl -X POST http://localhost:8088/agent/hitl/resume \
  -H "Content-Type: application/json" \
  -d '{"pendingToolCalls":[{"id":"call_xxx","name":"getWeather","arguments":"{}","result":"APPROVED"}],"checkpointMessages":[],"context":{}}'
```

### 对话管理

```bash
curl http://localhost:8088/chat/list                                  # 会话列表
curl "http://localhost:8088/chat/messages?conversationId=conv-001"    # 消息记录
curl -X PUT http://localhost:8088/chat/conversations/conv-001/title \
  -H "Content-Type: application/json" -d '"新标题"'                    # 重命名会话
curl -X DELETE http://localhost:8088/chat/conversations/conv-001      # 删除会话
```

### ragcore 教学端点（可选服务，未启动对应服务时调用报错）

```bash
# 前缀一览：/rag（模块化演示）、/rag/embedding、/rag/es、/rag/files、/rag/generate、
#           /rag/graph（需 Neo4j）、/rag/hybrid、/rag/image、/rag/metadata、
#           /rag/modular、/rag/retriever、/rag/rewrite、/rag/router
curl "http://localhost:8088/rag/graph/retrieve?movieName=影"          # Neo4j 图谱检索演示
```

### agentx 评测端点（需 DEEPSEEK_API_KEY）

```bash
curl -X POST http://localhost:8088/bird/eval/run   -H "Content-Type: application/json" -d '{...}'   # BIRD Text-to-SQL 评测
curl -X POST http://localhost:8088/agent/eval/judge -H "Content-Type: application/json" -d '{...}'  # 会话质量 Judge
# 另有 Diagnose / trace 查询端点，详见 BirdEvalController / AgentEvalController
```

### know-engine（独立模块，独立启动）

```bash
mvn spring-boot:run -pl third-brain-think-know-engine   # 独立运行，端口与配置见其自带 application.yml
```

---

## 核心组件详解

### RagReactAgent — ReAct 循环

```
用户问题
    │
    ▼
System Prompt (ReAct 规则)
    │
    ▼
LLM 推理 ──有 ToolCall──▶ 执行工具(RagToolService / WeatherTool)
    │                          │
    │◀────────────────── ToolResponse
    │
    └──无 ToolCall──▶ 最终答案（输出）
           ↑
     超过 maxRounds(8)?
     强制生成最终答案
```

关键特性：
- **internalToolExecutionEnabled=false** — 手动控制工具调用，避免 Spring AI 自动执行影响观察
- **流式模式**：响应式 `Flux<String>`，工具调用在 `boundedElastic` 线程池异步执行
- **同步模式**：阻塞循环，工具调用顺序执行

### DatabaseChatMemoryStore — 双写 Memory

```
getMessages(memoryId):
  1. 查 Redis key rag-demo:chat-memory:{id}
  2. hit → JSON 反序列化 → 返回
  3. miss → 查 MySQL chat_message 表
          → 序列化写 Redis（TTL 1h）
          → 返回

updateMessages(memoryId, messages):
  1. 序列化写 Redis
  2. 批量 upsert 到 MySQL

evictCache(conversationId):
  仅删 Redis key，不动 MySQL
  （在每轮 RAG 对话前调用，防止上轮 [REFERENCE]: 内容
   污染本轮的意图识别上下文）
```

### ProgressAwareContentRetriever — 装饰器模式

```java
// 使用示例（来自 RagConfiguration）
ContentRetriever wrapped = ProgressAwareContentRetriever.builder()
    .delegate(vectorRetriever)
    .progressMessage("正在检索向量库...")
    .build();
// retrieve() 被调用时，先向 SseEmitterHolder.get() 推送进度，再委托 delegate
```

### ExcelSplitter — Excel/CSV 处理

```
文件输入
    │
    ├── 魔术字节检测
    │   ├── 50 4B 03 04 → xlsx (EasyExcel 读取)
    │   ├── D0 CF 11 E0 → xls  (EasyExcel 读取)
    │   └── 其他        → csv  (BufferedReader + BOM 处理)
    │
    ├── KEY_VALUE 模式（默认）
    │   每行 → "header1: value1; header2: value2"
    │
    └── HTML_TABLE 模式
        多行分组 → <table><tr><th>/<td> HTML
```

---

## Agent 模块

### 包结构

```
agent/                                        # 位于 third-brain-think-application 模块
├── AgentChatParam.java          # 请求 DTO：conversationId + question
├── AgentChatResult.java         # 响应 DTO：conversationId + answer
├── AgentController.java         # /agent/chat、/chat/stream、/reflection/chat、/plan-execute/chat、/hitl/chat、/hitl/resume
├── RagReactAgent.java           # ReAct Agent（流式 + 同步）
├── RagReflectionAgent.java      # 自我反思 Agent
├── RagPlanExecuteAgent.java     # Plan-and-Execute Agent
├── advisor/
│   └── VerificationAdvisor.java # 答案验证切面
├── hitl/                        # HITL 状态机：RagHITLReactAgent / HITLState / PendingToolCall / AgentResult 等
├── prompts/                     # AgentDefaultPrompts / PlanExecutePromptsFactory
├── tool/
│   ├── RagToolService.java      # @Tool: searchKnowledgeBase(query)
│   ├── WeatherTool.java         # @Tool: getWeather(city) [示例]
│   ├── WebSearchTool.java       # @Tool: Tavily Web 搜索（未配置降级 mock）
│   ├── CalculatorTool.java      # @Tool: 数学计算
│   ├── CodeExecutorTool.java    # @Tool: Groovy 代码执行
│   └── GroovyCodeVerifier.java  # 代码安全校验
└── verification/                # 答案验证引擎：客观验证 + 主观评估 + 引用/数值一致性核查
```

### 添加新工具

```java
@Service
public class MyCustomTool {

    @Tool(name = "myTool", description = "工具描述，清晰说明用途和返回内容")
    public String doSomething(@ToolParam(description = "参数描述") String input) {
        // 实现工具逻辑
        return "结果";
    }
}
```

然后在 `RagReactAgent` 构造函数中注入并加入 `ToolCallbacks.from(...)` 即可。

---

## RAG 管道

### 检索流程（三路 → RRF → 重排）

```
用户问题
    │
    ▼ QueryTransformerFactory
意图识别 → RAG 路径 / 普通对话路径
    │
    ▼ 并行三路检索（带 ProgressAware 装饰）
    ├── VectorContentRetriever    → 语义向量检索 Top-K（Milvus）
    ├── KeywordContentRetriever   → ES BM25 全文检索 Top-K（ES 启用时）
    │                               MysqlKeywordContentRetriever（ES 关闭时自动降级）
    ├── MetadataContentRetriever  → 元数据精确过滤（ES 启用时）
    └── BrotherAwareRetriever     → Brother/Parent Chunk 扩展
    │
    ▼ RRFContentAggregator        → Reciprocal Rank Fusion
    │
    ▼ ReRankingContentAggregator  → DashScope gte-rerank-v2 精排
    │
    ▼ LangChain4j AiServices      → 流式生成答案
    │
    ▼ ProgressAwareContentAggregator → 推送 [REFERENCE]: 事件
```

### RRF 算法

```
RRF(d) = Σ  1 / (k + rank_i(d))
         i

k = 60（平滑常数）
rank_i(d) = 文档 d 在第 i 路检索结果中的排名
```

### Brother/Parent Chunk 机制

```
切分时：
  父 chunk (parentChunkId=null)  ← 完整段落，不向量化
      └── 子 chunk A (parentChunkId=P1, brotherChunkId=B)
      └── 子 chunk B (parentChunkId=P1, brotherChunkId=A)

检索时（BrotherAwareRetriever）：
  检索到子 chunk A
      → 通过 brotherChunkId 拉取 兄弟 chunk B（ES terms 查询）
      → 通过 parentChunkId 拉取 父 chunk 完整文本（MySQL）
      → 用父文本替换子文本，送入 LLM，上下文更完整
```

---

## 文档处理管道

### 上传流程

```
POST /api/document/upload
    │
    ▼ 1. 保存文件到本地（uploads/ 目录）
    ▼ 2. 插入 knowledge_document 记录（status=UPLOADED）
    ▼ 3. 创建版本记录（knowledgeDocumentVersionService.createVersion）
    ▼ 4. 自动切分（autoSplit=true 时）
         │ DocumentSplitterFactory.splitWithStrategies()
         ▼
    插入 knowledge_segment 记录（status=CREATED）
    更新 knowledge_document.status=SPLITTED
```

### 支持的切分策略

| SplitType | 实现类 | 说明 |
|---|---|---|
| `TITLE` | `MarkdownHeaderSplitter` | 按 Markdown `#` `##` `###` 切分 |
| `BROTHER` | `MarkdownHeaderBrotherTextSplitter` | 生成 brother/parent chunk 结构 |
| `OVERLAP` | `OverlapParagraphSplitter` | 固定窗口 + 重叠 |
| `HYBRID` | 组合策略 | TITLE + OVERLAP 二次切割 |
| `EXCEL` | `ExcelSplitter` | xlsx / xls / csv |
| `WORD` | `WordHeaderSplitter` | `.doc`/`.docx` 标题层级切分，需 `rag.word-splitter.enable=true` |

---

## Chat Memory

### 双写架构

```
EnhancedChatService.chat()
    │
    ├── evictCache(conversationId)     ← 清 Redis（防污染）
    │
    ├── 意图识别（IntentRecognitionService）
    │
    ├── RAG 检索 + 生成（AiServices with ChatMemoryProvider）
    │       │
    │       └── DatabaseChatMemoryStore
    │               ├── get  → Redis → MySQL（fallback）
    │               └── put  → Redis（TTL 1h）+ MySQL
    │
    └── [REFERENCE]: 事件 → chatMessageService.updateRagReferences()
```

---

## 扩展开发指南

### 新增文档格式

1. 在 `MultiFormatDocumentLoader.loadDocument()` 中添加扩展名判断
2. 如需自定义切分逻辑，实现 `DocumentSplitter` 接口
3. 在 `DocumentSplitterFactory` 中注册新的 `SplitType`

### 新增检索器

```java
// 1. 实现 ContentRetriever
public class MyRetriever implements ContentRetriever {
    @Override
    public List<Content> retrieve(Query query) { ... }
}

// 2. 在 RagConfiguration 中注册（可用 ProgressAware 包装）
@Bean
ContentRetriever myContentRetriever() {
    return ProgressAwareContentRetriever.builder()
        .delegate(new MyRetriever(...))
        .progressMessage("正在检索自定义源...")
        .build();
}

// 3. 加入 QueryRouter / MultiSourceQueryRouter 路由列表
```

### 新增 Agent 工具

```java
@Service
public class StockTool {
    @Tool(name = "getStockPrice", description = "根据股票代码查询实时价格")
    public String getStockPrice(@ToolParam(description = "股票代码，如 600519") String code) {
        ...
    }
}
// 注入 RagReactAgent 构造函数并加入 ToolCallbacks.from()
```

---

## Roadmap

### 已完成

- [x] `agent/RagReflectionAgent` — 自我反思循环
- [x] `agent/RagPlanExecuteAgent` — 规划型 Agent（Plan → Execute → Verify）
- [x] `agent/hitl/RagHITLReactAgent` — Human-in-the-Loop，支持挂起/人工审批/恢复
- [x] `agent/verification/` — 答案验证引擎（客观验证 + 主观评估 + 严重级别）
- [x] `agent/tool/WebSearchTool` — Tavily Web 搜索工具集成
- [x] `ai/ThinkTagParser` + `ai/StreamThinkTagFilter` — 过滤推理模型 `<think>...</think>` 块
- [x] `retrieval/QueryRouter` — 查询路由（NONE / KEYWORD / VECTOR / HYBRID）
- [x] `loader/DocumentCleaner` — 文档入库前清洗
- [x] `rerank/BgeScoringModel` — BGE 重排（HTTP API，失败降级词频匹配）
- [x] `retrieval/RetrievalCacheService` + `RerankRateLimiter` — 检索缓存与重排限流

### 计划中

- [ ] `retrieval/SqlDatabaseRetriever` — Text-to-SQL 检索路径（自然语言→SQL→结果）
- [ ] `retrieval/Neo4jContentRetriever` — Graph RAG（知识图谱增强检索）
- [ ] `retrieval/MultiSourceQueryRouter` 落地 GRAPH / RELATIONAL 分支（当前仅 VECTOR 可用，开关默认关闭）

---

## 注意事项

1. **API Key 安全** — 不要将 `application.yml` 中的 Key 提交到版本控制，用环境变量替代（当前 yml 中 DashScope Key 存在硬编码默认值，部署前务必移除并用 `DASHSCOPE_API_KEY` 覆盖）
2. **JVM 内存** — 大文档处理建议：`java -Xms2g -Xmx4g -jar third-brain-think-starter-1.0.0-SNAPSHOT.jar`
3. **Elasticsearch 可选** — 默认 `elasticsearch.enable=false`，关键词检索自动降级为 MySQL LIKE（精度有限）；生产环境建议开启 ES 以获得完整 BM25 检索能力。开启时若连接失败，应用会启动失败并给出明确提示，不会静默降级
4. **版本管理** — 文档上传后自动创建 v1 版本；重复上传相同内容文件（SHA-256 相同）不会创建新版本
5. **evictCache** — `EnhancedChatService` 每轮对话前主动清除 Redis 缓存，防止上轮 `[REFERENCE]:` 内容污染本轮意图识别

---

## 相关资源

- [LangChain4j 官方文档](https://docs.langchain4j.dev/)
- [Spring AI 官方文档](https://spring.io/projects/spring-ai)
- [Elasticsearch 文档](https://www.elastic.co/guide/index.html)
- [EasyExcel 文档](https://easyexcel.opensource.alibaba.com/)
- [CHANGELOG](./CHANGELOG.md) — 完整变更历史
- [docs/project-map.md](./docs/project-map.md) — 模块与目录结构参考
- [docs/architecture.md](./docs/architecture.md) — 架构说明与 FAQ

---

**License**: Apache-2.0  
**作者**: Hollis  
**课程**: LLMentor — 带你系统学习大语言模型开发
