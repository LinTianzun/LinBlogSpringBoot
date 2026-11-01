package com.linblog.security;

import com.linblog.support.TestAppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void loginBucketAllowsConfiguredRateThenBlocks() {
        // rateLimitLogin=2（TestAppProperties 默认）
        RateLimiter limiter = new RateLimiter(TestAppProperties.defaults());
        assertTrue(limiter.tryLogin("1.2.3.4"));
        assertTrue(limiter.tryLogin("1.2.3.4"));
        assertFalse(limiter.tryLogin("1.2.3.4"), "超过 RATE_LIMIT_LOGIN=2 后拒绝");
        // 不同 IP 独立计数
        assertTrue(limiter.tryLogin("5.6.7.8"));
    }

    @Test
    void resetBucketIndependent() {
        RateLimiter limiter = new RateLimiter(TestAppProperties.defaults());
        assertTrue(limiter.tryReset("1.2.3.4"));
        assertTrue(limiter.tryReset("1.2.3.4"));
        assertTrue(limiter.tryReset("1.2.3.4"), "RATE_LIMIT_RESET=3 > 已用 2");
        assertFalse(limiter.tryReset("1.2.3.4"));
    }

    @Test
    void customSmallRatesHonored() {
        RateLimiter limiter = new RateLimiter(TestAppProperties.of(new Object[]{19, 1, 1}));
        assertTrue(limiter.tryLogin("9.9.9.9"));
        assertFalse(limiter.tryLogin("9.9.9.9"));
    }
}
