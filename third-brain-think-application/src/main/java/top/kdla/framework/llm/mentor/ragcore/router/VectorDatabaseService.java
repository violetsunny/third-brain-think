package top.kdla.framework.llm.mentor.ragcore.router;

import org.springframework.stereotype.Service;

@Service
public class VectorDatabaseService {

    /**
     * mock方法，具体的路由实现，在智能客服中讲解
     * @param query
     * @return
     */
    public String searchVectorDatabase(String query) {
        return "向量数据库搜索结果: 基于语义相似性，找到与'" + query + "'相关的文档片段。" +
                "这里模拟返回了相关的嵌入向量匹配结果，实际应用中会连接到真实的向量数据库如Chroma、Milvus、Faiss等。";
    }
}