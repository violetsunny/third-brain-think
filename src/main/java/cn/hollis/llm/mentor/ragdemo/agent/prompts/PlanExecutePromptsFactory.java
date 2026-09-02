package cn.hollis.llm.mentor.ragdemo.agent.prompts;

import lombok.Builder;
import lombok.Getter;

/**
 * PlanExecute Agent Prompt 工厂。
 *
 * <p>持有五个阶段的提示词：plan / execute / critique / compress / summarize。
 * 使用 {@link #buildPrompts()} 获取默认 Prompt，使用 {@link #buildPrompts(PlanExecutePromptsFactory)}
 * 合并自定义与默认值（自定义非 null 字段优先）。
 */
@Getter
@Builder
public class PlanExecutePromptsFactory {

    private String planPrompt;
    private String executePrompt;
    private String critiquePrompt;
    private String compressPrompt;
    private String summarizePrompt;

    /** 返回全套默认 Prompt。 */
    public static PlanExecutePromptsFactory buildPrompts() {
        return PlanExecutePromptsFactory.builder()
                .planPrompt(AgentDefaultPrompts.PLAN)
                .executePrompt(AgentDefaultPrompts.EXECUTE)
                .critiquePrompt(AgentDefaultPrompts.CRITIQUE)
                .compressPrompt(AgentDefaultPrompts.COMPRESS)
                .summarizePrompt(AgentDefaultPrompts.SUMMARIZE)
                .build();
    }

    /** 合并自定义与默认 Prompt（自定义非 null 字段优先）。 */
    public static PlanExecutePromptsFactory buildPrompts(PlanExecutePromptsFactory custom) {
        PlanExecutePromptsFactory defaults = buildPrompts();
        if (custom == null) {
            return defaults;
        }
        return PlanExecutePromptsFactory.builder()
                .planPrompt(custom.planPrompt != null ? custom.planPrompt : defaults.planPrompt)
                .executePrompt(custom.executePrompt != null ? custom.executePrompt : defaults.executePrompt)
                .critiquePrompt(custom.critiquePrompt != null ? custom.critiquePrompt : defaults.critiquePrompt)
                .compressPrompt(custom.compressPrompt != null ? custom.compressPrompt : defaults.compressPrompt)
                .summarizePrompt(custom.summarizePrompt != null ? custom.summarizePrompt : defaults.summarizePrompt)
                .build();
    }
}
