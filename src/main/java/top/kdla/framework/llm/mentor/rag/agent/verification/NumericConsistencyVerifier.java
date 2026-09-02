package top.kdla.framework.llm.mentor.rag.agent.verification;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数值一致性校验器（客观，确定性代码）。
 *
 * <p>从回答中提取数值，检查是否在检索到的源文档中出现。
 * <ul>
 *   <li>回答中的数值在源文档中均能找到 → {@link VerificationSeverity#PASS}</li>
 *   <li>回答中的某些数值在源文档中找不到 → {@link VerificationSeverity#WARNING}（可能为幻觉数值）</li>
 *   <li>回答或源文档为空 → {@link VerificationSeverity#PASS}（跳过校验）</li>
 * </ul>
 *
 * <p>注意：数值匹配是启发式的，仅检查数字字符串是否在源文本中出现。
 * 不做语义级数值比较（如 "1GB" vs "1024MB"），避免误报。
 */
@Slf4j
public class NumericConsistencyVerifier implements ObjectiveVerifier {

    /** 提取数值模式 — 匹配整数、小数、带单位的数值（如 100, 3.14, 500MB, 90%） */
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "\\d+\\.?\\d*(?:\\s*[%年月日时分秒GBgBMKkb次条个元万亿])?"
    );

    /** 排除的噪音数值 — 年份范围、常见无意义数字 */
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^\\d{1,2}$"  // 排除纯 1-2 位数（如列表编号 "1." "2." 中的数字）
    );

    @Override
    public VerificationResult verify(String answer, String retrievedContent) {
        if (answer == null || answer.isBlank() || retrievedContent == null || retrievedContent.isBlank()) {
            return VerificationResult.pass(name(), "回答或源文档为空，跳过数值校验");
        }

        Set<String> answerNumbers = extractNumbers(answer);
        Set<String> sourceNumbers = extractNumbers(retrievedContent);

        log.debug("NumericConsistencyVerifier: answerNumbers={}, sourceNumbers={}", answerNumbers, sourceNumbers);

        if (answerNumbers.isEmpty()) {
            return VerificationResult.pass(name(), "回答中无数值，跳过数值校验");
        }

        List<String> unmatched = new ArrayList<>();
        for (String num : answerNumbers) {
            if (!sourceNumbers.contains(num)) {
                unmatched.add(num);
            }
        }

        if (unmatched.isEmpty()) {
            return VerificationResult.pass(
                    name(),
                    "回答中所有数值均可在源文档中找到（" + answerNumbers.size() + " 个数值）"
            );
        }

        return VerificationResult.warning(
                name(),
                "回答中" + unmatched.size() + "个数值未在源文档中找到（可能为幻觉数值）",
                unmatched.stream()
                        .map(n -> "数值「" + n + "」未在检索到的源文档中出现")
                        .toList()
        );
    }

    @Override
    public String name() {
        return "NumericConsistencyVerifier";
    }

    private Set<String> extractNumbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            String match = matcher.group().trim();
            if (!NOISE_PATTERN.matcher(match).matches()) {
                numbers.add(match);
            }
        }
        return numbers;
    }
}
