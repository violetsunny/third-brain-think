package top.kdla.framework.llm.mentor.rag.splitter;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel / CSV 文档切分器。
 * <p>
 * 支持输出模式:
 * <ul>
 *   <li>{@link OutputMode#KEY_VALUE} — 每行转为 "header1: value1; header2: value2" 字符串</li>
 *   <li>{@link OutputMode#HTML_TABLE} — 多行分组后生成 HTML table（按 chunkSize 字符上限）</li>
 * </ul>
 * <p>
 * 文件类型通过魔术字节自动检测:
 * <ul>
 *   <li>PK (50 4B 03 04) → xlsx</li>
 *   <li>OLE2 (D0 CF 11 E0) → xls</li>
 *   <li>其余 → csv</li>
 * </ul>
 */
@Slf4j
public class ExcelSplitter implements DocumentSplitter {

    public enum OutputMode {
        KEY_VALUE,
        HTML_TABLE
    }

    private final int chunkSize;
    private final OutputMode outputMode;

    public ExcelSplitter(int chunkSize) {
        this(chunkSize, OutputMode.KEY_VALUE);
    }

    public ExcelSplitter(int chunkSize, OutputMode outputMode) {
        this.chunkSize = chunkSize;
        this.outputMode = outputMode;
    }

    @Override
    public List<TextSegment> split(Document document) {
        // The Document text is expected to be the file path stored during upload,
        // or raw CSV content. Metadata "filePath" takes priority.
        String filePath = document.metadata().getString("filePath");
        if (filePath == null) {
            filePath = document.metadata().getString("fileName");
        }

        if (filePath != null && new File(filePath).exists()) {
            return splitFile(new File(filePath));
        }

        // Fall back: treat document text as raw CSV content
        log.warn("ExcelSplitter: no physical file found, treating document text as CSV content");
        return splitCsvContent(document.text());
    }

    // ─────────────────────────────────────────────────────────────
    // File-level routing
    // ─────────────────────────────────────────────────────────────

    private List<TextSegment> splitFile(File file) {
        byte[] magic = readMagicBytes(file, 4);
        String fileType = detectType(magic);
        log.info("ExcelSplitter detected file type: {} for {}", fileType, file.getName());

        return switch (fileType) {
            case "xlsx", "xls" -> splitExcel(file);
            default -> splitCsvFile(file);
        };
    }

    // ─────────────────────────────────────────────────────────────
    // Task 6.2: Magic byte detection
    // ─────────────────────────────────────────────────────────────

    private String detectType(byte[] magic) {
        if (magic.length >= 4) {
            // xlsx: PK signature
            if ((magic[0] & 0xFF) == 0x50 && (magic[1] & 0xFF) == 0x4B
                    && (magic[2] & 0xFF) == 0x03 && (magic[3] & 0xFF) == 0x04) {
                return "xlsx";
            }
            // xls: OLE2 signature
            if ((magic[0] & 0xFF) == 0xD0 && (magic[1] & 0xFF) == 0xCF
                    && (magic[2] & 0xFF) == 0x11 && (magic[3] & 0xFF) == 0xE0) {
                return "xls";
            }
        }
        return "csv";
    }

    private byte[] readMagicBytes(File file, int count) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[count];
            int read = in.read(buf);
            if (read < count) {
                byte[] result = new byte[read];
                System.arraycopy(buf, 0, result, 0, read);
                return result;
            }
            return buf;
        } catch (IOException e) {
            log.warn("Failed to read magic bytes from {}: {}", file.getName(), e.getMessage());
            return new byte[0];
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Task 6.3: xlsx/xls parsing via EasyExcel
    // ─────────────────────────────────────────────────────────────

    private List<TextSegment> splitExcel(File file) {
        List<List<String>> headers = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();

        EasyExcel.read(file, new AnalysisEventListener<Map<Integer, String>>() {
            private List<String> headerRow;

            @Override
            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                headerRow = new ArrayList<>(headMap.values());
                if (headers.isEmpty()) headers.add(headerRow);
            }

            @Override
            public void invoke(Map<Integer, String> data, AnalysisContext context) {
                List<String> hdr = headers.isEmpty() ? new ArrayList<>() : headers.get(0);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < hdr.size(); i++) {
                    row.put(hdr.get(i), data.getOrDefault(i, ""));
                }
                rows.add(row);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("EasyExcel finished reading, total rows: {}", rows.size());
            }
        }).headRowNumber(1).doReadAll();

        List<String> headerRow = headers.isEmpty() ? new ArrayList<>() : headers.get(0);
        return buildSegments(headerRow, rows);
    }

    // ─────────────────────────────────────────────────────────────
    // Task 6.4: CSV parsing
    // ─────────────────────────────────────────────────────────────

    private List<TextSegment> splitCsvFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = in.readAllBytes();
            return splitCsvContent(new String(stripBom(bytes), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("Failed to read CSV file {}: {}", file.getName(), e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<TextSegment> splitCsvContent(String content) {
        if (content == null || content.isBlank()) return new ArrayList<>();

        List<String> headerRow = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                String[] cells = parseCsvLine(line);
                if (firstLine) {
                    for (String h : cells) headerRow.add(h.trim());
                    firstLine = false;
                } else {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < headerRow.size(); i++) {
                        row.put(headerRow.get(i), i < cells.length ? cells[i].trim() : "");
                    }
                    rows.add(row);
                }
            }
        } catch (IOException e) {
            log.error("Failed to parse CSV content: {}", e.getMessage());
        }

        return buildSegments(headerRow, rows);
    }

    /** Remove UTF-8 BOM if present */
    private byte[] stripBom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return bytes;
    }

    /** Naive CSV line parser (no quoted-field support) */
    private String[] parseCsvLine(String line) {
        return line.split(",", -1);
    }

    // ─────────────────────────────────────────────────────────────
    // Task 6.5 & 6.6: Build segments from parsed rows
    // ─────────────────────────────────────────────────────────────

    private List<TextSegment> buildSegments(List<String> headers, List<Map<String, String>> rows) {
        return switch (outputMode) {
            case KEY_VALUE -> buildKeyValueSegments(headers, rows);
            case HTML_TABLE -> buildHtmlTableSegments(headers, rows);
        };
    }

    /** Task 6.5: KEY_VALUE — one TextSegment per row */
    private List<TextSegment> buildKeyValueSegments(List<String> headers, List<Map<String, String>> rows) {
        List<TextSegment> segments = new ArrayList<>();
        for (Map<String, String> row : rows) {
            StringBuilder sb = new StringBuilder();
            row.forEach((k, v) -> {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(k).append(": ").append(v);
            });
            if (!sb.isEmpty()) {
                segments.add(TextSegment.from(sb.toString()));
            }
        }
        return segments;
    }

    /** Task 6.6: HTML_TABLE — group rows up to chunkSize chars, produce HTML tables */
    private List<TextSegment> buildHtmlTableSegments(List<String> headers, List<Map<String, String>> rows) {
        List<TextSegment> segments = new ArrayList<>();
        List<Map<String, String>> group = new ArrayList<>();
        int currentSize = 0;

        for (Map<String, String> row : rows) {
            String rowHtml = buildRowHtml(row);
            if (currentSize + rowHtml.length() > chunkSize && !group.isEmpty()) {
                segments.add(TextSegment.from(buildTableHtml(headers, group)));
                group.clear();
                currentSize = 0;
            }
            group.add(row);
            currentSize += rowHtml.length();
        }

        if (!group.isEmpty()) {
            segments.add(TextSegment.from(buildTableHtml(headers, group)));
        }
        return segments;
    }

    private String buildTableHtml(List<String> headers, List<Map<String, String>> rows) {
        StringBuilder sb = new StringBuilder("<table>\n<tr>");
        for (String h : headers) sb.append("<th>").append(h).append("</th>");
        sb.append("</tr>\n");
        for (Map<String, String> row : rows) {
            sb.append(buildRowHtml(row)).append("\n");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private String buildRowHtml(Map<String, String> row) {
        StringBuilder sb = new StringBuilder("<tr>");
        row.values().forEach(v -> sb.append("<td>").append(v).append("</td>"));
        sb.append("</tr>");
        return sb.toString();
    }
}
