package top.kdla.framework.llm.mentor.rag.service;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/**
 * RAG助手接口 - AI服务抽象
 * 使用LangChain4j AiServices自动生成实现
 */
public interface RagAssistant {

    /**
     * 简单问答（不带来源）
     *
     * @param question 用户问题
     * @return AI回答
     */
    String answer(@UserMessage String question);

    /**
     * 带检索来源的问答
     *
     * @param question 用户问题
     * @return 包含答案和来源的结果
     */
    Result<String> answerWithSources(@UserMessage String question);
}
