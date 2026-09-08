package top.kdla.framework.llm.mentor.ragcore.es;

import lombok.Data;

import java.util.Map;

@Data
public class EsDocumentChunk {

    private String id;
    private String content;
    private Map<String, Object> metadata;
}