package top.kdla.framework.llm.mentor.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Markdown 兄弟分片分割器
 * <p>
 * 基于标题层级进行文档分段，支持：
 * <ul>
 *   <li><b>父子分片关系</b> - 子分段关联父分段，检索时补充完整上下文</li>
 *   <li><b>兄弟分片关系</b> - 同组子分段共享 brotherChunkId，检索时拼接相关内容</li>
 *   <li><b>智能二次切割</b> - 超出 chunkSize 的分段自动切割，保留 overlap 重叠</li>
 *   <li><b>代码块保护</b> - 代码块内的内容不作为标题处理</li>
 * </ul>
 * <p>
 * <b>元数据字段：</b>
 * <ul>
 *   <li>{@code chunkId} - 分段唯一ID</li>
 *   <li>{@code parentChunkId} - 父分段ID（如果有）</li>
 *   <li>{@code brotherChunkId} - 兄弟分组ID（同一组的分段共享）</li>
 *   <li>{@code brotherChunkIndex} - 在兄弟组中的序号（从1开始）</li>
 *   <li>{@code brotherChunkTotal} - 兄弟组总分段数</li>
 *   <li>{@code headerLevel} - 标题级别（1-6）</li>
 *   <li>{@code skipEmbedding} - 是否跳过向量生成（父分段为true）</li>
 * </ul>
 *
 * @author Hollis (adapted from know-engine)
 */
@Slf4j
public class MarkdownHeaderBrotherTextSplitter implements DocumentSplitter {

    private static final Map<String, String> DEFAULT_HEADERS_TO_SPLIT = new HashMap<>();

    static {
        DEFAULT_HEADERS_TO_SPLIT.put("#", "一级标题");
        DEFAULT_HEADERS_TO_SPLIT.put("##", "二级标题");
        DEFAULT_HEADERS_TO_SPLIT.put("###", "三级标题");
        DEFAULT_HEADERS_TO_SPLIT.put("####", "四级标题");
        DEFAULT_HEADERS_TO_SPLIT.put("#####", "五级标题");
        DEFAULT_HEADERS_TO_SPLIT.put("######", "六级标题");
    }

    // 元数据键常量
    private static final String CHUNK_ID = "chunkId";
    private static final String PARENT_CHUNK_ID = "parentChunkId";
    private static final String BROTHER_CHUNK_ID = "brotherChunkId";
    private static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";
    private static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";
    private static final String HEADER_LEVEL = "headerLevel";
    private static final String SKIP_EMBEDDING = "skipEmbedding";

    /**
     * 需要分割的标题列表，按标题标记长度倒序排列
     */
    private List<Map.Entry<String, String>> headersToSplitOn;

    /**
     * 是否按行返回结果
     */
    private boolean returnEachLine;

    /**
     * 是否剥离标题行本身
     */
    private boolean stripHeaders;

    /**
     * 是否启用父子分段模式
     */
    private boolean parentChildModel;

    /**
     * 每个分片的最大字符数，0表示不限制
     */
    private int chunkSize;

    /**
     * 相邻分片之间的重叠字符数
     */
    private int overlap;

    /**
     * 构造函数（默认配置）
     *
     * @param chunkSize 每个分片的最大字符数
     * @param overlap   相邻分片之间的重叠字符数
     */
    public MarkdownHeaderBrotherTextSplitter(int chunkSize, int overlap) {
        this(DEFAULT_HEADERS_TO_SPLIT, true, false, true, chunkSize, overlap);
    }

    /**
     * 完整构造函数
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param parentChildModel 是否启用父子分段模式，启用后会在元数据中添加parentChunkId
     * @param chunkSize        每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap          相邻分片之间的重叠字符数
     */
    public MarkdownHeaderBrotherTextSplitter(Map<String, String> headersToSplitOn, 
                                             boolean returnEachLine, 
                                             boolean stripHeaders, 
                                             boolean parentChildModel, 
                                             int chunkSize, 
                                             int overlap) {
        // 按标题标记长度倒序排列，确保优先匹配更长的标记（如"###"优先于"##"）
        this.headersToSplitOn = headersToSplitOn.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .collect(Collectors.toList());
        this.returnEachLine = returnEachLine;
        this.stripHeaders = stripHeaders;
        this.parentChildModel = parentChildModel;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        
        log.info("MarkdownHeaderBrotherTextSplitter initialized: chunkSize={}, overlap={}, parentChildModel={}", 
                 chunkSize, overlap, parentChildModel);
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.info("开始解析Markdown文档...");
        
        // 移除文档中所有空行
        String text = Arrays.stream(document.text().split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(text, document.metadata().toMap());
        
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }

        log.info("Markdown文档解析完成，生成分段数: {}", result.size());
        return result;
    }

    /**
     * 核心分割逻辑，保留元数据
     *
     * @param text         待分割的文本
     * @param baseMetadata 基础元数据，会被传递到每个分段中
     * @return 带有元数据的文档片段列表
     */
    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        List<String> lines = Arrays.asList(text.split("\n"));
        List<Line> linesWithMetadata = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        List<Header> headerStack = new ArrayList<>();  // 标题栈，用于追踪当前的标题层级结构
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        boolean inCodeBlock = false;  // 是否在代码块中
        String openingFence = "";     // 代码块的开始标记

        for (String line : lines) {
            String strippedLine = line.trim();

            // 处理代码块标记，代码块内的内容不作为标题处理
            if (!inCodeBlock) {
                if (strippedLine.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "```";
                } else if (strippedLine.startsWith("~~~")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "~~~";
                }
            } else {
                if (strippedLine.startsWith(openingFence)) {
                    inCodeBlock = false;
                    openingFence = "";
                }
            }

            // 代码块内的内容直接添加，不做标题检测
            if (inCodeBlock) {
                currentContent.add(strippedLine);
                continue;
            }

            // 检测并处理标题行
            interrupted:
            {
                for (Map.Entry<String, String> header : headersToSplitOn) {
                    String sep = header.getKey();    // 标题标记，如"#"、"##"
                    String name = header.getValue(); // 元数据中的键名

                    // 判断是否为有效的标题行
                    if (strippedLine.startsWith(sep) && 
                        (strippedLine.length() == sep.length() || strippedLine.charAt(sep.length()) == ' ')) {
                        
                        if (name != null) {
                            // 计算当前标题级别（统计#的个数）
                            int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();

                            // 维护标题栈：移除所有级别大于等于当前级别的标题
                            while (!headerStack.isEmpty() && 
                                   headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                                Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                                initialMetadata.remove(poppedHeader.getName());
                            }

                            // 将当前标题加入栈，并更新元数据
                            Header headerType = new Header(currentHeaderLevel, name, 
                                                          strippedLine.substring(sep.length()).trim());
                            headerStack.add(headerType);
                            initialMetadata.put(name, headerType.getData());
                            initialMetadata.put(HEADER_LEVEL, currentHeaderLevel);
                            
                            // 为每个分段生成唯一ID
                            String currentChunkId = UUID.randomUUID().toString();
                            initialMetadata.put(CHUNK_ID, currentChunkId);
                        }

                        // 遇到新标题时，保存之前累积的内容
                        if (!currentContent.isEmpty()) {
                            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                            currentContent.clear();
                        }

                        // 根据stripHeaders配置决定是否保留标题行
                        if (!stripHeaders) {
                            currentContent.add(strippedLine);
                        }

                        break interrupted;
                    }
                }

                // 处理非标题行
                if (!strippedLine.isEmpty()) {
                    currentContent.add(strippedLine);
                } else if (!currentContent.isEmpty()) {
                    // 遇到空行时，保存当前累积的内容
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }
            }

            // 更新当前元数据为最新的标题信息
            currentMetadata = new HashMap<>(initialMetadata);
        }

        // 处理最后累积的内容
        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        // 根据配置决定返回方式
        List<DocumentWithMetadata> segments;
        if (!returnEachLine) {
            // 聚合模式：将相同元数据的行合并
            segments = aggregateLinesToChunks(linesWithMetadata);
        } else {
            // 逐行模式：保持每行独立
            segments = linesWithMetadata.stream()
                    .map(line -> new DocumentWithMetadata(line.getContent(), line.getMetadata()))
                    .collect(Collectors.toList());
        }

        // 如果设置了 chunkSize，对超出大小的分片进行二次切割
        if (chunkSize > 0) {
            segments = splitByChunkSize(segments);
        }

        return segments;
    }

    /**
     * 聚合行为分块
     * 将具有相同元数据的行合并为一个分块，并处理父子关系
     *
     * @param lines 待聚合的行列表
     * @return 聚合后的文档片段列表
     */
    private List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        
        for (Line line : lines) {
            // 情况1：元数据相同，直接合并到上一个分块
            if (!aggregatedChunks.isEmpty() && 
                aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())) {
                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况2：元数据不同但上一行以标题结尾且未剥离标题，则也合并
            else if (!aggregatedChunks.isEmpty() && 
                     !aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata()) &&
                     aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().size() < line.getMetadata().size() &&
                     aggregatedChunks.get(aggregatedChunks.size() - 1).getContent()
                         .split("\n")[aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n").length - 1]
                         .startsWith("#") && 
                     !stripHeaders) {

                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况3：创建新分块
            else {
                aggregatedChunks.add(line);
            }
        }

        // 处理父子分段关系
        if (parentChildModel) {
            try {
                // 遍历所有分块，为非顶级标题建立父子关系
                for (int i = 0; i < aggregatedChunks.size(); i++) {
                    Map<String, Object> currentMetaData = aggregatedChunks.get(i).getMetadata();
                    Integer headerLevel = (Integer) currentMetaData.get(HEADER_LEVEL);
                    
                    // 顶级标题（level=1）或无标题的分块跳过
                    if (headerLevel == null || headerLevel == 1) {
                        continue;
                    }

                    // 向前查找第一个级别更低的标题作为父节点
                    if (headerLevel > 1) {
                        for (int j = i - 1; j >= 0; j--) {
                            Map<String, Object> lastMetaData = aggregatedChunks.get(j).getMetadata();
                            Integer lastHeaderLevel = (Integer) lastMetaData.get(HEADER_LEVEL);
                            
                            if (lastHeaderLevel != null && lastHeaderLevel < headerLevel) {
                                // 将父节点的chunkId设置为当前节点的parentChunkId
                                currentMetaData.put(PARENT_CHUNK_ID, lastMetaData.get(CHUNK_ID));
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("父子模式转换失败: {}", e.getMessage());
            }
        }

        return aggregatedChunks.stream()
                .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 对超出 chunkSize 的分片进行二次切割
     * <p>
     * 切割规则：
     * - 未超出 chunkSize 的分片保持不变
     * - 超出 chunkSize 的分片按字符数切割，相邻分片之间保留 overlap 个字符的重叠
     * - 切割出的同组分片之间共享同一个 brotherChunkId，方便检索时拼接
     *
     * @param segments 原始分片列表
     * @return 切割后的分片列表
     */
    private List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        List<DocumentWithMetadata> result = new ArrayList<>();
        
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();
            
            if (content.length() <= chunkSize) {
                // 未超出 chunkSize，保持原分片不变
                result.add(segment);
            } else {
                // 超出 chunkSize，需要二次切割
                // 生成共同的 brotherChunkId，赋予同组所有分片
                String brotherChunkId = UUID.randomUUID().toString();
                List<DocumentWithMetadata> subChunks = new ArrayList<>();

                int start = 0;
                while (start < content.length()) {
                    int end = Math.min(start + chunkSize, content.length());
                    String subContent = content.substring(start, end);

                    // 复制元数据并进行更新
                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    subMetadata.put(CHUNK_ID, UUID.randomUUID().toString());
                    subMetadata.put(BROTHER_CHUNK_ID, brotherChunkId);

                    subChunks.add(new DocumentWithMetadata(subContent, subMetadata));

                    if (end == content.length()) {
                        break;
                    }
                    // 下一片的起始位置 = 当前片的结束位置 - overlap
                    start = end - Math.min(overlap, end);
                }

                // 回填 brotherChunkIndex 和 brotherChunkTotal，方便后续按序拼接
                int total = subChunks.size();
                for (int i = 0; i < total; i++) {
                    subChunks.get(i).getMetadata().put(BROTHER_CHUNK_INDEX, i + 1);
                    subChunks.get(i).getMetadata().put(BROTHER_CHUNK_TOTAL, total);
                }

                result.addAll(subChunks);
            }
        }
        
        log.debug("二次切割完成，原始分段数: {}, 切割后分段数: {}", segments.size(), result.size());
        return result;
    }

    // ==================== 内部类 ====================

    /**
     * 内部类：表示带有元数据的文本行
     */
    public static class Line {
        private String content;
        private Map<String, Object> metadata;

        public Line(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * 内部类：表示Markdown标题
     */
    public static class Header {
        private int level;      // 标题级别（1-6）
        private String name;    // 元数据中的键名
        private String data;    // 标题文本内容（不含#标记）

        public Header(int level, String name, String data) {
            this.level = level;
            this.name = name;
            this.data = data;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

    /**
     * 内部类：携带元数据的文档片段
     */
    private static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        public DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        public String getContent() {
            return content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
