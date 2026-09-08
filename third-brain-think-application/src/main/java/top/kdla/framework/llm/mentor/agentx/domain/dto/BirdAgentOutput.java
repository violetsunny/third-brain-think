package top.kdla.framework.llm.mentor.agentx.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * BIRD ReactAgent 结构化输出。
 */
@Data
public class BirdAgentOutput {

    @JsonProperty("sql")
    @JsonPropertyDescription("本题最终 SQLite SELECT/WITH 查询 SQL。必须是单条可直接执行的查询，不要 markdown，不要分号。")
    private String sql;

    @JsonProperty("requestedColumns")
    @JsonPropertyDescription("Values explicitly requested by the question/evidence, listed in final SELECT order. Use the exact schema field name when one exists, or a stable output alias otherwise. Include a rank measure only when needed; never add explanatory entity names or helper columns.")
    private List<String> requestedColumns;
}
