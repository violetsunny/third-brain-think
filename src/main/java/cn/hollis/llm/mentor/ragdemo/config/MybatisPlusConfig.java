package cn.hollis.llm.mentor.ragdemo.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MybatisPlusConfig {
    
    /**
     * MyBatis-Plus 插件拦截器
     * （分页插件 PaginationInnerInterceptor 在 mybatis-plus 3.5.x 中已合并到 MybatisPlusInterceptor，
     *  此处不再单独配置，若需分页可按需引入具体的 InnerInterceptor 实现。）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        return new MybatisPlusInterceptor();
    }
}
