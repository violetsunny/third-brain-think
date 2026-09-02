package cn.hollis.llm.mentor.ragdemo.splitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Word 分割器开关初始化器。
 *
 * <p>将配置属性 {@code rag.word-splitter.enable}（默认 false）注入到静态工厂
 * {@link DocumentSplitterFactory#wordSplitterEnabled}，避免将静态工厂改为 Spring Bean。
 *
 * <p>实现 {@link InitializingBean}，在所有 Bean 的属性注入完成后、应用就绪事件之前立即执行，
 * 确保 Spring 上下文内任何 Bean 调用 {@code DocumentSplitterFactory.create("WORD", ...)} 时
 * 开关值已经就位。
 *
 * <p>当 {@code rag.word-splitter.enable=true} 时，
 * {@code DocumentSplitterFactory.create("WORD", ...)} 才会返回 {@link WordHeaderSplitter}；
 * 否则抛出 {@code IllegalArgumentException}，与其他未知策略行为一致。
 */
@Slf4j
@Component
public class WordSplitterInitializer implements InitializingBean {

    @Value("${rag.word-splitter.enable:false}")
    private boolean wordSplitterEnabled;

    @Override
    public void afterPropertiesSet() {
        DocumentSplitterFactory.wordSplitterEnabled = wordSplitterEnabled;
        log.info("WordSplitterInitializer: rag.word-splitter.enable={}", wordSplitterEnabled);
    }
}
