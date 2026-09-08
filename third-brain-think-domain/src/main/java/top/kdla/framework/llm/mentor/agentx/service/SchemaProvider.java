package top.kdla.framework.llm.mentor.agentx.service;

import java.util.List;

/**
 * Schema 数据源统一接口（策略模式）。
 */
public interface SchemaProvider {

    /**
     * 列出全部表/视图（含描述 + 关联表）。
     */
    String listTables();

    /**
     * 查看指定表的字段详情。
     *
     * @param tableNames 表名列表（逗号分隔或 List）
     */
    String describeTables(List<String> tableNames);
}
