package top.kdla.framework.llm.mentor.rag.loader;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多格式文档加载器
 * 支持: PDF, DOC, DOCX, Markdown
 * PDF文件使用多模态处理器提取图片和表格
 */
@Slf4j
@Component
public class MultiFormatDocumentLoader {

    @Autowired
    private PdfMultimodalProcessor pdfMultimodalProcessor;
    
    @Autowired
    private ChatModel chatModel;

    private final ApacheTikaDocumentParser tikaParser = new ApacheTikaDocumentParser();

    /**
     * 从文件路径加载文档
     */
    public Document loadDocument(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        
        String fileName = file.getName();
        String fileType = detectFileType(fileName);
        
        if (!isSupportedFormat(fileType)) {
            throw new IllegalArgumentException("不支持的文件格式: " + fileType);
        }
        
        log.info("Loading document: {}, type: {}, size: {} bytes", 
                 fileName, fileType, file.length());
        
        try {
            Document document;
            
            // PDF文件使用多模态处理器（已包含图片提取）
            if ("pdf".equals(fileType)) {
                String content = pdfMultimodalProcessor.processPdf(file);
                document = Document.from(content);
            } 
            // DOCX文件需要提取图片
            else if ("docx".equals(fileType) || "doc".equals(fileType)) {
                document = loadDocxWithImages(file);
            }
            // Excel/CSV — return a skeleton Document; content is split by ExcelSplitter
            else if ("xlsx".equals(fileType) || "xls".equals(fileType) || "csv".equals(fileType)) {
                document = Document.from("", dev.langchain4j.data.document.Metadata.from(
                        "filePath", file.getAbsolutePath()));
            }
            else {
                // 其他格式使用Tika解析
                document = FileSystemDocumentLoader.loadDocument(filePath, tikaParser);
            }
            
            // 增强元数据
            enrichMetadata(document, file, fileType);
            
            log.info("Document loaded successfully: {} characters", 
                     document.text() != null ? document.text().length() : 0);
            
            return document;
            
        } catch (Exception e) {
            log.error("Failed to load document: {}", fileName, e);
            throw new RuntimeException("文档加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 加载DOCX文件并提取图片
     */
    private Document loadDocxWithImages(File file) {
        log.info("Loading DOCX with image extraction: {}", file.getName());
        
        try {
            // 1. 使用Tika提取文本
            Document baseDocument = FileSystemDocumentLoader.loadDocument(file.getAbsolutePath(), tikaParser);
            String text = baseDocument.text();
            
            // 2. 提取图片并生成描述
            DocxImageExtractor imageExtractor = new DocxImageExtractor(chatModel);
            List<ImagePlaceholder> images = imageExtractor.extractImages(file);
            
            if (images.isEmpty()) {
                log.info("No images found in DOCX file");
                return baseDocument;
            }
            
            // 3. 按段落位置精确插入图片描述
            // 注意：由于 Tika 解析后的文本没有明确的段落标记，我们采用简化策略：
            // - 将文档按换行符分割为段落
            // - 根据 paragraphIndex 在对应位置插入图片描述
            String enhancedText = insertImagesAtPositions(text, images);
            
            log.info("Enhanced DOCX with {} image descriptions inserted at correct positions", images.size());
            return Document.from(enhancedText);
            
        } catch (Exception e) {
            log.error("Failed to load DOCX with images", e);
            // 降级：返回纯文本文档
            return FileSystemDocumentLoader.loadDocument(file.getAbsolutePath(), tikaParser);
        }
    }

    /**
     * 在指定位置插入图片描述
     * @param text 原始文本
     * @param images 图片占位符列表（已按 position 排序）
     * @return 增强后的文本
     */
    private String insertImagesAtPositions(String text, List<ImagePlaceholder> images) {
        if (images == null || images.isEmpty()) {
            return text;
        }
        
        // 按段落位置分组图片
        Map<Integer, List<ImagePlaceholder>> positionToImages = new HashMap<>();
        for (ImagePlaceholder img : images) {
            positionToImages.computeIfAbsent(img.getPosition(), k -> new ArrayList<>()).add(img);
        }
        
        // 将文本按换行符分割为段落
        String[] paragraphs = text.split("\n", -1);  // -1 保留末尾空字符串
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < paragraphs.length; i++) {
            // 添加当前段落
            result.append(paragraphs[i]);
            
            // 如果当前位置有图片，插入图片描述
            if (positionToImages.containsKey(i)) {
                result.append("\n\n");
                for (ImagePlaceholder img : positionToImages.get(i)) {
                    result.append(img.toXmlTag()).append("\n");
                }
            }
            
            // 添加换行符（除了最后一个段落）
            if (i < paragraphs.length - 1) {
                result.append("\n");
            }
        }
        
        // 处理超出段落范围的图片（追加到末尾）
        int maxParagraphIndex = paragraphs.length - 1;
        boolean hasExtraImages = false;
        for (Map.Entry<Integer, List<ImagePlaceholder>> entry : positionToImages.entrySet()) {
            if (entry.getKey() > maxParagraphIndex) {
                if (!hasExtraImages) {
                    result.append("\n\n--- 额外图片内容 ---\n\n");
                    hasExtraImages = true;
                }
                for (ImagePlaceholder img : entry.getValue()) {
                    result.append(img.toXmlTag()).append("\n");
                }
            }
        }
        
        return result.toString();
    }

    /**
     * 从字节数组加载文档
     */
    public Document loadDocument(byte[] content, String fileName) {
        String fileType = detectFileType(fileName);
        
        if (!isSupportedFormat(fileType)) {
            throw new IllegalArgumentException("不支持的文件格式: " + fileType);
        }
        
        log.info("Loading document from bytes: {}, type: {}", fileName, fileType);
        
        try {
            // 创建临时文件用于 Tika 解析
            File tempFile = File.createTempFile("rag-demo-", "-" + fileName);
            Files.write(tempFile.toPath(), content);
            tempFile.deleteOnExit();
            
            Document document = FileSystemDocumentLoader.loadDocument(tempFile.getAbsolutePath(), tikaParser);
            
            // 增强元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", fileName);
            metadata.put("fileType", fileType);
            metadata.put("fileSize", (long) content.length);
            document.metadata().putAll(metadata);
            
            return document;
            
        } catch (Exception e) {
            log.error("Failed to load document from bytes: {}", fileName, e);
            throw new RuntimeException("文档加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从输入流加载文档
     */
    public Document loadDocument(InputStream inputStream, String fileName) {
        String fileType = detectFileType(fileName);
        
        if (!isSupportedFormat(fileType)) {
            throw new IllegalArgumentException("不支持的文件格式: " + fileType);
        }
        
        log.info("Loading document from stream: {}, type: {}", fileName, fileType);
        
        try {
            // 创建临时文件
            File tempFile = File.createTempFile("rag-demo-", "-" + fileName);
            Files.copy(inputStream, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tempFile.deleteOnExit();
            
            Document document = FileSystemDocumentLoader.loadDocument(tempFile.getAbsolutePath(), tikaParser);
            
            // 增强元数据
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("fileName", fileName);
            metadata.put("fileType", fileType);
            metadata.put("fileSize", tempFile.length());
            document.metadata().putAll(metadata);
            
            return document;
            
        } catch (Exception e) {
            log.error("Failed to load document from stream: {}", fileName, e);
            throw new RuntimeException("文档加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检测文件类型
     */
    private String detectFileType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "unknown";
        }
        
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".pdf")) return "pdf";
        if (lowerName.endsWith(".docx")) return "docx";
        if (lowerName.endsWith(".doc")) return "doc";
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) return "markdown";
        if (lowerName.endsWith(".xlsx")) return "xlsx";
        if (lowerName.endsWith(".xls")) return "xls";
        if (lowerName.endsWith(".csv")) return "csv";

        return "unknown";
    }

    /**
     * 检查文件格式是否支持
     */
    public boolean isSupportedFormat(String fileType) {
        return "pdf".equals(fileType) ||
               "doc".equals(fileType) ||
               "docx".equals(fileType) ||
               "markdown".equals(fileType) ||
               "xlsx".equals(fileType) ||
               "xls".equals(fileType) ||
               "csv".equals(fileType);
    }

    /**
     * 增强文档元数据
     */
    private void enrichMetadata(Document document, File file, String fileType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fileName", file.getName());
        metadata.put("fileType", fileType);
        metadata.put("filePath", file.getAbsolutePath());
        metadata.put("fileSize", file.length());
        metadata.put("lastModified", file.lastModified());
        
        // 提取 Tika 解析的元数据
        document.metadata().putAll(metadata);
    }

    /**
     * 获取支持的格式列表
     */
    public String[] getSupportedFormats() {
        return new String[]{"pdf", "doc", "docx", "md", "markdown", "xlsx", "xls", "csv"};
    }
}
