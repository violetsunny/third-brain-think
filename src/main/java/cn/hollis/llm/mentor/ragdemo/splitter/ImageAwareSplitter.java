package cn.hollis.llm.mentor.ragdemo.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片感知的文档分割器包装器
 * 确保在切分文档时不会截断 <image> 标签
 */
@Slf4j
public class ImageAwareSplitter implements DocumentSplitter {
    
    private final DocumentSplitter delegate;
    private static final Pattern IMAGE_TAG_PATTERN = Pattern.compile("<image>.*?</image>", Pattern.DOTALL);
    
    public ImageAwareSplitter(DocumentSplitter delegate) {
        this.delegate = delegate;
    }
    
    @Override
    public List<TextSegment> split(Document document) {
        log.debug("ImageAwareSplitter: splitting document with {} characters", 
                  document.text().length());
        
        // 使用委托的分割器进行切分
        List<TextSegment> segments = delegate.split(document);
        
        // 检查并修复可能被截断的图片标签
        List<TextSegment> fixedSegments = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            String text = segment.text();
            
            // 检查是否有未闭合的图片标签
            if (hasUnclosedImageTag(text)) {
                log.warn("Detected unclosed image tag in segment {}, attempting to fix...", i);
                
                // 尝试与下一个片段合并
                if (i + 1 < segments.size()) {
                    TextSegment nextSegment = segments.get(i + 1);
                    String mergedText = text + "\n" + nextSegment.text();
                    
                    // 重新检查是否修复
                    if (!hasUnclosedImageTag(mergedText)) {
                        log.info("Fixed by merging with next segment");
                        fixedSegments.add(TextSegment.from(mergedText, segment.metadata()));
                        i++; // 跳过下一个片段
                        continue;
                    }
                }
                
                // 如果无法修复，移除不完整的图片标签
                text = removeIncompleteImageTags(text);
                log.warn("Removed incomplete image tags from segment");
            }
            
            fixedSegments.add(TextSegment.from(text, segment.metadata()));
        }
        
        log.info("ImageAwareSplitter: produced {} segments (original: {})", 
                 fixedSegments.size(), segments.size());
        return fixedSegments;
    }
    
    /**
     * 检查文本中是否有未闭合的图片标签
     */
    private boolean hasUnclosedImageTag(String text) {
        int openCount = countOccurrences(text, "<image>");
        int closeCount = countOccurrences(text, "</image>");
        return openCount != closeCount;
    }
    
    /**
     * 移除不完整的图片标签
     */
    private String removeIncompleteImageTags(String text) {
        // 简单策略：移除所有孤立的 <image> 或 </image> 标签
        // 先找到所有完整的 <image>...</image> 对
        StringBuilder result = new StringBuilder();
        Matcher matcher = IMAGE_TAG_PATTERN.matcher(text);
        int lastEnd = 0;
        
        while (matcher.find()) {
            // 添加前面的非图片文本
            result.append(text, lastEnd, matcher.start());
            // 添加完整的图片标签
            result.append(matcher.group());
            lastEnd = matcher.end();
        }
        
        // 添加剩余的文本（不包含孤立标签）
        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd);
            // 移除孤立的标签
            remaining = remaining.replaceAll("</?image>", "");
            result.append(remaining);
        }
        
        return result.toString();
    }
    
    /**
     * 计算子字符串出现的次数
     */
    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
    
    /**
     * 调整切分点，确保不在图片标签内部切分
     * 
     * @param text 原始文本
     * @param proposedSplitPoint 建议的切分点
     * @return 调整后的切分点
     */
    public static int adjustSplitPoint(String text, int proposedSplitPoint) {
        if (proposedSplitPoint >= text.length()) {
            return proposedSplitPoint;
        }
        
        // 检查切分点是否在 <image> 标签内部
        String beforeSplit = text.substring(0, proposedSplitPoint);
        String afterSplit = text.substring(proposedSplitPoint);
        
        int openTagsBefore = countOccurrencesStatic(beforeSplit, "<image>");
        int closeTagsBefore = countOccurrencesStatic(beforeSplit, "</image>");
        
        // 如果开始标签多于结束标签，说明在标签内部
        if (openTagsBefore > closeTagsBefore) {
            // 找到下一个 </image> 标签的位置
            int endTagPos = afterSplit.indexOf("</image>");
            if (endTagPos != -1) {
                // 调整到标签结束后
                int adjustedPoint = proposedSplitPoint + endTagPos + "</image>".length();
                log.debug("Adjusted split point from {} to {} to avoid cutting image tag", 
                         proposedSplitPoint, adjustedPoint);
                return adjustedPoint;
            }
        }
        
        return proposedSplitPoint;
    }
    
    /**
     * 计算子字符串出现的次数（静态版本）
     */
    private static int countOccurrencesStatic(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }
}
