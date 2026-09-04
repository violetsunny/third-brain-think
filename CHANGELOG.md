# Changelog

所有对 `third-brain-think`（前身 `rag-demo`）的重要变更均记录在此文件中。  
格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [Semantic Versioning](https://semver.org/)。

---

## [Unreleased]

### 工程重构（Breaking）

- 单模块 `rag-demo` 拆分为 Maven 多模块工程，artifactId 由 `LLMentor` 更名为 `third-brain-think`，分层遵循 COLA 风格：
  - `third-brain-think-client` — 对外 DTO
  - `third-brain-think-domain` — 常量与领域模型（`SplitType`、`RagReference` 等）
  - `third-brain-think-infrastructure` — 聚合 `third-brain-think-integration`（loader / splitter / rerank）与 `third-brain-think-persistence`（entity / mapper / 版本管理）
  - `third-brain-think-application` — RAG 管道、Agent、检索、对话服务
  - `third-brain-think-interfaces` — REST Controller 与全局异常处理
  - `third-brain-think-starter` — 启动模块（`RagDemoApplication`、`application.yml`、建表脚本、静态页）
- 统一包路径为 `top.kdla.framework.llm.mentor.rag`，替换原有 `cn.hollis` 前缀
- 构建命令变更为根目录 `mvn clean package -DskipTests`，产物为 `third-brain-think-starter/target/third-brain-think-starter-1.0.0-SNAPSHOT.jar`

### 新增

#### Agent 编排（原“计划中”项已落地）

- `agent/RagReflectionAgent` — 自我反思 Agent，`POST /agent/reflection/chat`
- `agent/RagPlanExecuteAgent` — Plan-and-Execute Agent，`POST /agent/plan-execute/chat`
- `agent/hitl/RagHITLReactAgent` — HITL 状态机（挂起 / 审批 / 恢复），`POST /agent/hitl/chat` + `/agent/hitl/resume`
- `agent/verification/` — 答案验证引擎：`ObjectiveVerificationEngine`、`CitationVerifier`、`NumericConsistencyVerifier`、`SubjectiveAssessor`、`VerificationAdvisor`
- `agent/tool/WebSearchTool` — Tavily Web 搜索，未配置 API Key 时降级 mock
- `agent/tool/CalculatorTool`、`CodeExecutorTool`、`GroovyCodeVerifier` — 计算与 Groovy 代码执行工具
- `agent/prompts/` — Agent 提示词集中管理

#### RAG 管道

- `ai/ThinkTagParser` + `ai/StreamThinkTagFilter` — 流式过滤推理模型 think 块（DeepSeek / QwQ），处理跨 token 边界
- `retrieval/QueryRouter` — 查询路由（NONE / KEYWORD / VECTOR / HYBRID），已接入 `EnhancedChatService` 主链路
- `retrieval/MultiSourceQueryRouter` — 第二级查询路由（VECTOR / GRAPH / RELATIONAL），仅当 `rag.retrieval.enable-multi-source-routing=true` 时注册 Bean，默认关闭
- `retrieval/RetrievalCacheService` + `RetrievalCacheInvalidator` — 检索结果缓存（TTL 由 `rag.cache.retrieval-ttl-seconds` 控制）
- `retrieval/RerankRateLimiter` — Rerank API 限流（`rag.rerank.rate-limit-qps`）
- `retrieval/HyDEQueryTransformer` — HyDE 查询变换，`rag.hyde.enabled` 默认关闭
- `transformer/QueryComplexityClassifier` — 查询复杂度分类，驱动改写策略选择
- `loader/DocumentCleaner` — 文档入库前清洗（去控制字符、压缩空白、截断超长文本）
- `splitter/WordHeaderSplitter` + `WordSplitterInitializer` — Word 文档标题层级切分，`rag.word-splitter.enable` 默认关闭
- `constant/SplitType.WORD` — `DocumentSplitterFactory` 新增 WORD 路由，开关关闭时抛 `IllegalArgumentException`
- `rerank/BgeScoringModel` — BGE 重排 HTTP 调用，失败降级词频匹配
- `event/DocumentSplitCompletedEvent` + `EmbeddingEventListener` — 切分完成后异步嵌入
- `application.yml` 新增可选开关（均默认关闭）：`rag.word-splitter.enable`、`rag.retrieval.enable-multi-source-routing`、`rag.hyde.enabled`、`rag.query-expansion.enabled`、`rag.context-aware.enabled`

#### 接口

- `interfaces` 层新增 `GlobalExceptionHandler` / `BusinessException` / `ApiResponse` 统一异常与响应封装
- `ChatController` 新增会话管理端点：`GET /chat/list`、`GET /chat/messages`、`PUT /chat/conversations/{id}/title`、`DELETE /chat/conversations/{id}`
- `KnowledgeDocumentController` 新增：批量上传 / 批量删除 / 状态查询 / 分段查询 / 重试嵌入 / 删除文档
- `RagDemoController` — `/api/rag/**` 简化演示端点（upload / query / simple-query / query-with-sources / health）

### 最小启动配置无变化

MySQL + Redis + Milvus + `DASHSCOPE_API_KEY`，所有新增组件默认关闭，不引入额外必须依赖。

### 计划中

- `retrieval/SqlDatabaseRetriever` — Text-to-SQL 检索路径（stub）
- `retrieval/Neo4jContentRetriever` — Graph RAG 检索路径（stub）

---

## [4.0.0] — 2026-08 · 可选组件改造：Milvus 必选 + ES 可选 + MySQL LIKE 降级

### 背景

原始实现强依赖 Elasticsearch（向量存储 + BM25 关键词检索），导致本地开发必须同时启动 ES。本次改造将 Milvus 确立为唯一必须的向量存储，ES 降为可选组件，并在 ES 关闭时提供 MySQL LIKE 关键词检索降级路径。

### 变更

#### 最小启动依赖

- **之前**：MySQL + Redis + Elasticsearch（必须）
- **之后**：MySQL + Redis + Milvus（必须）；Elasticsearch 可选

#### 配置变更（`application.yml`）

```yaml
# Milvus — 必须，始终启用
milvus:
  enable: true
  host: localhost
  port: 19530

# Elasticsearch — 可选，默认关闭
elasticsearch:
  enable: false        # 改为 true 并确保 ES 服务可用，即可启用 BM25 检索
  uris: http://localhost:9200
  index-name: rag_demo_index
```

> 注意：`elasticsearch.enable=true` 时若 ES 连接失败，应用启动失败并输出明确错误信息，不会静默降级。

#### 新增

- `retrieval/MysqlKeywordContentRetriever` — 实现 `ContentRetriever` 接口，通过 `LIKE %{query}%` 对 `knowledge_segment.content` 执行关键词检索；通过 `@ConditionalOnMissingBean(name="esKeywordContentRetriever")` 与 ES 实现互斥，ES 激活时自动跳过

#### 改造

- `config/ElasticsearchConfig`
  - 整个类加 `@ConditionalOnProperty(name="elasticsearch.enable", havingValue="true")`，ES 关闭时所有 ES Bean 均不注册
  - 新增 `ElasticsearchEmbeddingStore` Bean（LangChain4j 向量存储，双写附加写入）
  - 新增 `esKeywordContentRetriever` Bean（同时注册别名 `keywordContentRetriever`），ES 连接失败时启动报错（fail-fast，不静默降级）

- `config/LangChain4jConfig`
  - 移除 `@PostConstruct initStores()` 及对 `VectorStoreService` 的注入
  - 配置类仅负责模型 Bean 初始化，不耦合向量存储

- `embedding/VectorStoreService`
  - Milvus 在 `@PostConstruct` 中强制初始化（必须，失败则应用启动失败）
  - `ElasticsearchEmbeddingStore` 改为 `@Autowired(required=false)` 可选注入
  - 写入：Milvus 失败抛异常；ES 失败仅记录 ERROR 日志，不阻断主流程
  - 检索：始终使用 Milvus，不依赖 ES

- `service/EnhancedChatService`
  - `keywordContentRetriever` 字段类型由具体类 `KeywordContentRetriever` 改为接口 `ContentRetriever`
  - 构造函数注入改为 `@Qualifier("keywordContentRetriever")` + `@Autowired(required=false)`，兼容 ES 和 MySQL 两种实现

- `retrieval/HybridRetrievalService`
  - `keywordSearch()` 统一通过 `@Qualifier("keywordContentRetriever") ContentRetriever` 执行，消除了原先直接调用 ES Java Client 的内部实现路径（两套 BM25 路径合并为一套）
  - `metadataSearch()` 在 ES 不可用时静默跳过（无通用降级策略）

#### 文档更新

- `README.md`：更新前置要求、快速开始（启动脚本）、技术栈表、架构图、RAG 管道检索流程、注意事项
- `CHANGELOG.md`：本条目

### 降级行为说明

| 配置 | 向量检索 | 关键词检索 | 元数据检索 |
|---|---|---|---|
| `elasticsearch.enable=false`（默认） | Milvus ✅ | MySQL LIKE ⚠️（精度有限） | 跳过 |
| `elasticsearch.enable=true`，ES 正常 | Milvus ✅ | ES BM25 ✅ | ES Bool Query ✅ |
| `elasticsearch.enable=true`，ES 不可达 | 应用启动失败（fail-fast） | — | — |

---

## [3.0.0] — 2025-07 · Bug 修复 & RAG 管道完善 & Agent 深化 & ThinkTag 集成

### 修复

#### Bug 修复
- **重复 chatMemoryProvider Bean**：`LangChain4jConfig` 中删除了内存版 `chatMemoryProvider` Bean 及相关 import，避免与 `RagConfiguration` 中 DatabaseChatMemoryStore 版本冲突导致 Spring 上下文启动失败
- **零向量写入**：`VectorStoreService.addSegments()` 对 `skipEmbedding=true` 的父分段不再写入零向量到 Milvus / ES，父分段只存 MySQL，由 `BrotherAwareRetriever` 通过 `parentChunkId` 按需加载，消除虚假相似度匹配

### 新增 & 完善

#### RAG 管道
- **QueryRouter 接入主链路**（`service/EnhancedChatService`）
  - 注入 `QueryRouter` 和 `KeywordContentRetriever`
  - `streamChat()` 在意图为 RAG 后调用 `queryRouter.route(message)` 获取路由类型，再传入 `ragChat()` 重载
  - `NONE` → 跳过检索，直接 LLM 回答；`KEYWORD` → 仅关键词检索；`VECTOR`/`HYBRID` → 完整混合检索
- **DocumentCleaner 集成**（`loader/DocumentCleaner`、`service/impl/DocumentProcessServiceImpl`）
  - `DocumentCleaner` 新增 `cleanText(String)` 和 `cleanText(String, int)` 公共方法，暴露纯文本清洗管道
  - `DocumentProcessServiceImpl.split()` 在加载文档后、分割前调用 `documentCleaner.cleanText()` 去控制字符、压缩空白、截断超长文本
- **BgeScoringModel 升级**（`rerank/BgeScoringModel`）
  - 新增 `bge.reranker.enabled`、`bge.reranker.url`、`bge.reranker.timeout-ms` 三个配置项
  - `enabled=true` 时调用 HTTP API（兼容 FlagEmbedding / Jina Reranker 接口规范）
  - HTTP 调用失败自动降级为词频匹配（mock）

#### Agent
- **WebSearchTool 接入 Tavily**（`agent/tool/WebSearchTool`）
  - 新增 `tavily.api-key`、`tavily.search-depth`、`tavily.max-results` 配置项
  - `api-key` 非空时调用 Tavily Search API，格式化 `answer` + `results` 输出
  - `api-key` 为空或调用失败时降级为 mock 输出

#### ThinkTag 集成
- **StreamThinkTagFilter**（`ai/StreamThinkTagFilter`）
  - 有状态的流式 `<think>` 标签过滤器，处理跨 token 边界的标签片段
  - 静态方法 `StreamThinkTagFilter.filter(Flux<String>)` 返回干净的 token 流
- **EnhancedChatService** — `streamChat()` 的所有返回路径（普通聊天 / NONE 路由 / RAG 路由）均包装 `StreamThinkTagFilter.filter()`
- **RagReactAgent** — `stream()` 返回的 `Flux<String>` 包装 `StreamThinkTagFilter.filter()`

### 配置变更（`application.yml`）
```yaml
# BGE Reranker HTTP API
bge:
  reranker:
    enabled: false          # 设为 true 启用真实 BGE HTTP 调用
    url: http://localhost:8000/rerank
    timeout-ms: 5000

# Tavily Web Search
tavily:
  api-key: ""               # 填写 Tavily API Key 以启用 Web 搜索
  search-depth: basic       # basic 或 advanced
  max-results: 5
```

---

## [2.0.0] — 2025-07 · Agent 集成 & 文档版本管理

### 新增

#### Agent 集成（`agent/` 包）
- `agent/RagReactAgent` — Spring AI 驱动的 ReAct Agent，支持流式与非流式两种模式，最大推理轮次可配置（默认 8 轮），轮次耗尽时强制生成最终答案
- `agent/tool/RagToolService` — `@Tool searchKnowledgeBase(query)` 将知识库混合检索封装为 Agent 可调用工具
- `agent/tool/WeatherTool` — `@Tool getWeather(city)` 示例工具，演示多工具协作
- `agent/AgentController` — `POST /agent/chat`（同步）和 `POST /agent/chat/stream`（SSE 流式）两个端点
- `agent/AgentChatParam` / `agent/AgentChatResult` — 请求/响应 DTO

#### 文档版本管理（`versioning/` 包）
- `versioning/KnowledgeDocumentVersion` — 版本实体，字段：`versionId`、`docId`、`version`、`contentHash`（SHA-256）、`status`、`changelog`
- `versioning/KnowledgeDocumentVersionMapper` — MyBatis-Plus BaseMapper
- `versioning/KnowledgeDocumentVersionService` / `KnowledgeDocumentVersionServiceImpl`
  - `createVersion(docId, fileBytes, changelog)` — SHA-256 内容去重，重复内容直接返回已有版本
  - `listVersions(docId)` — 按版本号降序列出
  - `activateVersion(versionId)` — 激活指定版本，更新 `knowledge_document.current_version_id`
  - `deactivateVersion(versionId)` — 停用指定版本，自动回退到版本号最大的其他 ACTIVE 版本（无则置 NULL）
- `entity/KnowledgeDocument` — 新增 `currentVersionId` 字段（`@TableField("current_version_id")`）
- `controller/KnowledgeDocumentController` — 新增端点：
  - `GET /api/document/{docId}/versions`
  - `POST /api/document/versions/{versionId}/activate`
  - `POST /api/document/versions/{versionId}/deactivate`
- `service/impl/DocumentProcessServiceImpl` — 文档上传成功后自动调用 `createVersion()` 创建初始版本记录

### 修复
- `config/MybatisPlusConfig` — 移除在 mybatis-plus 3.5.9 中已被删除的 `PaginationInnerInterceptor`，解决编译错误

### 数据库变更
```sql
-- 新增版本管理表
CREATE TABLE knowledge_document_version (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    version_id   VARCHAR(64)  NOT NULL UNIQUE,
    doc_id       VARCHAR(64)  NOT NULL,
    version      INT          NOT NULL DEFAULT 1,
    content_hash VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    changelog    TEXT,
    create_time  DATETIME     NOT NULL,
    INDEX idx_doc_id (doc_id),
    INDEX idx_content_hash (doc_id, content_hash)
);

-- knowledge_document 新增当前版本字段
ALTER TABLE knowledge_document
    ADD COLUMN current_version_id VARCHAR(64) NULL;
```

---

## [1.3.0] — 2025-07 · Excel/CSV 文档支持

### 新增
- `splitter/ExcelSplitter` — 支持 `.xlsx` / `.xls` / `.csv` 三种格式
  - 魔术字节自动检测（`50 4B 03 04` → xlsx，`D0 CF 11 E0` → xls，其余尝试 csv）
  - **KEY_VALUE 模式**：每行转为 `header1: value1; header2: value2` 文本段
  - **HTML_TABLE 模式**：按 `chunkSize` 字符上限分组多行，生成 `<table>` HTML
  - CSV 解析支持 UTF-8 BOM 自动去除
  - 使用 EasyExcel 流式读取，低内存占用
- `constant/SplitType` — 新增 `EXCEL` 枚举值
- `splitter/DocumentSplitterFactory` — 注册 `EXCEL → ExcelSplitter`
- `loader/MultiFormatDocumentLoader` — 识别 `.xlsx` / `.xls` / `.csv` 扩展名并路由

### 依赖
```xml
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>easyexcel</artifactId>
    <version>3.3.4</version>
</dependency>
```

---

## [1.2.0] — 2025-07 · Brother/Parent Chunk 检索完善

### 新增 & 完善
- `retrieval/BrotherAwareRetriever` — 完整实现 `fetchRelatedContents()`
  - 从 content metadata 提取 `brotherChunkId`，通过 ES `terms` 查询批量拉取兄弟 chunk
  - 提取 `parentChunkId`，通过 `KnowledgeSegmentMapper` 查询父 chunk 文本
  - 用父 chunk 文本替换对应 content 的 `TextSegment`（保留原始 metadata）
  - 本地 `Map` 缓存避免重复查询
  - 改为 `@Component` + `@Autowired(required=false)` 注入，ES/MySQL 均为可选依赖
- `config/RagConfiguration` — 新增 `brotherContentRetriever` Bean，注册到检索链

---

## [1.1.0] — 2025-07 · RAG 引用追踪 & 进度事件精细化

### 新增

#### RAG 引用追踪（`reference/` 包）
- `reference/RagReference` — 引用数据类：`docId`、`docName`、`chunkId`、`chunkContent`（截断 200 字符）、`score`
- `reference/ReferenceUtil` — 静态工具：`fromAggregatedContents(List<Content>)` 将检索内容转为 `List<RagReference>`
- `progress/ProgressAwareContentAggregator` — 聚合完成后自动推送 `[REFERENCE]:` JSON 事件（SSE）
- `controller/ChatController` — 捕获 `[REFERENCE]:` 前缀的 SSE 事件，调用 `chatMessageService.updateRagReferences()` 持久化引用
- `service/ChatMessageService` — 新增 `updateRagReferences(messageId, ragReferencesJson)`
- `entity/ChatMessage` — 新增 `ragReferences TEXT` 字段

#### SSE 进度事件精细化（`progress/` 包）
- `progress/SseEmitterHolder` — `ThreadLocal<SseEmitter>` 持有器，`set/get/clear` 静态方法
- `progress/ProgressAwareContentRetriever` — 装饰器模式，检索前向 SSE 推送 `[PROGRESS]:消息`
- `progress/ProgressAwareContentAggregator` — 聚合前后分别推送进度事件
- `config/RagConfiguration` — 用 ProgressAware 装饰器包装所有 ContentRetriever 和 ContentAggregator

**SSE 事件序列**：
```
[PROGRESS]:正在分析意图...
[PROGRESS]:正在转换查询...
[PROGRESS]:正在检索向量库...
[PROGRESS]:正在检索关键词库...
[PROGRESS]:正在检索元数据...
[PROGRESS]:正在检索关联片段...
[PROGRESS]:正在排序筛选结果...
[PROGRESS]:正在生成回答...
<answer tokens...>
[REFERENCE]:[{"docId":"...","chunkContent":"...","score":0.92},...]
```

### 数据库变更
```sql
ALTER TABLE chat_message ADD COLUMN rag_references TEXT NULL;
```

---

## [1.0.0] — 2025-07 · 持久化 Chat Memory & 基础架构升级

### 新增

#### 持久化 Chat Memory（`memory/` 包）
- `memory/DatabaseChatMemoryStore` — LangChain4j `ChatMemoryStore` 实现，Redis + MySQL 双写策略
  - `getMessages(memoryId)` — 先查 Redis（TTL 1h），miss 时查 MySQL 并回写 Redis
  - `updateMessages(memoryId, messages)` — 序列化写 Redis + 持久化到 MySQL
  - `deleteMessages(memoryId)` — 同时清 Redis 和 MySQL
  - `evictCache(conversationId)` — 只删 Redis key（防意图识别被上轮 RAG 引用污染）
- `config/RagConfiguration` — `chatMemoryProvider` Bean 切换为 `DatabaseChatMemoryStore` 后端
- `service/EnhancedChatService` — 每次提问前调用 `evictCache()`，彻底隔离对话轮次

#### 数据库 & 配置
- `db/migration.sql` — 完整建表 DDL（`knowledge_document`、`knowledge_segment`、`chat_conversation`、`chat_message`、`knowledge_document_version`）
- `application.yml` — 新增 Redis chat-memory key 前缀配置

### 数据库变更
```sql
ALTER TABLE chat_message ADD COLUMN rag_references TEXT NULL;
```

---

## [0.1.0] — 初始版本

### 初始功能
- 多格式文档加载（PDF、Word、Markdown、TXT）
- PDF 多模态处理（文字 + 图片描述，`PdfMultimodalProcessor`）
- DOCX 图片提取（`DocxImageExtractor`）
- 多策略文档分割：
  - `MarkdownHeaderSplitter` — 标题层级分割
  - `MarkdownHeaderBrotherTextSplitter` — 兄弟 chunk 分割（Brother/Parent 结构）
  - `OverlapParagraphSplitter` — 重叠段落分割
  - `ImageAwareSplitter` — 图像感知分割
  - `DocumentSplitterFactory` — 策略工厂
- 向量存储（Elasticsearch embedding store）
- 混合检索（`HybridRetrievalService`）：向量 + BM25 + 元数据三路 → RRF 融合 → 粗排 → 精排
- RRF 融合（`RRFContentAggregator`）
- API 重排序（`ReRankingContentAggregator`，阿里云 DashScope gte-rerank-v2）
- 意图识别（`IntentRecognitionService`）— RAG vs 普通对话路由
- 查询转换（`QueryTransformerFactory`）— 改写 / 压缩 / 多角度扩展
- 流式聊天（SSE，`EnhancedChatService`）
- 对话 & 消息持久化（MySQL + MyBatis-Plus）
- REST API：`/api/document/upload`、`/api/document/split`、`/chat/send`
