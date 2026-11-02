package com.linblog.module.message.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 私信（§5.6，M4）：会话按 (min,max) 配对唯一；会话双方可读发；SSE 推送属 M6 不做。
 * 端点权限：列表 message.private，其余仅要求登录且为会话双方。
 */
@Service
public class MessageService {

    /** 会话消息单页条数（§5.6 ?before= 游标倒查）。 */
    public static final int PAGE_SIZE = 20;
    public static final int BODY_MAX = 2000;

    public record Peer(Long id, String username, String nickname) {}

    /** 对齐前端 ConversationDTO：peer + lastMessageAt + lastMessagePreview + unread。 */
    public record ConversationView(Long id, Peer peer, LocalDateTime lastMessageAt,
                                   String lastMessagePreview, Integer unread) {}

    /** 对齐前端 MessageDTO。 */
    public record DirectMessageView(Long id, Long conversationId, Long senderId, String body,
                                    LocalDateTime readAt, LocalDateTime createdAt) {}

    /** 游标分页结果：records 正序，hasMore 提示还可再往前翻。 */
    public record MessagePage(List<DirectMessageView> records, boolean hasMore) {}

    /** SSE message.new 载荷（§5.8）：不含正文——接收端按会话拉取，避免重复渲染与正文外泄面。 */
    public record MessagePushPayload(long conversationId, long messageId, long senderId, long unread) {}

    private final ConversationMapper conversationMapper;
    private final DirectMessageMapper directMessageMapper;
    private final UserMapper userMapper;
    private final SystemLogService systemLogService;
    private final SsePushService ssePushService;

    public MessageService(ConversationMapper conversationMapper,
                          DirectMessageMapper directMessageMapper,
                          UserMapper userMapper,
                          SystemLogService systemLogService,
                          SsePushService ssePushService) {
        this.conversationMapper = conversationMapper;
        this.directMessageMapper = directMessageMapper;
        this.userMapper = userMapper;
        this.systemLogService = systemLogService;
        this.ssePushService = ssePushService;
    }

    /** GET /messages/conversations（message.private）：按 last_message_at 倒序，含未读数与对方信息。 */
    public PageResult<ConversationView> conversations(AuthPrincipal principal, PageQuery query) {
        Long uid = principal.uid();
        Page<Conversation> page = conversationMapper.selectPage(new Page<>(query.getPage(), query.getPageSize()),
                new LambdaQueryWrapper<Conversation>()
                        .and(w -> w.eq(Conversation::getUserAId, uid).or().eq(Conversation::getUserBId, uid))
                        .orderByDesc(Conversation::getLastMessageAt)
                        .orderByDesc(Conversation::getId));
        List<Conversation> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.of(List.of(), page.getTotal());
        }

        Set<Long> peerIds = new HashSet<>();
        for (Conversation row : rows) {
            peerIds.add(peerIdOf(row, uid));
        }
        Map<Long, User> users = new HashMap<>();
        for (User user : userMapper.selectBatchIds(peerIds)) {
            users.put(user.getId(), user);
        }

        Set<Long> convIds = new HashSet<>();
        for (Conversation row : rows) {
            convIds.add(row.getId());
        }
        Map<Long, DirectMessage> latestByConv = new HashMap<>();
        for (DirectMessage message : directMessageMapper.selectList(new LambdaQueryWrapper<DirectMessage>()
                .in(DirectMessage::getConversationId, convIds)
                .orderByDesc(DirectMessage::getId))) {
            latestByConv.putIfAbsent(message.getConversationId(), message);
        }

        List<ConversationView> views = new ArrayList<>(rows.size());
        for (Conversation row : rows) {
            User peer = users.get(peerIdOf(row, uid));
            DirectMessage latest = latestByConv.get(row.getId());
            views.add(new ConversationView(row.getId(),
                    new Peer(peerIdOf(row, uid),
                            peer == null ? null : peer.getUsername(),
                            peer == null ? null : peer.getNickname()),
                    row.getLastMessageAt(),
                    latest == null ? null : latest.getBody(),
                    userAOf(row, uid) ? row.getAUnread() : row.getBUnread()));
        }
        return new PageResult<>(views, page.getTotal());
    }

    /** GET /messages/conversations/{id}（会话双方）：?before= 游标倒查 20 条，返回正序。 */
    public MessagePage messages(AuthPrincipal principal, long conversationId, Long before) {
        Conversation conversation = requireParticipant(principal, conversationId);
        List<DirectMessage> rows = directMessageMapper.selectList(new LambdaQueryWrapper<DirectMessage>()
                .eq(DirectMessage::getConversationId, conversation.getId())
                .lt(before != null, DirectMessage::getId, before)
                .orderByDesc(DirectMessage::getId)
                .last("LIMIT " + (PAGE_SIZE + 1)));
        boolean hasMore = rows.size() > PAGE_SIZE;
        if (hasMore) {
            rows = rows.subList(0, PAGE_SIZE);
        }
        java.util.Collections.reverse(rows);
        List<DirectMessageView> views = new ArrayList<>(rows.size());
        for (DirectMessage message : rows) {
            views.add(toView(message));
        }
        return new MessagePage(views, hasMore);
    }

    /** POST /messages/conversations（登录）：{recipientId, body}；无会话则建（配对并发靠 uk_pair 兜底）。 */
    @Transactional
    public DirectMessageView start(AuthPrincipal principal, Long recipientId, String body, String ip) {
        if (recipientId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "recipientId 不能为空");
        }
        long uid = principal.uid();
        if (recipientId == uid) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能给自己发私信");
        }
        if (userMapper.selectById(recipientId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        String text = requireBody(body);
        Conversation conversation = findOrCreatePair(Math.min(uid, recipientId), Math.max(uid, recipientId));
        return appendMessage(conversation, uid, text, ip, "to:" + recipientId, "create");
    }

    /** POST /messages/conversations/{id}（会话双方）：发送。 */
    @Transactional
    public DirectMessageView send(AuthPrincipal principal, long conversationId, String body, String ip) {
        Conversation conversation = requireParticipant(principal, conversationId);
        String text = requireBody(body);
        return appendMessage(conversation, principal.uid(), text, ip, null, "send");
    }

    /** PATCH /messages/conversations/{id}/read（会话双方）：清自己的未读，并落对方消息的 read_at。 */
    @Transactional
    public void markRead(AuthPrincipal principal, long conversationId) {
        Conversation conversation = requireParticipant(principal, conversationId);
        long uid = principal.uid();
        if (userAOf(conversation, uid)) {
            conversationMapper.clearAUnread(conversation.getId());
        } else {
            conversationMapper.clearBUnread(conversation.getId());
        }
        directMessageMapper.markConversationRead(conversation.getId(), uid, LocalDateTime.now());
    }

    /** GET /messages/unread-count（登录）：跨会话未读总数（角标）。 */
    public long unreadCount(AuthPrincipal principal) {
        return conversationMapper.sumUnreadFor(principal.uid());
    }

    private Conversation findOrCreatePair(long userAId, long userBId) {
        Conversation conversation = selectPair(userAId, userBId);
        if (conversation != null) {
            return conversation;
        }
        Conversation created = new Conversation();
        created.setUserAId(userAId);
        created.setUserBId(userBId);
        created.setAUnread(0);
        created.setBUnread(0);
        try {
            conversationMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            conversation = selectPair(userAId, userBId);
            if (conversation == null) {
                throw new BizException(ErrorCode.INTERNAL, "会话创建失败");
            }
            return conversation;
        }
    }

    private Conversation selectPair(long userAId, long userBId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserAId, userAId)
                .eq(Conversation::getUserBId, userBId));
    }

    private DirectMessageView appendMessage(Conversation conversation, long senderId, String body,
                                            String ip, String detail, String action) {
        DirectMessage message = new DirectMessage();
        message.setConversationId(conversation.getId());
        message.setSenderId(senderId);
        message.setBody(body);
        message.setCreatedAt(LocalDateTime.now());
        directMessageMapper.insert(message);
        if (userAOf(conversation, senderId)) {
            conversationMapper.incrBUnread(conversation.getId());
        } else {
            conversationMapper.incrAUnread(conversation.getId());
        }
        conversationMapper.touchLastMessage(conversation.getId(), message.getCreatedAt());
        systemLogService.record(senderId, "message", action, "conversation", conversation.getId(), detail, ip);
        notifyRecipient(conversation, senderId, message.getId());
        return toView(message);
    }

    /** SSE message.new 只推对方（§7.7：仅会话双方、绝不广播）；离线静默，不补推。 */
    private void notifyRecipient(Conversation conversation, long senderId, long messageId) {
        Long recipientId = userAOf(conversation, senderId) ? conversation.getUserBId() : conversation.getUserAId();
        if (recipientId == null) {
            return;
        }
        ssePushService.pushToUser(recipientId, SsePushService.EVENT_MESSAGE_NEW,
                new MessagePushPayload(conversation.getId(), messageId, senderId,
                        conversationMapper.sumUnreadFor(recipientId)));
    }

    private DirectMessageView toView(DirectMessage message) {
        return new DirectMessageView(message.getId(), message.getConversationId(), message.getSenderId(),
                message.getBody(), message.getReadAt(), message.getCreatedAt());
    }

    private String requireBody(String raw) {
        String body = raw == null ? "" : raw.trim();
        if (body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "消息内容不能为空");
        }
        if (body.length() > BODY_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "消息内容过长");
        }
        return body;
    }

    /** 会话不存在或非双方一律 40400（防会话 id 探测）。 */
    private Conversation requireParticipant(AuthPrincipal principal, long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        long uid = principal.uid();
        if (conversation == null || (!userAOf(conversation, uid) && !userBOf(conversation, uid))) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    private long peerIdOf(Conversation conversation, long uid) {
        return userAOf(conversation, uid) ? conversation.getUserBId() : conversation.getUserAId();
    }

    private boolean userAOf(Conversation conversation, long uid) {
        return conversation.getUserAId() != null && conversation.getUserAId() == uid;
    }

    private boolean userBOf(Conversation conversation, long uid) {
        return conversation.getUserBId() != null && conversation.getUserBId() == uid;
    }
}
