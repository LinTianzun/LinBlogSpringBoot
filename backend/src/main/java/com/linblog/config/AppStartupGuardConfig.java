package com.linblog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * AppStartupGuard 的注册入口。
 * BeanFactoryPostProcessor 必须以 static @Bean 声明（Spring 官方推荐）：
 * BFPP 会在 BeanPostProcessor 注册之前被提早实例化，扫描注册的 @Component
 * 走不了构造器自动装配（会回退无参构造器并崩溃）；static @Bean 的参数
 * 由容器按类型直接解析，不受该阶段限制。
 */
@Configuration
public class AppStartupGuardConfig {

    @Bean
    public static AppStartupGuard appStartupGuard(Environment environment) {
        return new AppStartupGuard(environment);
    }
}
