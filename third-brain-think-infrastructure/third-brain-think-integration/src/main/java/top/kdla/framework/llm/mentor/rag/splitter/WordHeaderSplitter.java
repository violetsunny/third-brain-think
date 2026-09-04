package top.kdla.framework.llm.mentor.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Word 文档标题层级分割器（LangChain4j DocumentSplitter 适配器）
 *
 * <p>基于 Apache POI 解析 {@code .doc} / {@code .docx}，按 Word 标题样式（Heading 1-6）
 * 进行层级切分，支持 Parent-Child chunk 结构，将结果转换为 LangChain4j {@link TextSegment}。
 *
 * <p>移植自 {@code rag/} 教学模块的 {@code WordHeaderTextSplitter}，
 * 调整为 {@link DocumentSplitter} 接口，兼容 rag-demo 的分割器工厂和流水线。
 *
 * <p>使用前提：需在 {@code application.yml} 中设置 {@code rag.word-splitter.enable=true}，
 * 默认关闭，不影响最小启动配置。
 *
 * <p>文档的原始文件路径须通过 metadata 的 {@code "filePath"} 键传入（与 {@link ExcelSplitter} 一致）；
 * 若物理文件不存在则降级为对 document.text() 的纯文本直接切分（不按标题）。
 */
@Slf4j
public class WordHeaderSplitter implements DocumentSplitter {

    /** 需要分割的标题级别列表（1-9），对应 Word 的标题样式 */
    private final List<Integer> headingLevelsToSplitOn;
    /** 是否逐段返回（false = 聚合相同元数据的段落） */
    private final boolean returnEachParagraph;
    /** 是否在结果中移除标题段落本身 */
    private final boolean stripHeadings;
    /** 是否启用父子分段模式（在 metadata 中添加 parentChunkId） */
    private final boolean parentChildModel;
    /** 单个 chunk 最大字符数，0 表示不限制 */
    private final int chunkSize;
    /** 相邻 chunk 重叠字符数 */
    private final int overlap;

    /**
     * 推荐构造函数：按 Heading 1-3 分割，聚合模式，保留标题，启用父子关系。
     */
    public WordHeaderSplitter(int chunkSize, int overlap) {
        this(Arrays.asList(1, 2, 3, 4, 5, 6), false, false, true, chunkSize, overlap);
    }

    public WordHeaderSplitter(List<Integer> headingLevelsToSplitOn, boolean returnEachParagraph,
                               boolean stripHeadings, boolean parentChildModel,
                               int chunkSize, int overlap) {
        if (chunkSize > 0 && overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap (" + overlap + ") must be less than chunkSize (" + chunkSize + ")");
        }
        this.headingLevelsToSplitOn = headingLevelsToSplitOn != null
                ? new ArrayList<>(headingLevelsToSplitOn)
                : Arrays.asList(1, 2, 3, 4, 5, 6);
        Collections.sort(this.headingLevelsToSplitOn);
        this.returnEachParagraph = returnEachParagraph;
        this.stripHeadings = stripHeadings;
        this.parentChildModel = parentChildModel;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.info("WordHeaderSplitter: splitting document, chunkSize={}, overlap={}", chunkSize, overlap);

        // 从 metadata 读取文件路径（与 ExcelSplitter 保持一致）
        String filePath = document.metadata().getString("filePath");
        Map<String, Object> baseMetadata = new HashMap<>(document.metadata().toMap());

        List<DocumentWithMetadata> rawSegments;
        try {
            if (filePath != null && new File(filePath).exists()) {
                try (InputStream is = new FileInputStream(filePath)) {
                    rawSegments = splitWordDocument(is, baseMetadata);
                }
            } else {
                // 兜底：无物理文件时对 document.text() 直接整段返回
                log.warn("WordHeaderSplitter: no physical file found at filePath={}, falling back to plain text", filePath);
                rawSegments = splitPlainText(document.text(), baseMetadata);
            }
        } catch (Exception e) {
            throw new RuntimeException("Word 文档分割失败: " + e.getMessage(), e);
        }

        // 将内部表示转换为 LangChain4j TextSegment
        // Metadata.from(Map<String,?>) 接受 String/Integer/Long/Float/Double/UUID，过滤 null 值
        return rawSegments.stream()
                .map(seg -> {
                    Map<String, Object> filteredMeta = seg.getMetadata().entrySet().stream()
                            .filter(e -> e.getValue() != null)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    Metadata lc4jMeta = Metadata.from(filteredMeta);
                    return TextSegment.from(seg.getContent(), lc4jMeta);
                })
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────
    // Core Word parsing logic (ported from rag/ WordHeaderTextSplitter)
    // ─────────────────────────────────────────────────────────────

    private List<DocumentWithMetadata> splitWordDocument(InputStream inputStream,
                                                          Map<String, Object> baseMetadata) throws Exception {
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        bis.mark(8192);
        FileMagic fileMagic = FileMagic.valueOf(bis);
        bis.reset();

        if (fileMagic == FileMagic.OLE2) {
            return splitDocDocument(bis, baseMetadata);
        } else if (fileMagic == FileMagic.OOXML) {
            return splitDocxDocument(bis, baseMetadata);
        } else {
            throw new IllegalArgumentException("不支持的文件格式，仅支持 .doc 和 .docx 文件");
        }
    }

    private List<DocumentWithMetadata> splitDocxDocument(InputStream inputStream,
                                                           Map<String, Object> baseMetadata) throws Exception {
        List<ParagraphWithMetadata> paragraphsWithMeta = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            List<String> currentContent = new ArrayList<>();
            Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
            List<HeadingInfo> headingStack = new ArrayList<>();
            // 初始化时预置 chunkId，确保首个标题之前的正文段落（前言/封面）也有唯一标识
            Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);
            initialMetadata.put("chunkId", UUID.randomUUID().toString());

            for (XWPFParagraph paragraph : doc.getParagraphs()) {
                String text = paragraph.getText().trim();
                if (text.isEmpty()) continue;

                String style = paragraph.getStyle();
                Integer headingLevel = extractHeadingLevelFromDocx(style, paragraph);

                if (headingLevel != null && headingLevelsToSplitOn.contains(headingLevel)) {
                    while (!headingStack.isEmpty()
                            && headingStack.get(headingStack.size() - 1).getLevel() >= headingLevel) {
                        HeadingInfo popped = headingStack.remove(headingStack.size() - 1);
                        initialMetadata.remove(popped.getMetadataKey());
                    }
                    String metaKey = "heading" + headingLevel;
                    headingStack.add(new HeadingInfo(headingLevel, metaKey, text));
                    initialMetadata.put(metaKey, text);
                    initialMetadata.put("headingLevel", headingLevel);
                    initialMetadata.put("chunkId", UUID.randomUUID().toString());

                    if (!currentContent.isEmpty()) {
                        paragraphsWithMeta.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
                        currentContent.clear();
                    }
                    if (!stripHeadings) currentContent.add(text);
                } else {
                    currentContent.add(text);
                }
                currentMetadata = new HashMap<>(initialMetadata);
            }
            if (!currentContent.isEmpty()) {
                paragraphsWithMeta.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
            }
        }
        return processSegments(paragraphsWithMeta);
    }

    /**
     * 处理旧版 {@code .doc} 格式（OLE2/HWPF）。
     *
     * <p><strong>已知限制</strong>：{@code HWPFDocument.Paragraph} API 无法直接读取 Word 内置标题样式名
     * （如 "Heading 1"），因此标题识别完全依赖 {@link #detectHeadingByTextPattern(String)} 的文本模式匹配。
     * 不符合内置中文编号规范（如纯英文标题、纯数字编号标题）的 {@code .doc} 文档，所有段落将视为正文，
     * 输出为单一 chunk。若需精确的标题层级切分，建议将文档另存为 {@code .docx} 格式。
     */
    private List<DocumentWithMetadata> splitDocDocument(InputStream inputStream,
                                                         Map<String, Object> baseMetadata) throws Exception {
        List<ParagraphWithMetadata> paragraphsWithMeta = new ArrayList<>();
        try (HWPFDocument doc = new HWPFDocument(inputStream)) {
            Range range = doc.getRange();

            List<String> currentContent = new ArrayList<>();
            Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
            List<HeadingInfo> headingStack = new ArrayList<>();
            // 初始化时预置 chunkId，确保首个标题之前的正文段落（前言/封面）也有唯一标识
            Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);
            initialMetadata.put("chunkId", UUID.randomUUID().toString());

            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph paragraph = range.getParagraph(i);
                String text = paragraph.text().trim();
                if (text.isEmpty()) continue;

                Integer headingLevel = detectHeadingByTextPattern(text);
                if (headingLevel != null && headingLevelsToSplitOn.contains(headingLevel)) {
                    while (!headingStack.isEmpty()
                            && headingStack.get(headingStack.size() - 1).getLevel() >= headingLevel) {
                        HeadingInfo popped = headingStack.remove(headingStack.size() - 1);
                        initialMetadata.remove(popped.getMetadataKey());
                    }
                    String metaKey = "heading" + headingLevel;
                    headingStack.add(new HeadingInfo(headingLevel, metaKey, text));
                    initialMetadata.put(metaKey, text);
                    initialMetadata.put("headingLevel", headingLevel);
                    initialMetadata.put("chunkId", UUID.randomUUID().toString());

                    if (!currentContent.isEmpty()) {
                        paragraphsWithMeta.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
                        currentContent.clear();
                    }
                    if (!stripHeadings) currentContent.add(text);
                } else {
                    currentContent.add(text);
                }
                currentMetadata = new HashMap<>(initialMetadata);
            }
            if (!currentContent.isEmpty()) {
                paragraphsWithMeta.add(new ParagraphWithMetadata(String.join("\n", currentContent), currentMetadata));
            }
        }
        return processSegments(paragraphsWithMeta);
    }

    private List<DocumentWithMetadata> processSegments(List<ParagraphWithMetadata> paragraphsWithMeta) {
        List<DocumentWithMetadata> segments;
        if (!returnEachParagraph) {
            segments = aggregateParagraphsToChunks(paragraphsWithMeta);
        } else {
            segments = paragraphsWithMeta.stream()
                    .map(p -> new DocumentWithMetadata(p.getContent(), p.getMetadata()))
                    .collect(Collectors.toList());
        }
        return segments;
    }

    private List<DocumentWithMetadata> aggregateParagraphsToChunks(List<ParagraphWithMetadata> paragraphs) {
        List<ParagraphWithMetadata> aggregated = new ArrayList<>();
        for (ParagraphWithMetadata p : paragraphs) {
            if (!aggregated.isEmpty()
                    && aggregated.get(aggregated.size() - 1).getMetadata().equals(p.getMetadata())) {
                ParagraphWithMetadata last = aggregated.get(aggregated.size() - 1);
                last.setContent(last.getContent() + "\n" + p.getContent());
            } else {
                aggregated.add(p);
            }
        }
        if (chunkSize > 0) {
            aggregated = applySizeBasedSplitting(aggregated);
        }
        if (parentChildModel) {
            for (int i = 0; i < aggregated.size(); i++) {
                Map<String, Object> meta = aggregated.get(i).getMetadata();
                Integer level = (Integer) meta.get("headingLevel");
                if (level == null || level == 1) continue;
                for (int j = i - 1; j >= 0; j--) {
                    Map<String, Object> prevMeta = aggregated.get(j).getMetadata();
                    Integer prevLevel = (Integer) prevMeta.get("headingLevel");
                    if (prevLevel != null && prevLevel < level) {
                        meta.put("parentChunkId", prevMeta.get("chunkId"));
                        break;
                    }
                }
            }
        }
        return aggregated.stream()
                .map(c -> new DocumentWithMetadata(c.getContent(), c.getMetadata()))
                .collect(Collectors.toList());
    }

    private List<ParagraphWithMetadata> applySizeBasedSplitting(List<ParagraphWithMetadata> chunks) {
        List<ParagraphWithMetadata> result = new ArrayList<>();
        for (ParagraphWithMetadata chunk : chunks) {
            String content = chunk.getContent();
            if (content.length() <= chunkSize) {
                result.add(chunk);
                continue;
            }
            // 按 chunkSize/overlap 滑窗分割
            int start = 0;
            int idx = 0;
            while (start < content.length()) {
                int end = Math.min(start + chunkSize, content.length());
                Map<String, Object> segMeta = new HashMap<>(chunk.getMetadata());
                String origChunkId = (String) chunk.getMetadata().get("chunkId");
                segMeta.put("chunkId", origChunkId + "_" + idx);
                segMeta.put("segmentIndex", idx);
                segMeta.put("isSplit", true);
                result.add(new ParagraphWithMetadata(content.substring(start, end), segMeta));
                // 已到末尾，不再继续（否则下一轮 start = end - overlap 会产生与最后分片重叠的冗余分片）
                if (end == content.length()) break;
                start = end - overlap;
                idx++;
            }
        }
        return result;
    }

    private List<DocumentWithMetadata> splitPlainText(String text, Map<String, Object> baseMetadata) {
        baseMetadata.remove("wordInputStream");
        Map<String, Object> meta = new HashMap<>(baseMetadata);
        meta.put("chunkId", UUID.randomUUID().toString());
        return Collections.singletonList(new DocumentWithMetadata(text, meta));
    }

    // ─────────────────────────────────────────────────────────────
    // Heading detection helpers
    // ─────────────────────────────────────────────────────────────

    private Integer extractHeadingLevelFromDocx(String style, XWPFParagraph paragraph) {
        Integer level = null;
        if (style != null && (style.matches("(?i)heading\\s*\\d") || style.matches("标题\\s*\\d"))) {
            try {
                level = Integer.parseInt(style.replaceAll("(?i)heading|标题|\\s", ""));
            } catch (NumberFormatException ignored) {
            }
        }
        if (level == null) {
            String text = paragraph.getText();
            if (text != null && !text.isEmpty()) {
                level = detectHeadingByTextPattern(text.trim());
            }
        }
        return level;
    }

    private Integer detectHeadingByTextPattern(String text) {
        if (text == null || text.isEmpty()) return null;
        if (text.matches("^第[一二三四五六七八九十百]+章.*")) return 1;
        if (text.matches("^第[一二三四五六七八九十百]+部分.*")) return 1;
        if (text.matches("^第[一二三四五六七八九十百]+条.*")) return 1;
        if (text.matches("^[（(][一二三四五六七八九十百]+[)）].*")) return 3;
        if (text.matches("^[一二三四五六七八九十百]+、.*")) return 2;
        if (text.matches("^\\d+\\.\\s*[^0-9].*")) return 3;
        if (text.matches("^[（(]\\d+[)）].*")) return 3;
        if (text.matches("^\\d+\\.\\d+.*")) return 4;
        if (text.length() <= 20 && text.matches("^[一-龥]+$")) {
            if (text.contains("总则") || text.contains("附则") || text.contains("说明")
                    || text.contains("须知") || text.contains("规定") || text.contains("制度")
                    || text.contains("办法") || text.contains("条例")) {
                return 1;
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Internal data classes
    // ─────────────────────────────────────────────────────────────

    private static class ParagraphWithMetadata {
        private String content;
        private final Map<String, Object> metadata;

        ParagraphWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        String getContent() { return content; }
        void setContent(String content) { this.content = content; }
        Map<String, Object> getMetadata() { return metadata; }
    }

    private static class HeadingInfo {
        private final int level;
        private final String metadataKey;
        private final String text;

        HeadingInfo(int level, String metadataKey, String text) {
            this.level = level;
            this.metadataKey = metadataKey;
            this.text = text;
        }

        int getLevel() { return level; }
        String getMetadataKey() { return metadataKey; }
        String getText() { return text; }
    }

    private static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        String getContent() { return content; }
        Map<String, Object> getMetadata() { return metadata; }
    }

}

