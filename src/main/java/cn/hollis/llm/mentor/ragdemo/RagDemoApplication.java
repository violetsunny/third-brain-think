package cn.hollis.llm.mentor.ragdemo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * RAG Demo 主应用
 * 融合 LangChain4j 和 Spring AI 的高级 RAG 系统
 */
@SpringBootApplication
@MapperScan("cn.hollis.llm.mentor.ragdemo.mapper")
public class RagDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagDemoApplication.class, args);
    }
}
