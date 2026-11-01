package com.linblog.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 邮件配置（M4）：JavaMailSender 由 §4.3 SMTP_* 环境变量（app.smtp.*）手工组装，
 * 不走 spring.mail.* 自动装配；SMTP 未配置时仍给占位实例（MailService 入口守卫，绝不投递）。
 * 投递跑在虚拟线程池，失败只记 system_log。
 */
@Configuration
public class MailConfig {

    @Bean
    public JavaMailSender javaMailSender(AppProperties props) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        AppProperties.Smtp smtp = props.smtp();
        if (smtp == null || smtp.host() == null || smtp.host().isBlank()) {
            sender.setHost("localhost");
            return sender;
        }
        sender.setHost(smtp.host());
        sender.setPort(smtp.port() > 0 ? smtp.port() : 587);
        boolean withAuth = smtp.user() != null && !smtp.user().isBlank();
        if (withAuth) {
            sender.setUsername(smtp.user());
            sender.setPassword(smtp.pass());
        }
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(withAuth));
        p.put("mail.smtp.ssl.enable", String.valueOf(smtp.secure()));
        p.put("mail.smtp.connectiontimeout", "5000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    @Bean(name = "mailExecutor")
    public ExecutorService mailExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
