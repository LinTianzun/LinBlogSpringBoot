package com.linblog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 媒体相关配置（§7.5）：缩略图生成跑在虚拟线程池（事务提交后异步执行）。
 */
@Configuration
public class MediaConfig {

    @Bean(name = "thumbnailExecutor")
    public ExecutorService thumbnailExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
