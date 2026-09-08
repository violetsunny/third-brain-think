package top.kdla.framework.llm.mentor.agentx.controller;

import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentDiagnoseRequest;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentDiagnoseResponse;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentEvalJudgeRequest;
import top.kdla.framework.llm.mentor.agentx.domain.dto.AgentEvalJudgeResponse;
import top.kdla.framework.llm.mentor.agentx.service.eval.AgentDiagnoseService;
import top.kdla.framework.llm.mentor.agentx.service.eval.AgentEvalJudgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 业务评测入口：判分（LLM-as-Judge）与问题诊断（带 trace 工具的 ReactAgent）。
 * <p>
 * 仅本地评测 runner 调用，不走业务鉴权。生产环境请勿暴露此接口。
 */
@Slf4j
@RestController
@RequestMapping("/agent/eval")
@RequiredArgsConstructor
public class AgentEvalController {

    private final AgentEvalJudgeService judgeService;
    private final AgentDiagnoseService diagnoseService;

    /**
     * 单次判分：比对 Agent 报告与固化标准答案，返回四维度档位分与通过判定。
     */
    @PostMapping("/judge")
    public AgentEvalJudgeResponse judge(@RequestBody AgentEvalJudgeRequest request) {
        return judgeService.judge(request);
    }

    /**
     * 问题诊断：判错之后，沿 trace 定位第一次出错的轮次（根源错误点）并归因到
     * 模型能力/提示词/工具/框架四类。同步接口，诊断耗时随被测会话复杂度增长。
     */
    @PostMapping("/diagnose")
    public AgentDiagnoseResponse diagnose(@RequestBody AgentDiagnoseRequest request) {
        return diagnoseService.diagnose(request);
    }
}
