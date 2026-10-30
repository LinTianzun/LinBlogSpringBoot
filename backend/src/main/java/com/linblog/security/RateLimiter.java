package com.linblog.security;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.linblog.config.AppProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * IP 维限流（§4.3 RATE_LIMIT_*）：次/分钟，贪婪补充。
 * login/register 共用 RATE_LIMIT_LOGIN；reset-request/reset 共用 RATE_LIMIT_RESET。
 */
@Component
public class RateLimiter {

    private final LoadingCache<String, Bucket> loginBuckets;
    private final LoadingCache<String, Bucket> resetBuckets;

    public RateLimiter(AppProperties props) {
        this.loginBuckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(2))
                .maximumSize(10_000)
                .build(ip -> newBucket(props.rateLimitLogin()));
        this.resetBuckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofHours(2))
                .maximumSize(10_000)
                .build(ip -> newBucket(props.rateLimitReset()));
    }

    private static Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute)
                        .refillGreedy(perMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    public boolean tryLogin(String ip) {
        return loginBuckets.get(ip).tryConsume(1);
    }

    public boolean tryReset(String ip) {
        return resetBuckets.get(ip).tryConsume(1);
    }
}
