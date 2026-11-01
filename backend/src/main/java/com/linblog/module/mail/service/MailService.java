package com.linblog.module.mail.service;

import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;

/**
 * SMTP 邮件（M4）：异步虚拟线程投递；SMTP 未配置时 {@link #isConfigured()}=false，
 * 调用方回落日志输出。任何投递失败只记 system_log + warn，不外抛、不阻塞业务。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties props;
    private final SystemLogService systemLogService;
    private final ExecutorService mailExecutor;

    public MailService(JavaMailSender mailSender, AppProperties props,
                       SystemLogService systemLogService,
                       @Qualifier("mailExecutor") ExecutorService mailExecutor) {
        this.mailSender = mailSender;
        this.props = props;
        this.systemLogService = systemLogService;
        this.mailExecutor = mailExecutor;
    }

    /** SMTP 是否可用（host 已配置）。决定 reset-request 走邮件还是日志。 */
    public boolean isConfigured() {
        AppProperties.Smtp smtp = props.smtp();
        return smtp != null && smtp.host() != null && !smtp.host().isBlank();
    }

    /** 异步发送密码重置邮件（链接一次性、30 分钟有效）。 */
    public void sendResetLink(String to, String link) {
        String body = "您正在重置 LinBlog 账号密码。重置链接 30 分钟内有效，请勿泄露给他人；"
                + "若非本人操作请忽略本邮件。\n\n" + link;
        mailExecutor.submit(() -> deliver(to, "LinBlog 密码重置", body));
    }

    void deliver(String to, String subject, String body) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            AppProperties.Smtp smtp = props.smtp();
            if (smtp != null && smtp.from() != null && !smtp.from().isBlank()) {
                msg.setFrom(smtp.from());
            }
            msg.setTo(to);
            msg.setSubject(subject);
            msg.setText(body);
            mailSender.send(msg);
            log.info("mail sent to={}", to);
        } catch (RuntimeException e) {
            log.warn("mail send failed to={}: {}", to, e.getMessage());
            systemLogService.record(null, "mail", "send_failed", "mail", null,
                    to + ": " + e.getMessage(), null);
        }
    }
}
