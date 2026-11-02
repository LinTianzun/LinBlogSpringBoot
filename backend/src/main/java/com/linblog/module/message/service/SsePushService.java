package com.linblog.module.message.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.AuthPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * SSE 实时推送（§5.8/§7.7，M6）：按 userId 注册 SseEmitter，单机内存注册表（并发百人假设）。
 * 事件：message.new（只投递给会话双方，绝不广播）、report.new（comment.moderate 持有者）、
 * heartbeat（30s 具名心跳事件，data: ping，对齐 frontend/src/utils/sse.ts 的事件监听），防代理空闲断连。
 * 投递走专用虚拟线程执行器（异步）：慢客户端不阻塞发消息事务；离线不补推。
 */
@Service
public class SsePushService {

    public static final String EVENT_MESSAGE_NEW = "message.new";
    public static final String EVENT_REPORT_NEW = "report.new";

    /** 在线订阅者：userId + 注册时刻角色快照（report.new 的权限判定走 keysForRole 实时矩阵）。 */
    private record Subscriber(String role, Set<SseEmitter> emitters) {}

    private static final Logger log = LoggerFactory.getLogger(SsePushService.class);
    private static final String HEARTBEAT_FRAME = "event: heartbeat\ndata: ping\n\n";

    private final Map<Long, Subscriber> registry = new ConcurrentHashMap<>();
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public SsePushService(PermissionService permissionService, ObjectMapper objectMapper,
                          @Qualifier("sseExecutor") ExecutorService executor) {
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    /** GET /events：为当前用户注册一条 SSE 连接；0L=不设超时，死连接靠心跳发送失败剔除。 */
    public SseEmitter register(AuthPrincipal principal) {
        SseEmitter emitter = new SseEmitter(0L);
        long uid = principal.uid();
        attach(uid, principal.role(), emitter);
        emitter.onCompletion(() -> detach(uid, emitter));
        emitter.onTimeout(() -> detach(uid, emitter));
        emitter.onError(e -> detach(uid, emitter));
        return emitter;
    }

    /** 当前在线用户数（监控用）。 */
    public int onlineUsers() {
        return registry.size();
    }

    /**
     * 推给指定用户的全部在线连接（§7.7：私信事件只投给会话双方）；返回接收连接数。
     * 用户离线返回 0 且不落任何队列——离线不补推，纯在线提醒。
     */
    public int pushToUser(long userId, String event, Object payload) {
        Subscriber subscriber = registry.get(userId);
        if (subscriber == null || subscriber.emitters().isEmpty()) {
            return 0;
        }
        return submitAll(subscriber.emitters(), event, payload, null);
    }

    /** 推给所有持有指定权限键的在线用户（report.new → comment.moderate）；返回接收连接数。 */
    public int pushToPermission(String permissionKey, String event, Object payload) {
        int receivers = 0;
        for (Map.Entry<Long, Subscriber> entry : registry.entrySet()) {
            if (permissionService.keysForRole(entry.getValue().role()).contains(permissionKey)) {
                receivers += submitAll(entry.getValue().emitters(), event, payload, entry.getKey());
            }
        }
        return receivers;
    }

    /** 30s 心跳保活（§7.7）：comment 行全连接广播；发送失败连接即剔除。 */
    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public void heartbeat() {
        for (Map.Entry<Long, Subscriber> entry : registry.entrySet()) {
            for (SseEmitter emitter : entry.getValue().emitters()) {
                executor.submit(() -> {
                    if (!sendQuietly(emitter, HEARTBEAT_FRAME)) {
                        detach(entry.getKey(), emitter);
                    }
                });
            }
        }
    }

    /** 组帧后交虚拟线程执行器异步投递；返回接收连接数（0=离线静默）。 */
    private int submitAll(Set<SseEmitter> emitters, String event, Object payload, Long ownerUid) {
        String frame = frame(event, payload);
        if (frame == null || emitters.isEmpty()) {
            return 0;
        }
        for (SseEmitter emitter : emitters) {
            executor.submit(() -> {
                if (!sendQuietly(emitter, frame) && ownerUid != null) {
                    detach(ownerUid, emitter);
                }
            });
        }
        return emitters.size();
    }

    private String frame(String event, Object payload) {
        String json;
        try {
            json = payload == null ? "" : objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("sse payload serialize failed, event={}", event, e);
            return null;
        }
        return "event: " + event + "\ndata: " + json + "\n\n";
    }

    private boolean sendQuietly(SseEmitter emitter, String frame) {
        try {
            emitter.send(frame, MediaType.TEXT_EVENT_STREAM);
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    /** package-private 供测试注入 fake emitter。 */
    void attach(long userId, String role, SseEmitter emitter) {
        registry.compute(userId, (k, existing) -> {
            if (existing == null) {
                Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();
                emitters.add(emitter);
                return new Subscriber(role, emitters);
            }
            existing.emitters().add(emitter);
            return existing;
        });
    }

    private void detach(long userId, SseEmitter emitter) {
        registry.computeIfPresent(userId, (k, subscriber) -> {
            subscriber.emitters().remove(emitter);
            return subscriber.emitters().isEmpty() ? null : subscriber;
        });
    }
}
