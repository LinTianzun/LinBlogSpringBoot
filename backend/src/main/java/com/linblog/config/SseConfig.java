package com.linblog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE 推送配置（§7.7，M6）：事件投递跑在虚拟线程池，
 * 慢客户端的 send 阻塞不占用发送方（发私信/举报）线程。
 */
@Configuration
public class SseConfig {

    @Bean(name = "sseExecutor")
    public ExecutorService sseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
