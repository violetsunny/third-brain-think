package top.kdla.framework.llm.mentor.rag.loader;

import dev.langchain4j.data.document.Document;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.DrawObject;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * PDF多模态处理器
 * 提取PDF中的文字和图片,并对图片进行AI识别生成描述
 * 参考 rag 模块的 PdfMultimodalProcessor 优化实现
 */
@Slf4j
@Component
public class PdfMultimodalProcessor {

    @Autowired
    private ChatModel chatModel;

    @Value("${rag.document.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 处理PDF文件,提取文字和图片内容
     *
     * @param pdfFile PDF文件
     * @return 包含图片描述的文本内容
     */
    public String processPdf(File pdfFile) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            int totalPages = document.getNumberOfPages();
            StringBuilder finalText = new StringBuilder();
            log.info("开始处理PDF文件: {}, 总页数: {}", pdfFile.getName(), totalPages);

            for (int pageNum = 0; pageNum < totalPages; pageNum++) {
                PDPage page = document.getPage(pageNum);
                float pageHeight = page.getMediaBox().getHeight();

                UnifiedContentStripper stripper = new UnifiedContentStripper(pageHeight);
                stripper.setStartPage(pageNum + 1);
                stripper.setEndPage(pageNum + 1);
                stripper.getText(document);

                List<ContentElement> allElements = stripper.getElements();

                // 按坐标排序: Y轴从上到下, X轴从左到右
                allElements.sort((e1, e2) -> {
                    if (Math.abs(e1.getY0() - e2.getY0()) > 5) {
                        return Integer.compare(e2.getY0(), e1.getY0());
                    }
                    return Integer.compare(e1.getX0(), e2.getX0());
                });

                for (ContentElement element : allElements) {
                    finalText.append(element.getContent()).append("\n");
                }
                finalText.append("\n");
            }
            
            log.info("PDF处理完成, 总字符数: {}", finalText.length());
            return finalText.toString().trim();
        }
    }

    /**
     * 处理单张图片: AI识别生成描述
     */
    private String processImage(PDImageXObject image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BufferedImage bufferedImage = image.getImage();
            ImageIO.write(bufferedImage, "png", baos);
            byte[] imageBytes = baos.toByteArray();

            // AI识别图片
            String description = imageToText(imageBytes);
            description = (description == null || description.trim().isEmpty()) 
                    ? "[无法识别图片内容]" 
                    : description.trim();

            // 返回XML格式的图片标签
            return String.format("<image>%s</image>", escapeXml(description));
        } catch (Exception e) {
            log.error("图片处理异常", e);
            return "[图片处理错误]";
        }
    }

    /**
     * 转义XML特殊字符
     */
    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * 使用AI识别图片内容
     */
    private String imageToText(byte[] imageBytes) {
        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes);
            
            var userMessage = UserMessage.builder()
                    .text("请详细描述这张图片的内容,包括场景、对象、布局、颜色、文字信息等。直接输出纯文本描述,不要多余说明。")
                    .media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, imageResource)))
                    .build();
            
            var response = chatModel.call(new Prompt(List.of(userMessage)));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("AI图片识别失败", e);
            return "[图片识别失败]";
        }
    }

    // --- 内部类 ---

    private enum ContentType { TEXT, IMAGE }

    private static class ContentElement {
        private final ContentType type;
        private final String content;
        private final int x0;
        private final int y0;

        public ContentElement(ContentType type, String content, int x0, int y0) {
            this.type = type;
            this.content = content;
            this.x0 = x0;
            this.y0 = y0;
        }

        public ContentType getType() { return type; }
        public String getContent() { return content; }
        public int getX0() { return x0; }
        public int getY0() { return y0; }
    }

    /**
     * 统一内容提取器
     * 同时捕获文字和图片及其位置信息
     */
    private class UnifiedContentStripper extends PDFTextStripper {
        private final List<ContentElement> elements = new ArrayList<>();
        private final float pageHeight;

        public UnifiedContentStripper(float pageHeight) throws IOException {
            super();
            this.pageHeight = pageHeight;
            addOperator(new DrawObject(this));
            addOperator(new SetMatrix(this));
            addOperator(new Concatenate(this));
            addOperator(new SetGraphicsStateParameters(this));
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (!textPositions.isEmpty() && !text.trim().isEmpty()) {
                TextPosition first = textPositions.get(0);
                int x0 = (int) first.getXDirAdj();
                int y0 = (int) (pageHeight - first.getYDirAdj());
                elements.add(new ContentElement(ContentType.TEXT, text.trim(), x0, y0));
            }
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            String operation = operator.getName();
            if ("Do".equals(operation)) {
                COSName objectName = (COSName) operands.get(0);
                PDXObject xobject = getResources().getXObject(objectName);

                if (xobject instanceof PDImageXObject image) {
                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                    float x = ctm.getTranslateX();
                    float y = ctm.getTranslateY();
                    float h = ctm.getScalingFactorY();

                    int x0 = (int) x;
                    int y0 = (int) (y + h);

                    String imageTag = processImage(image);
                    elements.add(new ContentElement(ContentType.IMAGE, imageTag, x0, y0));
                }
            } else {
                super.processOperator(operator, operands);
            }
        }

        public List<ContentElement> getElements() {
            return elements;
        }
    }
}
