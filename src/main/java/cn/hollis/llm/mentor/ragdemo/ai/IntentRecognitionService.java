package cn.hollis.llm.mentor.ragdemo.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

/**
 * 意图识别服务
 * 判断用户问题是否需要检索知识库
 */
@AiService
public interface IntentRecognitionService {

    @SystemMessage("""
            你是一个意图识别助手。请判断用户的问题是否适合使用知识库（RAG）来回答。
            
            判断标准：
            - 如果问题是关于特定产品、技术、文档、政策等需要专业知识的内容，回答 related=true, intentType="RAG"
            - 如果问题是日常聊天、问候、通用知识等不需要特定知识库的内容，回答 related=false, intentType="CHAT"
            
            请以 JSON 格式返回结果，包含以下字段：
            - related: boolean类型，是否与知识库相关
            - intentType: string类型，"RAG" 或 "CHAT"
            - confidence: double类型，置信度(0-1)
            
            示例1：
            用户问题："特斯拉Model 3的续航里程是多少？"
            返回：{"related": true, "intentType": "RAG", "confidence": 0.95}
            
            示例2：
            用户问题："你好，今天天气怎么样？"
            返回：{"related": false, "intentType": "CHAT", "confidence": 0.98}
            """)
    IntentRecognitionResult recognize(@UserMessage String userMessage);
}
