package com.linblog.module.message.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SSE 推送服务：只推目标用户（绝不广播）、按权限过滤、心跳为具名事件并对死连接剔除（§5.8/§7.7）。
 * 执行器用 mock 内联执行，使异步投递在断言前同步完成。
 */
class SsePushServiceTest {

    private PermissionService permissionService;
    private ExecutorService executor;
    private SsePushService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        permissionService = mock(PermissionService.class);
        executor = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).submit(any(Runnable.class));
        service = new SsePushService(permissionService, new ObjectMapper(), executor);
    }

    private static MessageService.MessagePushPayload payload() {
        return new MessageService.MessagePushPayload(9, 3, 1, 2);
    }

    private static String sentFrame(SseEmitter emitter) throws IOException {
        ArgumentCaptor<Object> frames = ArgumentCaptor.forClass(Object.class);
        verify(emitter, times(1)).send(frames.capture(), eq(MediaType.TEXT_EVENT_STREAM));
        return (String) frames.getValue();
    }

    @Test
    void offlineUserReceivesNothing() {
        SseEmitter emitter = mock(SseEmitter.class);
        service.attach(7L, "editor", emitter);

        assertEquals(0, service.pushToUser(9L, SsePushService.EVENT_MESSAGE_NEW, payload()));
        verifyNoInteractions(emitter);
    }

    @Test
    void pushToUserDeliversNamedEventToEveryConnectionOfTargetUser() throws IOException {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        service.attach(7L, "editor", first);
        service.attach(7L, "editor", second);

        assertEquals(2, service.pushToUser(7L, SsePushService.EVENT_MESSAGE_NEW, payload()));

        String frame = sentFrame(first);
        assertTrue(frame.contains("event: message.new"));
        assertTrue(frame.contains("\"conversationId\":9,\"messageId\":3,\"senderId\":1,\"unread\":2"));
        String other = sentFrame(second);
        assertTrue(other.contains("event: message.new"));
    }

    @Test
    void pushOnlyReachesTargetUserNotOthers() throws IOException {
        SseEmitter target = mock(SseEmitter.class);
        SseEmitter bystander = mock(SseEmitter.class);
        service.attach(7L, "editor", target);
        service.attach(8L, "editor", bystander);

        assertEquals(1, service.pushToUser(7L, SsePushService.EVENT_MESSAGE_NEW, payload()));

        verify(target, times(1)).send(any(), eq(MediaType.TEXT_EVENT_STREAM));
        verify(bystander, never()).send(any(), any());
    }

    @Test
    void pushToPermissionFiltersByRoleMatrix() throws IOException {
        SseEmitter viewer = mock(SseEmitter.class);
        SseEmitter admin = mock(SseEmitter.class);
        SseEmitter superAdmin = mock(SseEmitter.class);
        service.attach(7L, "viewer", viewer);
        service.attach(8L, "admin", admin);
        service.attach(9L, "super_admin", superAdmin);
        when(permissionService.keysForRole("viewer")).thenReturn(Set.of());
        when(permissionService.keysForRole("admin")).thenReturn(Set.of("comment.moderate"));
        when(permissionService.keysForRole("super_admin")).thenReturn(PermissionKeys.ALL_KEYS);

        int receivers = service.pushToPermission("comment.moderate", SsePushService.EVENT_REPORT_NEW,
                Map.of("commentId", 5L, "reason", "spam"));

        assertEquals(2, receivers);
        String adminFrame = sentFrame(admin);
        assertTrue(adminFrame.contains("event: report.new"));
        assertTrue(adminFrame.contains("\"commentId\":5"));
        assertTrue(sentFrame(superAdmin).contains("event: report.new"));
        verify(viewer, never()).send(any(), any());
    }

    @Test
    void heartbeatBroadcastsNamedEventAndPrunesDeadConnections() throws IOException {
        SseEmitter alive = mock(SseEmitter.class);
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("gone")).when(dead).send(any(), any());
        service.attach(7L, "editor", alive);
        service.attach(8L, "editor", dead);

        service.heartbeat();

        verify(alive, times(1)).send("event: heartbeat\ndata: ping\n\n", MediaType.TEXT_EVENT_STREAM);
        verify(dead, times(1)).send(any(), any());
        assertEquals(1, service.onlineUsers());
        assertEquals(0, service.pushToUser(8L, SsePushService.EVENT_MESSAGE_NEW, payload()));
        assertEquals(1, service.pushToUser(7L, SsePushService.EVENT_MESSAGE_NEW, payload()));
    }

    @Test
    void heartbeatRetainsUserWhileAtLeastOneConnectionLives() throws IOException {
        SseEmitter alive = mock(SseEmitter.class);
        SseEmitter dead = mock(SseEmitter.class);
        doThrow(new IOException("gone")).when(dead).send(any(), any());
        service.attach(7L, "editor", alive);
        service.attach(7L, "editor", dead);

        service.heartbeat();

        assertEquals(1, service.onlineUsers());
        assertEquals(1, service.pushToUser(7L, SsePushService.EVENT_MESSAGE_NEW, payload()));
    }

    @Test
    void payloadSerializationFailureSkipsDelivery() throws JsonProcessingException {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        SsePushService broken = new SsePushService(permissionService, failing, executor);
        SseEmitter emitter = mock(SseEmitter.class);
        broken.attach(7L, "editor", emitter);

        assertEquals(0, broken.pushToUser(7L, SsePushService.EVENT_MESSAGE_NEW, Map.of("x", 1)));
        verifyNoInteractions(emitter);
    }

    @Test
    void registerSubscribesCurrentUser() {
        AuthPrincipal principal = new AuthPrincipal(7L, "alice", "editor", false, Set.of());

        SseEmitter emitter = service.register(principal);

        assertNotNull(emitter);
        assertEquals(1, service.onlineUsers());
        assertEquals(1, service.pushToUser(7L, SsePushService.EVENT_MESSAGE_NEW, payload()));
    }
}
