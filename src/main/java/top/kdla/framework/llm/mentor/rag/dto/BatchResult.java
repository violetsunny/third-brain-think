package top.kdla.framework.llm.mentor.rag.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量操作结果
 * - success: 成功的资源标识列表（docId / fileName 等）
 * - failed:  失败的映射 {资源标识 -> 错误原因}
 */
@Data
public class BatchResult {

    /** 成功处理的资源标识列表 */
    private List<String> success = new ArrayList<>();

    /** 失败的资源标识 → 错误原因 */
    private Map<String, String> failed = new LinkedHashMap<>();

    public BatchResult() {}

    public void addSuccess(String id) {
        success.add(id);
    }

    public void addFailed(String id, String reason) {
        failed.put(id, reason);
    }
}
