package com.linblog.module.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.content.dto.LabelRequest;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.mapper.ContentLabelMapper;
import com.linblog.module.content.mapper.LabelMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标签管理（§5.3）：GET /api/v1/labels 公开；写操作 label.manage。
 */
@Service
public class LabelService {

    private final LabelMapper labelMapper;
    private final ContentLabelMapper contentLabelMapper;
    private final SystemLogService systemLogService;

    public LabelService(LabelMapper labelMapper, ContentLabelMapper contentLabelMapper,
                        SystemLogService systemLogService) {
        this.labelMapper = labelMapper;
        this.contentLabelMapper = contentLabelMapper;
        this.systemLogService = systemLogService;
    }

    public List<Label> listAll() {
        return labelMapper.selectList(new LambdaQueryWrapper<Label>().orderByAsc(Label::getId));
    }

    @Transactional
    public Label create(LabelRequest request) {
        requirePermission();
        String name = request.name().trim();
        Label label = new Label();
        label.setName(name);
        try {
            labelMapper.insert(label);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "标签已存在");
        }
        log("create", label.getId(), name);
        return label;
    }

    @Transactional
    public Label update(Long id, LabelRequest request) {
        requirePermission();
        Label label = labelMapper.selectById(id);
        if (label == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "标签不存在");
        }
        String name = request.name().trim();
        label.setName(name);
        try {
            labelMapper.updateById(label);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "标签已存在");
        }
        log("update", id, name);
        return label;
    }

    @Transactional
    public void delete(Long id) {
        requirePermission();
        Label label = labelMapper.selectById(id);
        if (label == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "标签不存在");
        }
        if (contentLabelMapper.selectCount(new LambdaQueryWrapper<com.linblog.module.content.entity.ContentLabel>()
                .eq(com.linblog.module.content.entity.ContentLabel::getLabelId, id)) > 0) {
            throw new BizException(ErrorCode.CONFLICT, "标签仍被内容引用，无法删除");
        }
        labelMapper.deleteById(id);
        log("delete", id, label.getName());
    }

    private void requirePermission() {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.has(com.linblog.module.permission.PermissionKeys.LABEL_MANAGE)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限执行该操作");
        }
    }

    private void log(String action, Long targetId, String detail) {
        AuthPrincipal principal = CurrentUser.get();
        systemLogService.record(principal == null ? null : principal.uid(), "label", action,
                "label", targetId, detail, null);
    }
}
