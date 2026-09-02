package cn.hollis.llm.mentor.ragdemo.agent.tool;

import java.util.List;

/**
 * Groovy 代码静态安全检查器。
 * 通过黑名单关键词字符串匹配实现，不依赖完整 AST 解析。
 * 检测到禁止模式时抛出 {@link SecurityException}。
 */
public final class GroovyCodeVerifier {

    private GroovyCodeVerifier() {}

    /** 禁止的代码模式 */
    private static final List<String> BLACKLIST = List.of(
            "java.io",
            "java.net",
            "Runtime",
            "System.exit",
            "ProcessBuilder",
            "Class.forName",
            "getRuntime",
            "exec(",
            "ClassLoader",
            "Thread.currentThread",
            "System.setProperty",
            "System.getenv",
            "Reflection"
    );

    /**
     * 检查代码是否包含禁止的模式。
     *
     * @param code Groovy 代码字符串
     * @throws SecurityException 当代码包含黑名单关键词时
     */
    public static void verify(String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        for (String banned : BLACKLIST) {
            if (code.contains(banned)) {
                throw new SecurityException("安全检查失败: 代码包含禁止的操作 [" + banned + "]");
            }
        }
    }
}
