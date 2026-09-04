package top.kdla.framework.llm.mentor.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * 数学计算器工具。使用 JDK 内置 JavaScript 引擎（Nashorn/Rhino）求值数学表达式。
 * 支持四则运算、括号、幂运算（Math.pow 或 **）、Math.sqrt、Math.abs 等。
 * 表达式非法时返回错误说明字符串，不抛异常。
 */
@Slf4j
@Service
public class CalculatorTool {

    private static final ScriptEngine ENGINE;

    static {
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine engine = manager.getEngineByName("JavaScript");
        if (engine == null) {
            engine = manager.getEngineByName("Nashorn");
        }
        ENGINE = engine;
    }

    @Tool(name = "calculator", description = "计算数学表达式，支持四则运算、括号、幂运算(Math.pow 或 **)、Math.sqrt、Math.abs。示例: (3+5)*2/4")
    public String calculate(@ToolParam(description = "数学表达式字符串") String expression) {
        if (expression == null || expression.isBlank()) {
            return "错误: 表达式不能为空";
        }
        if (ENGINE == null) {
            return "错误: JavaScript 引擎不可用";
        }

        // Normalize: replace ^ with ** for power
        String normalized = expression.replace("^", "**");

        log.info("CalculatorTool.calculate: expression={}", expression);
        try {
            Object result = ENGINE.eval(normalized);
            if (result == null) {
                return "错误: 表达式求值结果为空";
            }
            // Convert Double/Integer to string
            if (result instanceof Double d) {
                if (d.isInfinite()) return "错误: 除数为零（结果为 Infinity）";
                if (d.isNaN()) return "错误: 无效的数学操作（结果为 NaN）";
                // Return without trailing .0 for integer-valued results
                if (d == Math.floor(d) && !d.isInfinite()) {
                    return String.valueOf(d.longValue());
                }
                return String.valueOf(d);
            }
            return result.toString();
        } catch (javax.script.ScriptException e) {
            log.warn("CalculatorTool: invalid expression: {}: {}", expression, e.getMessage());
            return "错误: 表达式无效 — " + e.getMessage();
        } catch (Exception e) {
            log.warn("CalculatorTool: unexpected error for: {}: {}", expression, e.getMessage());
            return "错误: 计算失败 — " + e.getMessage();
        }
    }
}
