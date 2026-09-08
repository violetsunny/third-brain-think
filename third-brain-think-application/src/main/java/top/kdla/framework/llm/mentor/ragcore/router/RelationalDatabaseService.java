package top.kdla.framework.llm.mentor.ragcore.router;

import org.springframework.stereotype.Service;

@Service
public class RelationalDatabaseService {

    /**
     * mock方法，具体的路由实现，在智能客服中讲解
     * @param query
     * @return
     */
    public String searchRelationalDatabase(String query) {
        return "关系型数据库搜索结果: 基于结构化查询，找到与'" + query + "'匹配的数据记录。" +
                "这里模拟返回了SQL查询结果，实际应用中会连接到MySQL、PostgreSQL或Oracle等关系型数据库进行精确查询和统计分析。";
    }
}