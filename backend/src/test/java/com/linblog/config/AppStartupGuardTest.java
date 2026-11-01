package com.linblog.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** §4.3 启动强校验：配置错误聚合报错，拒绝启动。 */
class AppStartupGuardTest {

    private static ConfigurableListableBeanFactory beanFactory() {
        return mock(ConfigurableListableBeanFactory.class);
    }

    private MockEnvironment baseEnv() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/springlinblog");
        env.setProperty("spring.datasource.username", "root");
        env.setProperty("spring.datasource.password", "root");
        env.setProperty("app.jwt-access-secret", "0123456789abcdef0123456789abcdef");
        env.setProperty("app.jwt-access-ttl", "PT15M");
        env.setProperty("app.refresh-ttl-days", "7");
        env.setProperty("app.allowed-origins", "http://localhost:5173");
        env.setProperty("app.storage-driver", "local");
        env.setProperty("app.storage-local-dir", "./data/storage");
        env.setProperty("app.thumb-widths", "320,640,1280");
        env.setProperty("app.upload-max-bytes", "20MB");
        env.setProperty("app.view-dedup-minutes", "30");
        env.setProperty("app.audit-retention-days", "90");
        env.setProperty("app.trash-retention-days", "30");
        env.setProperty("app.rate-limit-login", "5");
        env.setProperty("app.rate-limit-reset", "3");
        return env;
    }

    @Test
    void validConfigPasses() {
        AppStartupGuard guard = new AppStartupGuard(baseEnv());
        assertDoesNotThrow(() -> guard.postProcessBeanFactory(beanFactory()));
    }

    @Test
    void emptyConfigAggregatesAllErrors() {
        AppStartupGuard guard = new AppStartupGuard(new MockEnvironment());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        String msg = ex.getMessage();
        assertTrue(msg.contains("启动配置校验失败"), msg);
        assertTrue(msg.contains("DB_URL"), msg);
        assertTrue(msg.contains("DB_USERNAME"), msg);
        assertTrue(msg.contains("DB_PASSWORD"), msg);
        assertTrue(msg.contains("JWT_ACCESS_SECRET"), msg);
        assertTrue(msg.contains("ALLOWED_ORIGINS"), msg);
        assertTrue(msg.contains("STORAGE_DRIVER"), msg);
    }

    @Test
    void shortSecretRejected() {
        MockEnvironment env = baseEnv();
        env.setProperty("app.jwt-access-secret", "short");
        AppStartupGuard guard = new AppStartupGuard(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        assertTrue(ex.getMessage().contains("JWT_ACCESS_SECRET 强度不足"), ex.getMessage());
    }

    @Test
    void originWithPathRejected() {
        MockEnvironment env = baseEnv();
        env.setProperty("app.allowed-origins", "http://localhost:5173,https://bad.example.com/app");
        AppStartupGuard guard = new AppStartupGuard(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        assertTrue(ex.getMessage().contains("非法来源"), ex.getMessage());
        assertTrue(ex.getMessage().contains("https://bad.example.com/app"), ex.getMessage());
    }

    @Test
    void s3DriverRequiresS3Config() {
        MockEnvironment env = baseEnv();
        env.setProperty("app.storage-driver", "s3");
        AppStartupGuard guard = new AppStartupGuard(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        assertTrue(ex.getMessage().contains("S3_ENDPOINT"), ex.getMessage());
    }

    @Test
    void badNumericFloors() {
        MockEnvironment env = baseEnv();
        env.setProperty("app.refresh-ttl-days", "0");
        env.setProperty("app.rate-limit-login", "0");
        AppStartupGuard guard = new AppStartupGuard(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        assertTrue(ex.getMessage().contains("REFRESH_TTL_DAYS"), ex.getMessage());
        assertTrue(ex.getMessage().contains("RATE_LIMIT_LOGIN"), ex.getMessage());
    }

    @Test
    void smtpHostWithoutFromRejected() {
        MockEnvironment env = baseEnv();
        env.setProperty("app.smtp.host", "smtp.example.com");
        AppStartupGuard guard = new AppStartupGuard(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        assertTrue(ex.getMessage().contains("SMTP_FROM"), ex.getMessage());
    }

    @Test
    void badThumbWidthsRejected() {
        MockEnvironment env = baseEnv();
        env.setProperty("app.thumb-widths", "abc");
        AppStartupGuard guard = new AppStartupGuard(env);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.postProcessBeanFactory(beanFactory()));
        assertTrue(ex.getMessage().contains("THUMB_WIDTHS"), ex.getMessage());
    }
}
