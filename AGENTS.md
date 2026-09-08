# AGENTS.md

面向 AI 助手的项目工作指南。人类可读的项目介绍见 [README.md](./README.md)，结构参考见 [docs/project-map.md](./docs/project-map.md)，架构说明见 [docs/architecture.md](./docs/architecture.md)。

## 项目概览

- 名称：`third-brain-think`（前身 `rag-demo`，由 `LLMentor` 工程重构而来）
- 定位：基于 LangChain4j + Spring AI 的生产级 RAG + Agent 学习参考实现
- 语言/框架：Java 21、Spring Boot 3.5.6、Maven 多模块
- 基础包名：`top.kdla.framework.llm.mentor.*`（已从 `cn.hollis` 前缀统一迁移），四个包前缀：
  - `mentor.rag` — 主 RAG 工程（原 rag-demo）
  - `mentor.ragcore` — 教学 RAG 组件演示（源自 LLMentor/rag，拆并入六模块）
  - `mentor.agentx` — BIRD/Judge/Diagnose 评测（源自 dodo-agentx eval，拆并入五模块）
  - `mentor.know.engine` — 知识引擎独立模块；`com.agentx.ai` — agentx-core 框架库（保留原包名）

## 仓库结构

```text
third-brain-think/                     # 父 POM，packaging=pom
├── third-brain-think-client/          # 对外 DTO（upload/split/batch 参数与结果）+ ragcore 图谱模型
├── third-brain-think-domain/          # 领域常量（SplitType 等）、RAG 引用模型、agentx SchemaProvider/Mschema
├── third-brain-think-infrastructure/  # 基础设施层（聚合 POM）
│   ├── third-brain-think-integration/ # 文档 loader/splitter、rerank、异步配置、ragcore reader/es/minio/neo4j、agentx sqlite
│   └── third-brain-think-persistence/ # MyBatis-Plus entity/mapper、版本管理、agentx 三表 entity/mapper
├── third-brain-think-application/     # 核心业务：RAG 管道、Agent、检索、对话服务、ragcore 路由/改写、agentx 评测服务
├── third-brain-think-interfaces/      # REST Controller 与全局异常处理、ragcore/agentx Controller
├── third-brain-think-starter/         # 启动模块：RagDemoApplication + 配置 + 静态页
├── third-brain-think-agentx-core/     # 智能体框架核心库（com.agentx.ai，ReactAgent 等，纯库无 Spring Bean 扫描）
└── third-brain-think-know-engine/     # 知识引擎独立模块（KnowEngineApplication 独立运行，starter 不依赖，langchain4j 1.11.0 隔离）
```

## 入口点

- 启动类：`third-brain-think-starter/src/main/java/top/kdla/framework/llm/mentor/rag/RagDemoApplication.java`（`scanBasePackages = top.kdla.framework.llm.mentor`，覆盖 rag/ragcore/agentx 三包）
- know-engine 独立启动类：`third-brain-think-know-engine/.../know/engine/KnowEngineApplication.java`（独立运行，不与 starter 合并，避免 `/chat` 端点冲突）
- 主配置：`third-brain-think-starter/src/main/resources/application.yml`（端口 8088）
- 建表脚本：`third-brain-think-starter/src/main/resources/db/migration.sql`、`sql/schema.sql`
- 静态页面：`third-brain-think-starter/src/main/resources/static/`（`chat.html`、`upload.html`）

## 常用命令

```bash
# 全量构建（根目录执行）
mvn clean package -DskipTests

# 运行
java -Xms1g -Xmx2g -jar third-brain-think-starter/target/third-brain-think-starter-1.0.0-SNAPSHOT.jar

# 运行测试（JUnit 5，Spring Boot 父 POM 管理）
mvn test

# 冒烟脚本
./test-rag.sh
```

## 外部依赖

| 依赖 | 是否必须 | 说明 |
|---|---|---|
| MySQL 8 | 必须 | 数据库名见 `application.yml` 的 `spring.datasource.url`（当前为 `ardm`） |
| Redis | 必须 | Chat Memory 缓存 |
| Milvus 2.x | 必须 | 向量存储，启动时强制初始化，失败即启动失败 |
| DashScope API Key | 必须 | 环境变量 `DASHSCOPE_API_KEY`，用于 Chat / Embedding / Rerank |
| Elasticsearch 8.x | 可选 | `elasticsearch.enable=false`（默认）时关键词检索降级为 MySQL LIKE |
| Neo4j 5.x | 可选 | ragcore 图谱教学演示；驱动懒连接，无服务可启动，调用图谱端点时才需可达 |
| MinIO | 可选 | ragcore 文件存储教学演示；客户端懒初始化，无服务可启动 |
| DeepSeek API Key | 可选 | 环境变量 `DEEPSEEK_API_KEY`，agentx BIRD/Judge/Diagnose 评测；为空可启动，调用评测端点时报错 |
| SQLite（内嵌） | 可选 | agentx BIRD 评测通过 sqlite-jdbc 读取评测数据库文件，无需独立服务 |

## 关键规范

1. 分层依赖方向：`interfaces` → `application` → `infrastructure` / `domain` / `client`；`starter` 聚合所有模块。新增代码先判断归属模块，不要把业务逻辑放进 `starter`。
2. 包名统一使用 `top.kdla.framework.llm.mentor` 前缀（rag / ragcore / agentx / know.engine 四个子前缀），不要引入 `cn.hollis` 旧前缀；`third-brain-think-agentx-core` 例外保留 `com.agentx.ai`（上游框架包名，便于同步）。
3. 可选组件不得引入新的必须依赖，须采用以下两种安全模式之一：a) 配置开关控制且默认关闭（ES、Word 切分、多源路由、HyDE、BGE Reranker、Tavily）；b) 懒连接——启动不依赖服务在线，仅调用相关端点时报错（Neo4j 图谱、ragcore ES 教学演示、MinIO、DeepSeek 评测）。新增可选能力必须沿用其一。
4. ES 开启时连接失败必须 fail-fast（启动报错），不允许静默降级。
5. 不要在 `application.yml` 或代码中提交真实 API Key，敏感值一律走环境变量。
6. 日志序列化整个对象时使用 `JSONObject.toJSONString(obj)`，不逐参数打印。

## Agent 工作指引

1. 改动前先读目标模块的既有代码，遵循现有命名与注入方式（构造注入 + `@Qualifier` 区分同接口多实现，如 `keywordContentRetriever`）。
2. 检索器、聚合器新增时参考 `config/RagConfiguration` 中的 `ProgressAware` 装饰器包装方式。
3. Agent 工具为带 `@Tool` 注解的 Spring Bean，注册位置在对应 Agent 的构造函数 `ToolCallbacks.from(...)`。
4. 涉及数据库表结构变更时，同步更新 `db/migration.sql` 并在 CHANGELOG 记录 DDL。
5. 变更完成后更新 [CHANGELOG.md](./CHANGELOG.md) 的 `[Unreleased]` 段。

## Agent skills

### Issue tracker

Issues 以本地 markdown 文件形式存放在 `.scratch/` 目录。See `docs/agents/issue-tracker.md`.

### Triage labels

使用默认五标签：`needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`。See `docs/agents/triage-labels.md`.

### Domain docs

Single-context：根目录 `CONTEXT.md` + `docs/adr/`。See `docs/agents/domain.md`.
