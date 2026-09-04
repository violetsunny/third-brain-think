package top.kdla.framework.llm.mentor.rag.loader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片占位符
 * 用于在文档文本中标记图片位置和内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImagePlaceholder {
    
    /**
     * AI生成的图片描述
     */
    private String description;
    
    /**
     * 在原文中的插入位置（字符索引）
     */
    private int position;
    
    /**
     * 占位符标记，如 [IMG_001]
     */
    private String marker;
    
    /**
     * 图片ID（唯一标识）
     */
    private String imageId;
    
    /**
     * 生成XML格式的图片标签
     * 例如: <image id="IMG_001">这是一个流程图，展示了...</image>
     */
    public String toXmlTag() {
        return String.format("<image id=\"%s\">%s</image>", 
            escapeXml(imageId), 
            escapeXml(description));
    }
    
    /**
     * 生成Markdown格式的图片描述
     * 例如: ![IMG_001](这是一个流程图，展示了...)
     */
    public String toMarkdownTag() {
        return String.format("![%s](%s)", 
            imageId, 
            description);
    }
    
    /**
     * 转义XML特殊字符
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;");
    }
}
