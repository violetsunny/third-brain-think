package top.kdla.framework.llm.mentor.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG Demo 主应用
 * 融合 LangChain4j 和 Spring AI 的高级 RAG 系统
 */
@SpringBootApplication
@MapperScan("top.kdla.framework.llm.mentor.rag.mapper")
public class RagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagDemoApplication.class, args);
    }
}
