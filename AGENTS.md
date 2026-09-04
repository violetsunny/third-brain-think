# AGENTS.md

面向 AI 助手的项目工作指南。人类可读的项目介绍见 [README.md](./README.md)，结构参考见 [docs/project-map.md](./docs/project-map.md)，架构说明见 [docs/architecture.md](./docs/architecture.md)。

## 项目概览

- 名称：`third-brain-think`（前身 `rag-demo`，由 `LLMentor` 工程重构而来）
- 定位：基于 LangChain4j + Spring AI 的生产级 RAG + Agent 学习参考实现
- 语言/框架：Java 21、Spring Boot 3.5.6、Maven 多模块
- 基础包名：`top.kdla.framework.llm.mentor.rag`（已从 `cn.hollis` 前缀统一迁移）

## 仓库结构

```text
third-brain-think/                     # 父 POM，packaging=pom
├── third-brain-think-client/          # 对外 DTO（upload/split/batch 参数与结果）
├── third-brain-think-domain/          # 领域常量（SplitType 等）与 RAG 引用模型
├── third-brain-think-infrastructure/  # 基础设施层（聚合 POM）
│   ├── third-brain-think-integration/ # 文档 loader/splitter、rerank、异步配置
│   └── third-brain-think-persistence/ # MyBatis-Plus entity/mapper、版本管理
├── third-brain-think-application/     # 核心业务：RAG 管道、Agent、检索、对话服务
├── third-brain-think-interfaces/      # REST Controller 与全局异常处理
└── third-brain-think-starter/         # 启动模块：RagDemoApplication + 配置 + 静态页
```

## 入口点

- 启动类：`third-brain-think-starter/src/main/java/top/kdla/framework/llm/mentor/rag/RagDemoApplication.java`
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

## 关键规范

1. 分层依赖方向：`interfaces` → `application` → `infrastructure` / `domain` / `client`；`starter` 聚合所有模块。新增代码先判断归属模块，不要把业务逻辑放进 `starter`。
2. 包名统一使用 `top.kdla.framework.llm.mentor.rag` 前缀，不要引入 `cn.hollis` 旧前缀。
3. 可选组件（ES、Word 切分、多源路由、HyDE、BGE Reranker、Tavily）一律由配置开关控制且默认关闭，新增可选能力必须沿用该模式，不得引入新的必须依赖。
4. ES 开启时连接失败必须 fail-fast（启动报错），不允许静默降级。
5. 不要在 `application.yml` 或代码中提交真实 API Key，敏感值一律走环境变量。
6. 日志序列化整个对象时使用 `JSONObject.toJSONString(obj)`，不逐参数打印。

## Agent 工作指引

1. 改动前先读目标模块的既有代码，遵循现有命名与注入方式（构造注入 + `@Qualifier` 区分同接口多实现，如 `keywordContentRetriever`）。
2. 检索器、聚合器新增时参考 `config/RagConfiguration` 中的 `ProgressAware` 装饰器包装方式。
3. Agent 工具为带 `@Tool` 注解的 Spring Bean，注册位置在对应 Agent 的构造函数 `ToolCallbacks.from(...)`。
4. 涉及数据库表结构变更时，同步更新 `db/migration.sql` 并在 CHANGELOG 记录 DDL。
5. 变更完成后更新 [CHANGELOG.md](./CHANGELOG.md) 的 `[Unreleased]` 段。
