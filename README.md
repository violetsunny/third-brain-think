# RAG Demo — 生产级 RAG + Agent 学习参考实现

> 本模块是 **LLMentor** 课程的核心演示工程，目标是将 `rag`、`know-engine`、`agent/general-agent` 等兄弟模块的精华能力汇聚到一个可运行、可学习的参考实现中。

---

## 目录

- [项目定位](#项目定位)
- [技术栈](#技术栈)
- [能力全景](#能力全景)
- [架构设计](#架构设计)
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
rag-demo 不是生产服务，而是一个"精华提炼"的学习工程：
  know-engine  ──── 持久化 Memory / 版本管理 / 进度推送
  rag          ──── 混合检索 / RRF / 重排 / 多格式加载
  general-agent ─── ReAct Agent / Spring AI 工具调用
        ↓
     rag-demo  ──── 统一在一个模块里，可运行、可调试、可学习
```

---

## 技术栈

| 层次 | 技术 | 版本 |
|---|---|---|
| LLM 框架 | LangChain4j | 1.13.0 |
| LLM 框架 | Spring AI | 1.1.4 |
| Web 框架 | Spring Boot | 3.x |
| ORM | MyBatis-Plus | 3.5.9 |
| 向量存储 | Milvus（必须） | 2.x |
| 全文检索 / 向量存储（可选） | Elasticsearch 8.x | BM25 关键词检索 + embedding store；`elasticsearch.enable=false` 时不启动 |
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
| 自我反思 Agent | 🔜 | `ReflectionAgent` + `ReflectionAdvisor` |
| Plan-and-Execute Agent | 🔜 | `PlanExecuteAgent` |
| Human-in-the-Loop (HITL) | 🔜 | `HITLReactAgent` + 挂起/恢复状态机 |
| Web 搜索工具 | 🔜 | `WebSearchTool` |
| Think-tag 解析 | 🔜 | `ThinkTagParser` — 过滤 `<think>` 推理块 |

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

> **最小启动**：MySQL + Redis + Milvus + DashScope API Key。
> 开启 ES：将 `application.yml` 中 `elasticsearch.enable` 改为 `true`，并确保 ES 服务可用。

### 1. 启动基础服务

```bash
# MySQL
docker run -d --name mysql8 \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=rag_demo \
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
mysql -u root -proot rag_demo < src/main/resources/db/migration.sql
```

### 3. 配置 API Key

编辑 `src/main/resources/application.yml`：

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

### 4. 编译运行

```bash
# 在项目根目录
mvn clean package -pl rag-demo -am -DskipTests

# 运行
java -Xms1g -Xmx2g -jar rag-demo/target/rag-demo-1.0.0-SNAPSHOT.jar
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
agent/
├── AgentChatParam.java          # 请求 DTO：conversationId + question
├── AgentChatResult.java         # 响应 DTO：conversationId + answer
├── AgentController.java         # POST /agent/chat, /agent/chat/stream
├── RagReactAgent.java           # ReAct Agent 主体（Spring @Component）
└── tool/
    ├── RagToolService.java      # @Tool: searchKnowledgeBase(query)
    └── WeatherTool.java         # @Tool: getWeather(city) [示例]
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

// 3. 加入 DefaultQueryRouter 路由列表
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

### Agent 深化（开发中）

- [ ] `agent/ReflectionAgent` — 自我反思循环（ReflectionAdvisor）
- [ ] `agent/PlanExecuteAgent` — 规划型 Agent（先生成 Plan，再逐步 Execute）
- [ ] `agent/hitl/HITLReactAgent` — Human-in-the-Loop，支持挂起/人工审批/恢复
- [ ] `agent/tool/WebSearchTool` — Web 搜索工具集成
- [ ] `ai/ThinkTagParser` — 过滤推理模型 `<think>...</think>` 块（DeepSeek / QwQ）

### RAG 管道完善（开发中）

- [ ] `retrieval/QueryRouter` — 多路检索路由（根据意图分发到向量/ES/SQL/Graph）
- [ ] `loader/DocumentCleaner` — 文档入库前清洗（去噪、去页眉页脚、HTML 标签清理）
- [ ] `rerank/BgeScoringModel` — 本地 BGE 重排模型（无需 API，离线可用）
- [ ] `retrieval/SqlDatabaseRetriever` — Text-to-SQL 检索路径（自然语言→SQL→结果）
- [ ] `retrieval/Neo4jContentRetriever` — Graph RAG（知识图谱增强检索）

---

## 注意事项

1. **API Key 安全** — 不要将 `application.yml` 中的 Key 提交到版本控制，用环境变量替代
2. **JVM 内存** — 大文档处理建议：`java -Xms2g -Xmx4g -jar rag-demo.jar`
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

---

**作者**: Hollis  
**课程**: LLMentor — 带你系统学习大语言模型开发
