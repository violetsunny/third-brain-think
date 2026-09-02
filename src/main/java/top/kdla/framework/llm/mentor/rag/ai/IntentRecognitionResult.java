package top.kdla.framework.llm.mentor.rag.ai;

import lombok.Data;

/**
 * 意图识别结果
 */
@Data
public class IntentRecognitionResult {
    
    /**
     * 是否与知识库相关
     */
    private boolean related;
    
    /**
     * 意图类型: RAG(知识库问答), CHAT(普通聊天)
     */
    private String intentType;
    
    /**
     * 置信度
     */
    private double confidence;
}
