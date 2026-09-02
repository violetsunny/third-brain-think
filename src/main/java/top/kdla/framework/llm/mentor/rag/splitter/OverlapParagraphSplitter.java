package top.kdla.framework.llm.mentor.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 重叠段落分割器
 * 在段落之间添加重叠内容,保持上下文连贯性
 */
@Slf4j
public class OverlapParagraphSplitter implements DocumentSplitter {
    
    private final int chunkSize;
    private final int overlap;

    public OverlapParagraphSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<TextSegment> split(Document document) {
        log.info("Overlap paragraph splitting with chunkSize={}, overlap={}", chunkSize, overlap);
        
        String text = document.text();
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        
        // 按段落分割
        String[] paragraphs = text.split("\\n\\n+");
        List<TextSegment> segments = new ArrayList<>();
        
        StringBuilder currentChunk = new StringBuilder();
        
        for (String paragraph : paragraphs) {
            String trimmedParagraph = paragraph.trim();
            if (trimmedParagraph.isEmpty()) {
                continue;
            }
            
            // 如果当前块加上新段落超过chunkSize,保存当前块并开始新块
            if (currentChunk.length() + trimmedParagraph.length() > chunkSize && currentChunk.length() > 0) {
                segments.add(TextSegment.from(currentChunk.toString(), document.metadata()));
                
                // 保留重叠部分
                String overlapText = getOverlapContent(currentChunk.toString(), overlap);
                currentChunk = new StringBuilder(overlapText);
                if (!overlapText.isEmpty()) {
                    currentChunk.append("\n");
                }
            }
            
            // 添加段落到当前块
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(trimmedParagraph);
        }
        
        // 添加最后一个块
        if (currentChunk.length() > 0) {
            segments.add(TextSegment.from(currentChunk.toString(), document.metadata()));
        }
        
        log.info("Overlap split result: {} segments", segments.size());
        return segments;
    }
    
    /**
     * 获取重叠内容
     * 从文本末尾提取指定长度的内容作为重叠部分
     */
    private String getOverlapContent(String text, int overlapLength) {
        if (overlapLength <= 0 || text.length() <= overlapLength) {
            return text;
        }
        
        // 尝试在句子边界切割
        int startIdx = text.length() - overlapLength;
        
        // 查找最近的句子结束符
        int sentenceEnd = Math.max(
            text.lastIndexOf(".", startIdx + 50),
            Math.max(
                text.lastIndexOf("!", startIdx + 50),
                text.lastIndexOf("?", startIdx + 50)
            )
        );
        
        if (sentenceEnd > startIdx && sentenceEnd < text.length()) {
            return text.substring(sentenceEnd + 1).trim();
        }
        
        // 如果没有找到句子边界,直接截取
        return text.substring(startIdx).trim();
    }
}
