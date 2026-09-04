package top.kdla.framework.llm.mentor.rag.transformer;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;

import static dev.langchain4j.internal.ValidationUtils.ensureNotNull;
import static java.util.Collections.singletonList;

/**
 * 查询转换器适配器 - 实现LangChain4j QueryTransformer接口
 * 将现有的QueryTransformerService适配到标准接口
 */
@Slf4j
public class CompressingQueryTransformer implements QueryTransformer {

    private final ChatModel chatModel;
    private final QueryTransformerService queryTransformerService;

    public CompressingQueryTransformer(ChatModel chatModel, QueryTransformerService queryTransformerService) {
        this.chatModel = ensureNotNull(chatModel, "chatModel");
        this.queryTransformerService = ensureNotNull(queryTransformerService, "queryTransformerService");
    }

    @Override
    public Collection<Query> transform(Query query) {
        log.debug("执行查询转换: {}", query.text());

        try {
            // 使用现有的QueryTransformerService进行重写
            List<String> rewrittenQueries = queryTransformerService.rewriteQuery(query.text());
            
            // 转换为Query对象
            List<Query> queries = rewrittenQueries.stream()
                    .map(text -> Query.from(text, query.metadata()))
                    .toList();

            log.debug("查询转换完成，生成{}个变体", queries.size());
            return queries;

        } catch (Exception e) {
            log.error("查询转换失败，返回原始查询", e);
            return singletonList(query);
        }
    }
}
