package cn.hollis.llm.mentor.ragdemo.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * RAG 流式问答服务
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
           streamingChatModel = "openAiStreamingChatModel",
           chatMemoryProvider = "chatMemoryProvider",
           contentRetriever = "ragContentRetriever")
public interface RagStreamingService {

    @SystemMessage("""
            你是一个专业的RAG问答助手。请根据提供的上下文信息，详细、准确地回答用户的问题。
            
            ## 任务要求：
            1. 请基于以下提供的参考文档内容，回答用户的问题。
            2. 如果参考文档中没有相关信息，请直接说明"没有找到相关信息"，不要编造内容。
            3. 尽量贴合用户的问题需求，提供有价值的回答。
            
            ## 格式要求：
            1. 使用清晰的段落结构
            2. 重要信息可以加粗或列表展示
            
            注意：如果参考文档内容为空，请直接回答"没有找到相关信息"。
            """)
    Flux<String> streamChat(@MemoryId String userId, @UserMessage String message);
}
