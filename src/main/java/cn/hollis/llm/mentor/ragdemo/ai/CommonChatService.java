package cn.hollis.llm.mentor.ragdemo.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * 普通聊天服务（非RAG场景）
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
           streamingChatModel = "openAiStreamingChatModel", 
           chatMemoryProvider = "chatMemoryProvider")
public interface CommonChatService {

    @SystemMessage("你是一个智能助手，请友好、专业地回答用户的问题。")
    Flux<String> streamChat(@MemoryId String userId, @UserMessage String message);
}
