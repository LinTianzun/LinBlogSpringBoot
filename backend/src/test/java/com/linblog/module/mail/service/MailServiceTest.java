package com.linblog.module.mail.service;

import com.linblog.config.AppProperties;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** §7.9/§4.3 SMTP：异步投递、失败只记 system_log 不外抛。 */
class MailServiceTest {

    private JavaMailSender mailSender;
    private SystemLogService systemLogService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        systemLogService = mock(SystemLogService.class);
        executor = mock(ExecutorService.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).submit(any(Runnable.class));
    }

    private MailService service(AppProperties props) {
        return new MailService(mailSender, props, systemLogService, executor);
    }

    private AppProperties smtpProps(String host, String from) {
        return TestAppProperties.of(new Object[]{
                21, new AppProperties.Smtp(host, 587, false, "u", "p", from)});
    }

    @Test
    void isConfiguredReflectsSmtp() {
        assertTrue(service(smtpProps("smtp.test.local", "no-reply@linlog.com")).isConfigured());
        assertFalse(service(TestAppProperties.defaults()).isConfigured());
        assertFalse(service(smtpProps(" ", "no-reply@linlog.com")).isConfigured());
    }

    @Test
    void sendResetLinkDeliversAsynchronously() {
        service(smtpProps("smtp.test.local", "no-reply@linlog.com"))
                .sendResetLink("alice@linlog.com", "http://localhost:5173/reset-password?token=abc");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage msg = captor.getValue();
        assertEquals("alice@linlog.com", msg.getTo()[0]);
        assertEquals("no-reply@linlog.com", msg.getFrom());
        assertTrue(msg.getText().contains("http://localhost:5173/reset-password?token=abc"));
        verify(systemLogService, times(0)).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendFailureLoggedNotThrown() {
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        service(smtpProps("smtp.test.local", "no-reply@linlog.com"))
                .sendResetLink("alice@linlog.com", "http://x/reset");

        verify(systemLogService).record(isNull(), eq("mail"), eq("send_failed"), eq("mail"),
                isNull(), contains("alice@linlog.com"), isNull());
    }

    @Test
    void fromOmittedWhenNotConfigured() {
        service(smtpProps("smtp.test.local", null)).sendResetLink("a@b.c", "http://x/reset");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertNull(captor.getValue().getFrom());
    }

    @Test
    void unconfiguredServiceDoesNotSend() {
        MailService none = service(TestAppProperties.defaults());
        assertFalse(none.isConfigured());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }
}
