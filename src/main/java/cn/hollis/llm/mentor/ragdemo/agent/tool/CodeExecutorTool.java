package cn.hollis.llm.mentor.ragdemo.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Groovy 代码执行工具（受限沙箱）。
 * 执行前通过 {@link GroovyCodeVerifier} 做静态黑名单检查，
 * 执行超时 3 秒，结果截断 500 字符。
 */
@Slf4j
@Service
public class CodeExecutorTool {

    private static final int TIMEOUT_SECONDS = 3;
    private static final int MAX_RESULT_LENGTH = 500;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "groovy-sandbox");
        t.setDaemon(true);
        return t;
    });

    @Tool(name = "code_executor",
          description = "在受限沙箱中执行 Groovy 代码并返回结果。禁止 IO/网络/反射等操作，超时 3 秒，结果截断 500 字符。示例: [1,2,3].sum()")
    public String execute(@ToolParam(description = "Groovy 代码字符串") String code) {
        if (code == null || code.isBlank()) {
            return "错误: 代码不能为空";
        }

        // Step 1: static security check
        try {
            GroovyCodeVerifier.verify(code);
        } catch (SecurityException e) {
            log.warn("CodeExecutorTool: security check failed: {}", e.getMessage());
            return "错误: " + e.getMessage();
        }

        // Step 2: execute with timeout
        log.info("CodeExecutorTool.execute: code={}", code);
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("groovy");

        if (engine == null) {
            return "错误: Groovy 引擎不可用，请确认 groovy-jsr223 依赖已引入";
        }

        final String finalCode = code;
        Future<Object> future = executor.submit(() -> engine.eval(finalCode));

        try {
            Object result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null) {
                return "null";
            }
            String resultStr = result.toString();
            if (resultStr.length() > MAX_RESULT_LENGTH) {
                resultStr = resultStr.substring(0, MAX_RESULT_LENGTH) + "...(截断)";
            }
            return resultStr;
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("CodeExecutorTool: execution timed out for code={}", code);
            return "错误: 执行超时（超过 " + TIMEOUT_SECONDS + " 秒）";
        } catch (Exception e) {
            log.warn("CodeExecutorTool: execution error: {}", e.getMessage());
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return "错误: 代码执行失败 — " + cause.getMessage();
        }
    }
}
