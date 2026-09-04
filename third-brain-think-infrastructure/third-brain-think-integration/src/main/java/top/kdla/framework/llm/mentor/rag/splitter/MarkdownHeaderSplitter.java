package top.kdla.framework.llm.mentor.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Markdown 标题分割器（支持父子分段）
 * 参考 know-engine 的 MarkdownHeaderParentTextSplitter 优化实现
 * 
 * 特性：
 * 1. 基于 Markdown 标题层级分割（# ## ### 等）
 * 2. 保留标题层级作为元数据
 * 3. 支持父子分段机制（完整分片 + 拆分后的子分片）
 * 4. 处理代码块、空行等特殊情况
 */
@Slf4j
public class MarkdownHeaderSplitter implements DocumentSplitter {

    private static final Map<String, String> DEFAULT_HEADERS = new LinkedHashMap<>();
    static {
        DEFAULT_HEADERS.put("#", "h1");
        DEFAULT_HEADERS.put("##", "h2");
        DEFAULT_HEADERS.put("###", "h3");
        DEFAULT_HEADERS.put("####", "h4");
        DEFAULT_HEADERS.put("#####", "h5");
        DEFAULT_HEADERS.put("######", "h6");
    }

    private final int chunkSize;
    private final int overlap;
    private final List<Map.Entry<String, String>> headersToSplitOn;
    private final boolean enableParentChild;

    public MarkdownHeaderSplitter(int chunkSize, int overlap) {
        this(chunkSize, overlap, true);
    }

    public MarkdownHeaderSplitter(int chunkSize, int overlap, boolean enableParentChild) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.enableParentChild = enableParentChild;
        // 按标题标记长度倒序排列，优先匹配更长的标记
        this.headersToSplitOn = DEFAULT_HEADERS.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .collect(Collectors.toList());
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.info("Starting Markdown header splitting, chunkSize: {}, overlap: {}", chunkSize, overlap);
        
        String text = document.text();
        Map<String, Object> baseMetadata = document.metadata().toMap();
        
        // 移除空行
        String filteredText = Arrays.stream(text.split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));
        
        // 执行分割
        List<DocumentWithMetadata> segments = splitWithMetadata(filteredText, baseMetadata);
        
        // 转换为 TextSegment
        List<TextSegment> result = new ArrayList<>();
        for (DocumentWithMetadata segment : segments) {
            Metadata metadata = Metadata.from(segment.getMetadata());
            result.add(new TextSegment(segment.getContent(), metadata));
        }
        
        log.info("Split completed: {} segments generated", result.size());
        return result;
    }

    /**
     * 核心分割逻辑，保留元数据
     */
    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        List<String> lines = Arrays.asList(text.split("\n"));
        List<Line> linesWithMetadata = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        List<Header> headerStack = new ArrayList<>();
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        boolean inCodeBlock = false;
        String openingFence = "";

        for (String line : lines) {
            String strippedLine = line.trim();

            // 处理代码块
            if (!inCodeBlock) {
                if (strippedLine.startsWith("```") || strippedLine.startsWith("~~~")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = strippedLine.startsWith("```") ? "```" : "~~~";
                }
            } else {
                if (strippedLine.startsWith(openingFence)) {
                    inCodeBlock = false;
                    openingFence = "";
                }
            }

            // 代码块内直接添加
            if (inCodeBlock) {
                currentContent.add(strippedLine);
                continue;
            }

            // 检测标题
            boolean isHeader = false;
            for (Map.Entry<String, String> header : headersToSplitOn) {
                String sep = header.getKey();
                String name = header.getValue();

                if (strippedLine.startsWith(sep) && 
                    (strippedLine.length() == sep.length() || strippedLine.charAt(sep.length()) == ' ')) {
                    
                    if (name != null) {
                        int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();
                        
                        // 维护标题栈
                        while (!headerStack.isEmpty() && 
                               headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                            Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                            initialMetadata.remove(poppedHeader.getName());
                        }

                        // 添加新标题
                        Header headerType = new Header(currentHeaderLevel, name, 
                                                       strippedLine.substring(sep.length()).trim());
                        headerStack.add(headerType);
                        initialMetadata.put(name, headerType.getData());
                        initialMetadata.put("headerLevel", currentHeaderLevel);
                        initialMetadata.put("chunkId", UUID.randomUUID().toString());
                    }

                    // 保存之前的内容
                    if (!currentContent.isEmpty()) {
                        linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                        currentContent.clear();
                    }

                    isHeader = true;
                    break;
                }
            }

            // 处理非标题行
            if (!isHeader) {
                if (!strippedLine.isEmpty()) {
                    currentContent.add(strippedLine);
                } else if (!currentContent.isEmpty()) {
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }
            }

            currentMetadata = new HashMap<>(initialMetadata);
        }

        // 处理最后的内容
        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        // 聚合相同元数据的行
        List<DocumentWithMetadata> segments = aggregateLinesToChunks(linesWithMetadata);

        // 如果启用父子分段且设置了 chunkSize，进行二次切割
        if (enableParentChild && chunkSize > 0) {
            segments = splitByChunkSize(segments);
        }

        return segments;
    }

    /**
     * 聚合具有相同元数据的行
     */
    private List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        
        for (Line line : lines) {
            if (!aggregatedChunks.isEmpty() && 
                aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())) {
                // 元数据相同，合并
                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "\n\n" + line.getContent());
            } else {
                aggregatedChunks.add(line);
            }
        }

        return aggregatedChunks.stream()
                .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 对超出 chunkSize 的分段进行二次切割（父子分段）
     */
    private List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        List<DocumentWithMetadata> result = new ArrayList<>();
        
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();
            
            if (content.length() <= chunkSize) {
                // 未超出，保持不变
                result.add(segment);
            } else {
                // 超出，创建父分段（跳过向量化）
                Map<String, Object> parentMetadata = new HashMap<>(segment.getMetadata());
                String parentChunkId = UUID.randomUUID().toString();
                parentMetadata.put("chunkId", parentChunkId);
                parentMetadata.put("skipEmbedding", true);
                parentMetadata.put("isParent", true);
                result.add(new DocumentWithMetadata(content, parentMetadata));

                // 生成子分段
                int start = 0;
                int childIndex = 0;
                while (start < content.length()) {
                    int end = Math.min(start + chunkSize, content.length());
                    String subContent = content.substring(start, end);

                    Map<String, Object> childMetadata = new HashMap<>(segment.getMetadata());
                    childMetadata.put("chunkId", UUID.randomUUID().toString());
                    childMetadata.put("parentChunkId", parentChunkId);
                    childMetadata.put("childIndex", childIndex++);
                    childMetadata.put("isParent", false);

                    result.add(new DocumentWithMetadata(subContent, childMetadata));

                    if (end == content.length()) break;
                    start = end - Math.min(overlap, end);
                }
            }
        }
        
        return result;
    }

    // ==================== 内部类 ====================

    /**
     * 带元数据的文本行
     */
    private static class Line {
        private String content;
        private Map<String, Object> metadata;

        public Line(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Map<String, Object> getMetadata() { return metadata; }
    }

    /**
     * Markdown 标题
     */
    private static class Header {
        private int level;
        private String name;
        private String data;

        public Header(int level, String name, String data) {
            this.level = level;
            this.name = name;
            this.data = data;
        }

        public int getLevel() { return level; }
        public String getName() { return name; }
        public String getData() { return data; }
    }

    /**
     * 带元数据的文档片段
     */
    private static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        public DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
}
