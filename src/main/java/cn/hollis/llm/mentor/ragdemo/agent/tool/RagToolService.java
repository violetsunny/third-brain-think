package cn.hollis.llm.mentor.ragdemo.agent.tool;

import cn.hollis.llm.mentor.ragdemo.retrieval.HybridRetrievalService;
import cn.hollis.llm.mentor.ragdemo.retrieval.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索工具
 * 供 RagReactAgent 通过工具调用方式执行知识库检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagToolService {

    private final HybridRetrievalService hybridRetrievalService;

    @Tool(name = "searchKnowledgeBase",
          description = "在知识库中检索与问题相关的文档片段，返回最相关的内容摘要。当需要查询内部知识、文档资料或专业信息时调用此工具。")
    public String searchKnowledgeBase(
            @ToolParam(description = "检索关键词或问题描述，尽量简洁精准") String query) {
        log.info("RagToolService.searchKnowledgeBase called with query: {}", query);
        try {
            List<SearchResult> results = hybridRetrievalService.hybridSearch(query, null);
            if (results == null || results.isEmpty()) {
                return "知识库中未找到与「" + query + "」相关的内容。";
            }
            return results.stream()
                    .limit(5)
                    .map(r -> {
                        String docId = r.getDocId() != null ? r.getDocId() : "unknown";
                        String content = r.getSegment() != null ? r.getSegment().text() : "";
                        return "[文档:" + docId + "]\n" + content;
                    })
                    .collect(Collectors.joining("\n\n---\n\n"));
        } catch (Exception e) {
            log.error("Knowledge base search failed for query: {}", query, e);
            return "知识库检索失败：" + e.getMessage();
        }
    }
}
