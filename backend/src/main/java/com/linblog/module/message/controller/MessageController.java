package com.linblog.module.message.controller;

import com.linblog.common.ApiResult;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.message.service.MessageService;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.permission.service.PermissionService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import com.linblog.security.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 私信端点（§5.6，M4）：/api/v1/messages/**。
 * 列表 message.private；建会话/发消息/查消息/清未读/角标仅要求登录且为会话双方。
 */
@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {

    public record CreateConversationRequest(@NotNull Long recipientId,
                                            @NotBlank @Size(max = 2000) String body) {}

    public record SendMessageRequest(@NotBlank @Size(max = 2000) String body) {}

    private final MessageService messageService;
    private final PermissionService permissionService;

    public MessageController(MessageService messageService, PermissionService permissionService) {
        this.messageService = messageService;
        this.permissionService = permissionService;
    }

    @GetMapping("/conversations")
    public ApiResult<PageResult<MessageService.ConversationView>> conversations(@Valid PageQuery query) {
        AuthPrincipal principal = requirePrincipal();
        permissionService.require(principal, PermissionKeys.MESSAGE_PRIVATE);
        return ApiResult.ok(messageService.conversations(principal, query));
    }

    @PostMapping("/conversations")
    public ApiResult<MessageService.DirectMessageView> create(@Valid @RequestBody CreateConversationRequest request,
                                                              HttpServletRequest httpRequest) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(messageService.start(principal, request.recipientId(), request.body(),
                IpUtil.clientIp(httpRequest)));
    }

    @GetMapping("/conversations/{id}")
    public ApiResult<MessageService.MessagePage> messages(@PathVariable long id,
                                                          @RequestParam(required = false) Long before) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(messageService.messages(principal, id, before));
    }

    @PostMapping("/conversations/{id}")
    public ApiResult<MessageService.DirectMessageView> send(@PathVariable long id,
                                                            @Valid @RequestBody SendMessageRequest request,
                                                            HttpServletRequest httpRequest) {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(messageService.send(principal, id, request.body(), IpUtil.clientIp(httpRequest)));
    }

    @PatchMapping("/conversations/{id}/read")
    public ApiResult<Void> markRead(@PathVariable long id) {
        AuthPrincipal principal = requirePrincipal();
        messageService.markRead(principal, id);
        return ApiResult.ok(null);
    }

    @GetMapping("/unread-count")
    public ApiResult<Long> unreadCount() {
        AuthPrincipal principal = requirePrincipal();
        return ApiResult.ok(messageService.unreadCount(principal));
    }

    private AuthPrincipal requirePrincipal() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        return principal;
    }
}
