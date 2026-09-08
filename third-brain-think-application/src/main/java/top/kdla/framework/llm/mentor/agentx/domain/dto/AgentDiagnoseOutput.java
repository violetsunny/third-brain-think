package top.kdla.framework.llm.mentor.agentx.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/**
 * 诊断 Agent 结构化输出：根源错误点 + 归因 + 证据 + 建议。
 */
@Data
public class AgentDiagnoseOutput {

    @JsonProperty("rootSessionId")
    @JsonPropertyDescription("根源错误所在的 session 编号（窗口内一次提问的数字编号，字符串形式）。无法确定时为 null")
    private String rootSessionId;

    @JsonProperty("rootRound")
    @JsonPropertyDescription("根源错误所在的轮次（session 内从 1 计数），即第一次出错的那一轮。无法定位到具体轮次时为 null")
    private Integer rootRound;

    @JsonProperty("attribution")
    @JsonPropertyDescription("归因类别，四选一：模型能力 / 提示词 / 工具 / 框架")
    private String attribution;

    @JsonProperty("evidence")
    @JsonPropertyDescription("trace 证据：引用 session、round、item_index 与消息原文片段")
    private String evidence;

    @JsonProperty("analysis")
    @JsonPropertyDescription("诊断分析：错误如何发生、如何级联到最终回答")
    private String analysis;

    @JsonProperty("suggestion")
    @JsonPropertyDescription("针对归因类别的修改建议")
    private String suggestion;
}
