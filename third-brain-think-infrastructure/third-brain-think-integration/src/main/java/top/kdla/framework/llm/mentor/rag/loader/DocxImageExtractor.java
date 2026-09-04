package top.kdla.framework.llm.mentor.rag.loader;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Word/Docx图片提取器
 * 使用Apache POI提取.docx文件中的图片
 */
@Slf4j
public class DocxImageExtractor implements ImageExtractor {
    
    private final ChatModel chatModel;
    private static final AtomicInteger imageCounter = new AtomicInteger(0);
    
    public DocxImageExtractor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }
    
    @Override
    public List<ImagePlaceholder> extractImages(File file) {
        log.info("开始从Word文档提取图片: {}", file.getName());
        List<ImagePlaceholder> placeholders = new ArrayList<>();
        
        try (FileInputStream fis = new FileInputStream(file);
             XWPFDocument document = new XWPFDocument(fis)) {
            
            // 获取所有图片数据
            List<XWPFPictureData> pictures = document.getAllPictures();
            log.info("找到 {} 张图片", pictures.size());
            
            // 遍历文档段落，记录图片出现的位置
            int paragraphIndex = 0;
            int pictureIndex = 0;
            
            for (var paragraph : document.getParagraphs()) {
                // 检查段落中是否包含图片
                var runs = paragraph.getRuns();
                if (runs != null) {
                    for (var run : runs) {
                        // 尝试从run中提取图片
                        var picturesInRun = run.getEmbeddedPictures();
                        if (picturesInRun != null && !picturesInRun.isEmpty()) {
                            for (XWPFPicture picture : picturesInRun) {
                                String imageId = String.format("IMG_%03d", imageCounter.incrementAndGet());
                                
                                // 获取图片数据
                                XWPFPictureData pictureData = picture.getPictureData();
                                
                                // 处理图片并生成描述
                                String description = processImage(pictureData);
                                
                                ImagePlaceholder placeholder = ImagePlaceholder.builder()
                                    .imageId(imageId)
                                    .description(description)
                                    .position(paragraphIndex)
                                    .marker(String.format("[%s]", imageId))
                                    .build();
                                
                                placeholders.add(placeholder);
                                pictureIndex++;
                                log.debug("提取图片 {}/{}: {}", pictureIndex, pictures.size(), imageId);
                            }
                        }
                    }
                }
                paragraphIndex++;
            }
            
            log.info("成功提取 {} 张图片占位符", placeholders.size());
            return placeholders;
            
        } catch (Exception e) {
            log.error("Word图片提取失败", e);
            return Collections.emptyList();
        }
    }
    
    @Override
    public boolean supports(String fileName) {
        return fileName != null && (
            fileName.toLowerCase().endsWith(".docx") || 
            fileName.toLowerCase().endsWith(".doc")
        );
    }
    
    /**
     * 处理单张图片：转换为字节数组并用AI生成描述
     */
    private String processImage(XWPFPictureData picture) {
        try {
            byte[] imageBytes = picture.getData();
            
            // AI识别图片
            String description = imageToText(imageBytes, picture.suggestFileExtension());
            
            return (description == null || description.trim().isEmpty()) 
                ? "[无法识别图片内容]" 
                : description.trim();
                
        } catch (Exception e) {
            log.error("图片处理失败: {}", picture.getFileName(), e);
            return "[图片处理错误]";
        }
    }
    
    /**
     * 使用AI识别图片内容
     */
    private String imageToText(byte[] imageBytes, String extension) {
        try {
            // 确定MIME类型
            String mimeType = getMimeType(extension);
            MimeType mimeTypeObj = MimeType.valueOf(mimeType);
            
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes);
            
            var userMessage = UserMessage.builder()
                .text("请详细描述这张图片的内容，包括场景、对象、布局、颜色、文字信息等。如果是图表、流程图或表格，请详细说明其结构和含义。直接输出纯文本描述，不要多余说明。")
                .media(List.of(new Media(mimeTypeObj, imageResource)))
                .build();
            
            var response = chatModel.call(new Prompt(List.of(userMessage)));
            return response.getResult().getOutput().getText();
            
        } catch (Exception e) {
            log.error("AI图片识别失败", e);
            return "[图片识别失败]";
        }
    }
    
    /**
     * 根据文件扩展名获取MIME类型
     */
    private String getMimeType(String extension) {
        if (extension == null) return MimeTypeUtils.IMAGE_PNG.toString();
        
        return switch (extension.toLowerCase()) {
            case "png" -> MimeTypeUtils.IMAGE_PNG.toString();
            case "jpg", "jpeg" -> MimeTypeUtils.IMAGE_JPEG.toString();
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> MimeTypeUtils.IMAGE_PNG.toString();
        };
    }
}
