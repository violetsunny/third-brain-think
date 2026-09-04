package top.kdla.framework.llm.mentor.rag.agent.verification;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用存在性校验器（客观，确定性代码）。
 *
 * <p>从回答中提取引用标记（如 {@code [文档:xxx]}），与检索结果中的文档 ID 比对。
 * <ul>
 *   <li>回答引用了检索结果中不存在的文档 → {@link VerificationSeverity#FAIL}（引用造假，硬否决）</li>
 *   <li>回答未包含任何引用标记 → {@link VerificationSeverity#WARNING}（可能未基于检索内容作答）</li>
 *   <li>所有引用均存在于检索结果 → {@link VerificationSeverity#PASS}</li>
 * </ul>
 */
@Slf4j
public class CitationVerifier implements ObjectiveVerifier {

    /** 引用标记模式，如 [文档:doc-001] — 回答和检索结果共用 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[文档:([^\\]]+)]");

    @Override
    public VerificationResult verify(String answer, String retrievedContent) {
        Set<String> citedDocIds = extractMatches(answer, CITATION_PATTERN);
        Set<String> sourceDocIds = extractMatches(retrievedContent, CITATION_PATTERN);

        log.debug("CitationVerifier: cited={}, source={}", citedDocIds, sourceDocIds);

        if (citedDocIds.isEmpty()) {
            return VerificationResult.warning(
                    name(),
                    "回答中未发现引用标记，可能未基于检索内容作答",
                    List.of("回答缺少 [文档:xxx] 格式的引用标记")
            );
        }

        List<String> fabricated = new ArrayList<>();
        for (String docId : citedDocIds) {
            if (!sourceDocIds.contains(docId)) {
                fabricated.add(docId);
            }
        }

        if (!fabricated.isEmpty()) {
            return VerificationResult.fail(
                    name(),
                    "回答引用了检索结果中不存在的文档（引用造假）",
                    fabricated.stream()
                            .map(id -> "[文档:" + id + "] 不存在于检索结果中")
                            .toList()
            );
        }

        return VerificationResult.pass(
                name(),
                "所有引用均存在于检索结果中（" + citedDocIds.size() + " 条引用）"
        );
    }

    @Override
    public String name() {
        return "CitationVerifier";
    }

    private Set<String> extractMatches(String text, Pattern pattern) {
        Set<String> matches = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return matches;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group(1).trim());
        }
        return matches;
    }
}
