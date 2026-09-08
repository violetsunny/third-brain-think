package top.kdla.framework.llm.mentor.agentx.domain.schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * MSchema 元数据模型——对应 M-Schema dump() JSON 的结构，用 Jackson 序列化到 Redis。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Mschema(
        String dbId,
        Map<String, TableDef> tables,
        List<FkDef> foreignKeys) {

    public Mschema {
        tables = tables == null ? Map.of() : tables;
        foreignKeys = foreignKeys == null ? List.of() : foreignKeys;
    }

    /**
     * 表定义。
     */
    public record TableDef(
            String comment,
            Map<String, FieldDef> fields) {
        public TableDef {
            fields = fields == null ? Map.of() : fields;
        }
    }

    /**
     * 字段定义。
     */
    public record FieldDef(
            String type,
            boolean primaryKey,
            boolean nullable,
            String comment,
            List<String> examples) {
        public FieldDef {
            examples = examples == null ? List.of() : examples;
        }
    }

    /**
     * 外键定义。
     */
    public record FkDef(
            String table,
            String column,
            String refTable,
            String refColumn) {
    }
}
