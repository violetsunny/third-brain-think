package top.kdla.framework.llm.mentor.agentx.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

/**
 * 裁判结构化输出：只打四个维度的 0-5 档位分，加权与通过判定由程序计算。
 */
@Data
public class AgentEvalJudgeOutput {

    @JsonProperty("correctness")
    @JsonPropertyDescription("正确性档位分 0-5。5=关键数据和结论全部正确没有编造；4=核心结果正确仅非常轻微的非关键偏差；3=主体正确但次要数据或结论有偏差；2=较明显错误但部分核心结果仍正确；1=关键数据或结论错误；0=明显编造用了标准事实里不存在的数据")
    private Integer correctness;

    @JsonProperty("completeness")
    @JsonPropertyDescription("完整性档位分 0-5。5=问题的每个要点都完整作答；4=仅遗漏极次要要点；3=主体要点覆盖遗漏个别次要要点；2=遗漏问题明确要求的部分要点；1=只回答了问题的一小部分；0=完全没回答问题")
    private Integer completeness;

    @JsonProperty("relevance")
    @JsonPropertyDescription("相关性档位分 0-5。5=全部内容围绕问题无无关信息；4=偶有轻微延展但整体紧扣问题；3=主体相关含少量无关分析；2=相当篇幅偏离问题；1=大部分内容与问题无关；0=完全跑题")
    private Integer relevance;

    @JsonProperty("logic")
    @JsonPropertyDescription("逻辑性档位分 0-5。5=论证严密条理清晰；4=整体清晰局部组织可改进；3=整体连贯但局部跳跃；2=组织混乱读者难以跟上；1=想到哪写到哪；0=无法理解")
    private Integer logic;

    @JsonProperty("reason")
    @JsonPropertyDescription("判决理由，一两句话，指出关键数据比对结果与扣分点")
    private String reason;
}
