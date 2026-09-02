package cn.hollis.llm.mentor.ragdemo.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分割器工厂
 * 支持多种分割策略：
 * 1. TITLE - 基于标题层级（MarkdownHeaderSplitter）
 * 2. BROTHER - 兄弟分片（MarkdownHeaderBrotherTextSplitter，支持父子/兄弟关系）
 * 3. RECURSIVE - 递归分割（LangChain4j 内置）
 * 4. PARAGRAPH - 段落分割
 * 5. OVERLAP - 重叠段落分割
 * 6. HYBRID - 混合策略（先按标题，再对超长段落二次切割）
 * 7. COMBINED - 组合策略（标题+递归+重叠）
 * 8. WORD - Word 文档标题层级切分（需 rag.word-splitter.enable=true）
 */
@Slf4j
public class DocumentSplitterFactory {

    /**
     * Word 分割器开关，由 {@link WordSplitterInitializer} 在 Spring 启动时注入。
     * 默认 false，仅当 rag.word-splitter.enable=true 时为 true。
     */
    static boolean wordSplitterEnabled = false;

    /**
     * 根据策略名称创建分割器（自动包装ImageAwareSplitter）
     */
    public static DocumentSplitter create(String strategy, int chunkSize, int overlap) {
        log.info("Creating splitter with strategy: {}, chunkSize: {}, overlap: {}", 
                 strategy, chunkSize, overlap);
        
        DocumentSplitter baseSplitter = switch (strategy.toUpperCase()) {
            case "TITLE" -> new MarkdownHeaderSplitter(chunkSize, overlap);
            case "BROTHER" -> new MarkdownHeaderBrotherTextSplitter(chunkSize, overlap);
            case "RECURSIVE" -> DocumentSplitters.recursive(chunkSize, overlap);
            case "PARAGRAPH" -> new DocumentByParagraphSplitter(chunkSize, overlap);
            case "OVERLAP" -> new OverlapParagraphSplitter(chunkSize, overlap);
            case "HYBRID" -> new HybridSplitter(chunkSize, overlap);
            case "COMBINED" -> new CombinedSplitter(chunkSize, overlap);
            case "EXCEL" -> new ExcelSplitter(chunkSize);
            case "WORD" -> {
                if (!wordSplitterEnabled) {
                    throw new IllegalArgumentException("Unknown strategy: WORD (rag.word-splitter.enable is false)");
                }
                yield new WordHeaderSplitter(chunkSize, overlap);
            }
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy);
        };
        
        // 包装为图片感知的分割器
        return new ImageAwareSplitter(baseSplitter);
    }

    /**
     * 根据多个策略组合创建分割器链
     */
    public static List<TextSegment> splitWithStrategies(Document document, String[] strategies, 
                                                         int chunkSize, int overlap) {
        log.info("Splitting document with combined strategies: {}", String.join(",", strategies));
        
        List<TextSegment> segments = new ArrayList<>();
        segments.add(TextSegment.from(document.text(), document.metadata()));
        
        // 依次应用每个策略
        for (String strategy : strategies) {
            List<TextSegment> newSegments = new ArrayList<>();
            DocumentSplitter splitter = create(strategy, chunkSize, overlap);
            
            for (TextSegment segment : segments) {
                dev.langchain4j.data.document.Document tempDoc = 
                    dev.langchain4j.data.document.Document.from(segment.text(), segment.metadata());
                newSegments.addAll(splitter.split(tempDoc));
            }
            
            segments = newSegments;
            log.info("After applying {}: {} segments", strategy, segments.size());
        }
        
        return segments;
    }

    /**
     * 混合分割器 - 结合多种策略
     * 先用标题分割，再对超长段落进行递归分割
     */
    private static class HybridSplitter implements DocumentSplitter {
        private final int chunkSize;
        private final int overlap;

        public HybridSplitter(int chunkSize, int overlap) {
            this.chunkSize = chunkSize;
            this.overlap = overlap;
        }

        @Override
        public java.util.List<dev.langchain4j.data.segment.TextSegment> split(Document document) {
            log.info("Hybrid splitting: first by headers, then by size");
            
            // 第一步：按标题分割
            MarkdownHeaderSplitter headerSplitter = new MarkdownHeaderSplitter(chunkSize, overlap, false);
            var headerSegments = headerSplitter.split(document);
            
            log.info("After header split: {} segments", headerSegments.size());
            
            // 第二步：对超长段落再次分割
            java.util.List<dev.langchain4j.data.segment.TextSegment> finalSegments = new java.util.ArrayList<>();
            for (var segment : headerSegments) {
                if (segment.text().length() > chunkSize * 1.5) {
                    // 超长，使用递归分割器再次分割
                    var recursiveSplitter = DocumentSplitters.recursive(chunkSize, overlap);
                    var subSegments = recursiveSplitter.split(
                        dev.langchain4j.data.document.Document.from(segment.text(), segment.metadata())
                    );
                    finalSegments.addAll(subSegments);
                    log.debug("Sub-split long segment: {} -> {} sub-segments", 
                             segment.text().length(), subSegments.size());
                } else {
                    finalSegments.add(segment);
                }
            }
            
            log.info("Final segments after hybrid split: {}", finalSegments.size());
            return finalSegments;
        }
    }

    /**
     * 组合分割器 - 标题+递归+重叠三重策略
     */
    private static class CombinedSplitter implements DocumentSplitter {
        private final int chunkSize;
        private final int overlap;

        public CombinedSplitter(int chunkSize, int overlap) {
            this.chunkSize = chunkSize;
            this.overlap = overlap;
        }

        @Override
        public List<TextSegment> split(Document document) {
            log.info("Combined splitting: title -> recursive -> overlap");
            
            // 第一步：按标题分割
            MarkdownHeaderSplitter headerSplitter = new MarkdownHeaderSplitter(chunkSize, overlap);
            var titleSegments = headerSplitter.split(document);
            log.info("After title split: {} segments", titleSegments.size());
            
            // 第二步：对每个标题段进行递归分割
            List<TextSegment> recursiveSegments = new ArrayList<>();
            for (var segment : titleSegments) {
                if (segment.text().length() > chunkSize) {
                    var recursiveSplitter = DocumentSplitters.recursive(chunkSize, overlap);
                    var subSegments = recursiveSplitter.split(
                        dev.langchain4j.data.document.Document.from(segment.text(), segment.metadata())
                    );
                    recursiveSegments.addAll(subSegments);
                } else {
                    recursiveSegments.add(segment);
                }
            }
            log.info("After recursive split: {} segments", recursiveSegments.size());
            
            // 第三步：对相邻段落添加重叠
            OverlapParagraphSplitter overlapSplitter = new OverlapParagraphSplitter(chunkSize, overlap);
            var finalSegments = new ArrayList<TextSegment>();
            
            for (int i = 0; i < recursiveSegments.size(); i++) {
                TextSegment current = recursiveSegments.get(i);
                
                if (i > 0 && overlap > 0) {
                    // 与前一段落添加重叠
                    TextSegment previous = recursiveSegments.get(i - 1);
                    String overlapText = getOverlapText(previous.text(), overlap);
                    if (!overlapText.isEmpty()) {
                        String combinedText = overlapText + "\n" + current.text();
                        finalSegments.add(TextSegment.from(combinedText, current.metadata()));
                        continue;
                    }
                }
                
                finalSegments.add(current);
            }
            
            log.info("Final segments after combined split: {}", finalSegments.size());
            return finalSegments;
        }
        
        private String getOverlapText(String text, int overlapChars) {
            if (text.length() <= overlapChars) {
                return text;
            }
            // 取最后overlapChars个字符作为重叠部分
            return text.substring(text.length() - overlapChars);
        }
    }
}
