package top.kdla.framework.llm.mentor.rag.retrieval;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;

/**
 * HyDE (Hypothetical Document Embeddings) 查询变换器
 *
 * <p>原理：给定用户查询，先让 LLM 生成一段"假设的"回答文档，
 * 然后将该假设文档作为检索向量的输入。
 * 实验表明，假设文档与真实文档在语义空间中更近，可以提升召回率。
 *
 * <p>异常降级：LLM 调用失败时返回原始 Query，保证检索不中断。
 */
@Slf4j
@Component
public class HyDEQueryTransformer implements QueryTransformer {

    private static final String PROMPT_TEMPLATE =
            "\u8bf7\u6839\u636e\u4ee5\u4e0b\u95ee\u9898\uff0c\u751f\u6210\u4e00\u6bb5\u7b80\u6d01\u7684\u5047\u8bbe\u56de\u7b54\u6587\u6863\uff0c\u4e0d\u8d85\u8fc7 %d \u4e2a\u5b57\u3002" +
            "\u56de\u7b54\u5185\u5bb9\u5e94\u50cf\u6765\u81ea\u77e5\u8bc6\u5e93\u6587\u6863\u7684\u771f\u5b9e\u5185\u5bb9\uff0c\u76f4\u63a5\u56de\u7b54\u95ee\u9898\u3002\n\n\u95ee\u9898\uff1a%s";

    private final ChatModel chatModel;

    @Value("${rag.hyde.hypothetical-doc-max-tokens:200}")
    private int maxTokens;

    public HyDEQueryTransformer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Collection<Query> transform(Query query) {
        String originalText = query.text();
        log.debug("HyDE transforming query: {}", originalText);

        try {
            String prompt = String.format(PROMPT_TEMPLATE, maxTokens, originalText);
            ChatRequest request = ChatRequest.builder()
                    .messages(UserMessage.from(prompt))
                    .build();
            ChatResponse response = chatModel.chat(request);
            String hypotheticalDoc = response.aiMessage().text();

            if (hypotheticalDoc == null || hypotheticalDoc.isBlank()) {
                log.warn("HyDE returned blank response for query: {}, falling back to original", originalText);
                return Collections.singleton(query);
            }

            log.debug("HyDE generated hypothetical doc ({} chars) for query: {}",
                    hypotheticalDoc.length(), originalText);
            return Collections.singleton(Query.from(hypotheticalDoc, query.metadata()));

        } catch (Exception e) {
            log.warn("HyDE transform failed for query: '{}', falling back to original query. Error: {}",
                    originalText, e.getMessage());
            return Collections.singleton(query);
        }
    }
}
