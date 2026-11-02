package com.linblog.module.message.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.message.entity.Conversation;
import com.linblog.module.message.entity.DirectMessage;
import com.linblog.module.message.mapper.ConversationMapper;
import com.linblog.module.message.mapper.DirectMessageMapper;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 私信服务（§5.6）：配对会话、游标分页、未读数、越权 40400 防探测，
 * 以及 M6 SSE：message.new 只推对方，载荷不含正文。
 */
class MessageServiceTest {

    private static final AuthPrincipal ALICE = new AuthPrincipal(1L, "alice", "editor", false, Set.of());
    private static final AuthPrincipal BOB = new AuthPrincipal(2L, "bob", "editor", false, Set.of());
    private static final AuthPrincipal CAROL = new AuthPrincipal(3L, "carol", "editor", false, Set.of());

    private ConversationMapper conversationMapper;
    private DirectMessageMapper directMessageMapper;
    private UserMapper userMapper;
    private SystemLogService systemLogService;
    private SsePushService ssePushService;
    private MessageService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        directMessageMapper = mock(DirectMessageMapper.class);
        userMapper = mock(UserMapper.class);
        systemLogService = mock(SystemLogService.class);
        ssePushService = mock(SsePushService.class);
        service = new MessageService(conversationMapper, directMessageMapper, userMapper,
                systemLogService, ssePushService);
    }

    private static Conversation conv(long id, long userA, long userB, int aUnread, int bUnread) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserAId(userA);
        conversation.setUserBId(userB);
        conversation.setAUnread(aUnread);
        conversation.setBUnread(bUnread);
        conversation.setLastMessageAt(LocalDateTime.of(2026, 5, 1, 12, 0));
        return conversation;
    }

    private static DirectMessage msg(long id, long conversationId, long senderId, String body) {
        DirectMessage message = new DirectMessage();
        message.setId(id);
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setBody(body);
        message.setCreatedAt(LocalDateTime.of(2026, 5, 1, 12, 0));
        return message;
    }

    private static User user(long id, String username, String nickname) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        return user;
    }

    private void stubInsertIds() {
        when(conversationMapper.insert(any(Conversation.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Conversation.class).setId(10L);
            return 1;
        });
        when(directMessageMapper.insert(any(DirectMessage.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DirectMessage.class).setId(100L);
            return 1;
        });
    }

    @Test
    void startCreatesPairAndPushesRecipientOnly() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "bob", "bob_n"));
        when(conversationMapper.selectOne(any())).thenReturn(null);
        stubInsertIds();
        when(conversationMapper.sumUnreadFor(2L)).thenReturn(1L);

        service.start(ALICE, 2L, "  hi  ", "127.0.0.1");

        ArgumentCaptor<DirectMessage> message = ArgumentCaptor.forClass(DirectMessage.class);
        verify(directMessageMapper).insert(message.capture());
        assertEquals(10L, message.getValue().getConversationId());
        assertEquals("hi", message.getValue().getBody());
        verify(conversationMapper).incrBUnread(10L);
        verify(conversationMapper).touchLastMessage(eq(10L), any(LocalDateTime.class));
        verify(systemLogService).record(1L, "message", "create", "conversation", 10L, "to:2", "127.0.0.1");

        ArgumentCaptor<MessageService.MessagePushPayload> push =
                ArgumentCaptor.forClass(MessageService.MessagePushPayload.class);
        verify(ssePushService).pushToUser(eq(2L), eq(SsePushService.EVENT_MESSAGE_NEW), push.capture());
        assertEquals(10, push.getValue().conversationId());
        assertEquals(100, push.getValue().messageId());
        assertEquals(1, push.getValue().senderId());
        assertEquals(1, push.getValue().unread());
        verify(ssePushService, never()).pushToUser(eq(1L), any(), any());
    }

    @Test
    void startReusesExistingPair() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "bob", "bob_n"));
        when(conversationMapper.selectOne(any())).thenReturn(conv(10, 1, 2, 0, 0));
        when(directMessageMapper.insert(any(DirectMessage.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DirectMessage.class).setId(100L);
            return 1;
        });

        assertEquals(10L, service.start(ALICE, 2L, "hi", "ip").conversationId());
        verify(conversationMapper, never()).insert(any(Conversation.class));
    }

    @Test
    void startConcurrentDuplicatePairFallsBackToExisting() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "bob", "bob_n"));
        when(conversationMapper.selectOne(any())).thenReturn(null, conv(10, 1, 2, 0, 0));
        when(conversationMapper.insert(any(Conversation.class)))
                .thenThrow(new DuplicateKeyException("uk_pair"));
        when(directMessageMapper.insert(any(DirectMessage.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DirectMessage.class).setId(100L);
            return 1;
        });

        assertEquals(10L, service.start(ALICE, 2L, "hi", "ip").conversationId());
    }

    @Test
    void startConcurrentDuplicatePairStillMissingFails() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "bob", "bob_n"));
        when(conversationMapper.selectOne(any())).thenReturn(null);
        when(conversationMapper.insert(any(Conversation.class)))
                .thenThrow(new DuplicateKeyException("uk_pair"));

        BizException exception = assertThrows(BizException.class,
                () -> service.start(ALICE, 2L, "hi", "ip"));
        assertEquals(ErrorCode.INTERNAL, exception.getErrorCode());
    }

    @Test
    void startRejectsSelfRecipientAndNull() {
        assertEquals("不能给自己发私信",
                assertThrows(BizException.class, () -> service.start(ALICE, 1L, "hi", "ip")).getMessage());
        assertEquals("recipientId 不能为空",
                assertThrows(BizException.class, () -> service.start(ALICE, null, "hi", "ip")).getMessage());
        verify(conversationMapper, never()).selectOne(any());
    }

    @Test
    void startRejectsUnknownRecipient() {
        when(userMapper.selectById(9L)).thenReturn(null);

        assertEquals("用户不存在",
                assertThrows(BizException.class, () -> service.start(ALICE, 9L, "hi", "ip")).getMessage());
        verify(conversationMapper, never()).selectOne(any());
    }

    @Test
    void startRejectsEmptyOrTooLongBody() {
        when(userMapper.selectById(2L)).thenReturn(user(2L, "bob", "bob_n"));

        assertEquals("消息内容不能为空",
                assertThrows(BizException.class, () -> service.start(ALICE, 2L, "   ", "ip")).getMessage());
        assertEquals("消息内容过长", assertThrows(BizException.class,
                () -> service.start(ALICE, 2L, "长".repeat(2001), "ip")).getMessage());
        verify(directMessageMapper, never()).insert(any(DirectMessage.class));
    }

    @Test
    void sendRequiresParticipantAndRejectsOutsider() {
        when(conversationMapper.selectById(10L)).thenReturn(null);
        assertEquals("会话不存在",
                assertThrows(BizException.class, () -> service.send(ALICE, 10L, "hi", "ip")).getMessage());

        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 0, 0));
        assertEquals("会话不存在",
                assertThrows(BizException.class, () -> service.send(CAROL, 10L, "hi", "ip")).getMessage());
    }

    @Test
    void sendByUserBIncrementsAUnreadAndPushesUserA() {
        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 0, 0));
        when(directMessageMapper.insert(any(DirectMessage.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, DirectMessage.class).setId(101L);
            return 1;
        });
        when(conversationMapper.sumUnreadFor(1L)).thenReturn(3L);

        service.send(BOB, 10L, "yo", "ip");

        verify(conversationMapper).incrAUnread(10L);
        ArgumentCaptor<MessageService.MessagePushPayload> push =
                ArgumentCaptor.forClass(MessageService.MessagePushPayload.class);
        verify(ssePushService).pushToUser(eq(1L), eq(SsePushService.EVENT_MESSAGE_NEW), push.capture());
        assertEquals(101, push.getValue().messageId());
        assertEquals(3, push.getValue().unread());
    }

    @Test
    void markReadClearsOwnSideAndMarksPeerMessagesRead() {
        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 2, 0));

        service.markRead(ALICE, 10L);

        verify(conversationMapper).clearAUnread(10L);
        verify(conversationMapper, never()).clearBUnread(anyLong());
        verify(directMessageMapper).markConversationRead(eq(10L), eq(1L), any(LocalDateTime.class));

        service.markRead(BOB, 10L);
        verify(conversationMapper).clearBUnread(10L);
    }

    @Test
    void markReadRejectsOutsider() {
        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 2, 0));

        assertEquals("会话不存在",
                assertThrows(BizException.class, () -> service.markRead(CAROL, 10L)).getMessage());
        verify(conversationMapper, never()).clearAUnread(anyLong());
    }

    @Test
    void unreadCountDelegatesToMapper() {
        when(conversationMapper.sumUnreadFor(1L)).thenReturn(7L);
        assertEquals(7L, service.unreadCount(ALICE));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void conversationsAssemblePeerLatestMessageAndUnread() {
        PageQuery query = new PageQuery();
        when(conversationMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Conversation> page = invocation.getArgument(0);
            page.setRecords(List.of(conv(10, 1, 2, 4, 1), conv(11, 3, 1, 0, 5)));
            page.setTotal(2);
            return page;
        });
        when(userMapper.selectBatchIds(any())).thenReturn(
                List.of(user(2L, "bob", "bob_n"), user(3L, "carol", "c_n")));
        when(directMessageMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                msg(60, 11, 3, "latest11"), msg(50, 10, 2, "latest10"), msg(40, 10, 2, "old")));

        PageResult<MessageService.ConversationView> result = service.conversations(ALICE, query);

        assertEquals(2, result.total());
        MessageService.ConversationView first = result.records().get(0);
        assertEquals(10L, first.id());
        assertEquals("bob", first.peer().username());
        assertEquals("bob_n", first.peer().nickname());
        assertEquals("latest10", first.lastMessagePreview());
        assertEquals(4, first.unread());
        MessageService.ConversationView second = result.records().get(1);
        assertEquals(11L, second.id());
        assertEquals("carol", second.peer().username());
        assertEquals("latest11", second.lastMessagePreview());
        assertEquals(5, second.unread());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void conversationsEmptyPageSkipsEnrichment() {
        when(conversationMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<Conversation> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0);
            return page;
        });

        PageResult<MessageService.ConversationView> result = service.conversations(ALICE, new PageQuery());

        assertEquals(0, result.total());
        assertTrue(result.records().isEmpty());
        verify(userMapper, never()).selectBatchIds(any());
        verify(directMessageMapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void messagesCursorPageTrimsExtraAndReturnsAscending() {
        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 0, 0));
        List<DirectMessage> rows = new java.util.ArrayList<>();
        for (long id = 21; id >= 1; id--) {
            rows.add(msg(id, 10, 2, "body-" + id));
        }
        when(directMessageMapper.selectList(any(Wrapper.class))).thenReturn(rows);

        MessageService.MessagePage result = service.messages(ALICE, 10L, 22L);

        assertTrue(result.hasMore());
        assertEquals(MessageService.PAGE_SIZE, result.records().size());
        assertEquals(2L, result.records().get(0).id());
        assertEquals(21L, result.records().get(result.records().size() - 1).id());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void messagesLastPageHasNoMore() {
        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 0, 0));
        List<DirectMessage> rows = new java.util.ArrayList<>();
        for (long id = 20; id >= 1; id--) {
            rows.add(msg(id, 10, 2, "body-" + id));
        }
        when(directMessageMapper.selectList(any(Wrapper.class))).thenReturn(rows);

        MessageService.MessagePage result = service.messages(ALICE, 10L, null);

        assertTrue(!result.hasMore());
        assertEquals(1L, result.records().get(0).id());
        assertEquals(20L, result.records().get(19).id());
    }

    @Test
    void messagesRejectsOutsider() {
        when(conversationMapper.selectById(10L)).thenReturn(conv(10, 1, 2, 0, 0));

        assertEquals("会话不存在",
                assertThrows(BizException.class, () -> service.messages(CAROL, 10L, null)).getMessage());
    }
}
