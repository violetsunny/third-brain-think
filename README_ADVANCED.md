# Advanced RAG Demo

基于 LangChain4j 1.13.0 和 Spring AI 1.1.4 的高级 RAG 系统演示。

## 核心特性

### 1. 多格式文档支持
- ✅ PDF (含图片和表格的多模态处理)
- ✅ DOC/DOCX
- ✅ Markdown
- ✅ TXT

### 2. 智能文档分割
- **标题分割**: 基于Markdown标题层级智能分段
- **递归分割**: 自适应文本长度的递归切分
- **重叠分割**: 段落间保持上下文连贯的重叠策略
- **组合策略**: TITLE + RECURSIVE + OVERLAP 三重组合

### 3. 混合检索
- **向量检索**: 基于语义相似度检索 (Milvus/Elasticsearch)
- **关键词检索**: 基于BM25的全文检索 (Elasticsearch)
- **RRF融合**: Reciprocal Rank Fusion 算法融合多路结果

### 4. 重排序优化
- **粗排**: 基于相似度分数初步筛选
- **精排**: 使用 DashScope gte-rerank-v2 进行AI重排序

### 5. 查询转换
- **问题分解**: 将复杂问题拆解为多个子问题
- **问题多样化**: 生成多个语义相同的查询变体
- **问题回退**: Step-Back Prompting 抽象出本质问题
- **问题富化**: 结合对话历史补充上下文

### 6. 多存储支持
- **Milvus**: 高性能向量数据库
- **Elasticsearch**: 向量+关键词双引擎
- **Redis**: 聊天记忆存储

## 技术栈

- **LangChain4j**: 1.13.0
- **Spring AI**: 1.1.4
- **Spring Boot**: 3.x
- **PDFBox**: 3.0.1 (PDF多模态处理)
- **Apache Tika**: 文档解析
- **Milvus**: 向量存储
- **Elasticsearch**: 混合检索

## 快速开始

### 1. 环境准备

#### 必需服务
```bash
# Redis (聊天记忆)
docker run -d --name redis -p 6379:6379 redis

# Elasticsearch (可选,用于混合检索)
docker run -d --name elasticsearch \
  -p 9200:9200 -p 9300:9300 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.11.0

# Milvus (可选,用于向量存储)
docker run -d --name milvus \
  -p 19530:19530 \
  milvusdb/milvus:v2.3.0
```

### 2. 配置应用

编辑 `src/main/resources/application.yml`:

```yaml
langchain4j:
  open-ai:
    chat-model:
      api-key: ${DASHSCOPE_API_KEY}  # 替换为你的DashScope API Key
      model-name: qwen-plus
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    
    embedding-model:
      api-key: ${DASHSCOPE_API_KEY}
      model-name: text-embedding-v3
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      dimensions: 1536

milvus:
  enable: true  # 是否启用Milvus
  host: localhost
  port: 19530

elasticsearch:
  enable: true  # 是否启用Elasticsearch
  uris: http://localhost:9200
```

### 3. 启动应用

```bash
cd rag-demo
mvn clean package
java -jar target/rag-demo-1.0.0-SNAPSHOT.jar
```

或者使用 Maven:

```bash
mvn spring-boot:run
```

### 4. API 使用

#### 上传并处理文档

```bash
curl -X POST http://localhost:8088/api/rag/upload \
  -F "file=@test-documents/tesla-model3-manual.md"
```

响应:
```json
{
  "success": true,
  "fileName": "abc123_tesla-model3-manual.md",
  "filePath": "./uploads/abc123_tesla-model3-manual.md",
  "segmentCount": 45,
  "message": "文档处理成功"
}
```

#### RAG 查询

```bash
curl -X POST http://localhost:8088/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{
    "question": "Tesla Model 3的续航里程是多少?"
  }'
```

响应:
```json
{
  "success": true,
  "answer": "根据文档,Tesla Model 3的续航里程为...\n\n[详细内容]",
  "question": "Tesla Model 3的续航里程是多少?"
}
```

#### 简单查询(上传+问答一步完成)

```bash
curl -X POST http://localhost:8088/api/rag/simple-query \
  -F "file=@test-documents/sample.pdf" \
  -F "question=文档中提到了哪些关键信息?"
```

#### 健康检查

```bash
curl http://localhost:8088/api/rag/health
```

## 架构设计

```
┌─────────────┐
│  Client     │
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│  Controller     │  REST API
└──────┬──────────┘
       │
       ▼
┌─────────────────┐
│  RagCoreService │  核心编排
└──┬──┬──┬──┬─────┘
   │  │  │  │
   ▼  ▼  ▼  ▼
┌──────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│Loader│ │Splitter  │ │Retriever │ │Generator │
└──┬───┘ └────┬─────┘ └────┬─────┘ └────┬─────┘
   │          │             │            │
   │     ┌────┴────┐   ┌────┴────┐      │
   │     │TITLE    │   │Vector   │      │
   │     │RECURSIVE│   │Keyword  │      │
   │     │OVERLAP  │   └────┬────┘      │
   │     └─────────┘        │           │
   │                   ┌────┴────┐      │
   │                   │ Rerank  │      │
   │                   └────┬────┘      │
   │                        │           │
   ▼                        ▼           ▼
┌──────────────────────────────────────────┐
│         Vector Stores                    │
│  ┌──────────┐    ┌──────────────────┐   │
│  │  Milvus  │    │ Elasticsearch    │   │
│  └──────────┘    └──────────────────┘   │
└──────────────────────────────────────────┘
```

## 配置说明

### 文档分割策略

```yaml
rag:
  splitting:
    strategies: COMBINED  # TITLE, RECURSIVE, OVERLAP, HYBRID, COMBINED
    chunk-size: 500
    overlap: 50
```

**策略说明:**
- `TITLE`: 基于标题层级分割
- `RECURSIVE`: 递归分割
- `OVERLAP`: 重叠段落分割
- `HYBRID`: 标题+递归混合
- `COMBINED`: 标题+递归+重叠三重组合 (推荐)

### 检索配置

```yaml
rag:
  retrieval:
    vector-top-k: 10        # 向量检索返回数量
    keyword-top-k: 10       # 关键词检索返回数量
    final-top-k: 5          # 重排序后最终返回数量
    similarity-threshold: 0.6
    enable-hybrid: true     # 启用混合检索
    enable-rerank: true     # 启用重排序
```

### 查询重写配置

```yaml
rag:
  query:
    enable-rewrite: true
    rewrite-strategies: DECOMPOSE,DIVERSIFY,STEP_BACK,ENRICH
```

## 测试

运行测试脚本:

```bash
chmod +x test-rag.sh
./test-rag.sh
```

## 性能优化建议

1. **批量Embedding**: 使用 `max-segments-per-batch` 控制批量大小
2. **索引优化**: Milvus使用 IVF_FLAT 索引, ES使用 HNSW
3. **缓存策略**: 对常见查询结果进行缓存
4. **异步处理**: 文档处理采用异步方式,避免阻塞

## 扩展开发

### 添加新的文档格式

实现 `DocumentReaderStrategy` 接口并在 `MultiFormatDocumentLoader` 中注册。

### 自定义分割策略

实现 `DocumentSplitter` 接口并在 `DocumentSplitterFactory` 中添加新策略。

### 集成其他向量数据库

实现 `EmbeddingStore` 接口并配置到 `VectorStoreService`。

## 常见问题

**Q: PDF中的图片无法识别?**  
A: 确保配置了多模态模型 (如 qwen-vl),并且网络连接正常。

**Q: 检索结果不相关?**  
A: 调整 `similarity-threshold` 参数,或启用重排序功能。

**Q: 响应速度慢?**  
A: 检查网络延迟,考虑使用本地部署的模型和服务。

## License

MIT

## References

- [LangChain4j Documentation](https://docs.langchain4j.dev/)
- [Spring AI Documentation](https://spring.io/projects/spring-ai)
- [Milvus Documentation](https://milvus.io/docs)
