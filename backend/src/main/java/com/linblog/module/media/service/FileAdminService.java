package com.linblog.module.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.media.dto.FileAdminView;
import com.linblog.module.media.dto.StorageStatus;
import com.linblog.module.media.entity.FileAttachment;
import com.linblog.module.media.mapper.FileAttachmentMapper;
import com.linblog.module.permission.PermissionKeys;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台文件管理（§5.7，M4）：列表/状态筛选、storage-status 汇总、trash/restore/purge。
 * files 表属全局管理资源（无归属语义），缺权限键 → 40300（§7.3 管理端口径）。
 */
@Service
public class FileAdminService {

    /** 备份区配额：§5.7/§7.9 固定 2GiB（未开放配置）。 */
    public static final long QUOTA_BYTES = 2L * 1024 * 1024 * 1024;
    /** 告警阈值：备份区用量 ≥80%。 */
    public static final double ALERT_RATIO = 0.8;

    private static final Set<String> LISTABLE_STATES = Set.of("active", "trash", "purged");

    private final FileAttachmentMapper fileAttachmentMapper;
    private final UserMapper userMapper;
    private final SystemLogService systemLogService;
    private final StorageDriver storageDriver;

    public FileAdminService(FileAttachmentMapper fileAttachmentMapper, UserMapper userMapper,
                            SystemLogService systemLogService, StorageDriver storageDriver) {
        this.fileAttachmentMapper = fileAttachmentMapper;
        this.userMapper = userMapper;
        this.systemLogService = systemLogService;
        this.storageDriver = storageDriver;
    }

    public PageResult<FileAdminView> page(PageQuery query, String state, AuthPrincipal principal) {
        requireLogin(principal);
        requirePermission(principal, PermissionKeys.FILE_VIEW);
        LambdaQueryWrapper<FileAttachment> wrapper = new LambdaQueryWrapper<>();
        if (state != null && !state.isBlank()) {
            if (!LISTABLE_STATES.contains(state)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "state 取值不合法");
            }
            wrapper.eq(FileAttachment::getState, state);
        }
        wrapper.orderByDesc(FileAttachment::getId);
        Page<FileAttachment> page = new Page<>(query.getPage(), query.getPageSize());
        fileAttachmentMapper.selectPage(page, wrapper);
        return PageResult.of(toViews(page.getRecords()), page.getTotal());
    }

    public StorageStatus storageStatus(AuthPrincipal principal) {
        requireLogin(principal);
        requirePermission(principal, PermissionKeys.FILE_VIEW);
        long primaryBytes = 0;
        long primaryCount = 0;
        long backupBytes = 0;
        long backupCount = 0;
        for (Map<String, Object> row : fileAttachmentMapper.selectStateSummary()) {
            String state = String.valueOf(row.get("state"));
            long bytes = ((Number) row.get("total_bytes")).longValue();
            long cnt = ((Number) row.get("cnt")).longValue();
            if ("active".equals(state)) {
                primaryBytes = bytes;
                primaryCount = cnt;
            } else if ("trash".equals(state)) {
                backupBytes = bytes;
                backupCount = cnt;
            }
        }
        double percent = QUOTA_BYTES == 0 ? 0 : backupBytes * 100.0 / QUOTA_BYTES;
        return new StorageStatus(primaryBytes, primaryCount, backupBytes, backupCount,
                QUOTA_BYTES, percent, backupBytes >= QUOTA_BYTES * ALERT_RATIO);
    }

    public void trash(Long id, AuthPrincipal principal, String ip) {
        requireLogin(principal);
        requirePermission(principal, PermissionKeys.FILE_DELETE);
        FileAttachment file = mustGet(id);
        if (!"active".equals(file.getState())) {
            throw new BizException(ErrorCode.CONFLICT, "仅可用文件可移入备份区");
        }
        file.setState("trash");
        file.setTrashedAt(LocalDateTime.now());
        fileAttachmentMapper.updateById(file);
        systemLogService.record(principal.uid(), "file", "trash", "file", id, file.getStorageKey(), ip);
    }

    public void restore(Long id, AuthPrincipal principal, String ip) {
        requireLogin(principal);
        requirePermission(principal, PermissionKeys.FILE_DELETE);
        FileAttachment file = mustGet(id);
        if (!"trash".equals(file.getState())) {
            throw new BizException(ErrorCode.CONFLICT, "文件不在备份区");
        }
        file.setState("active");
        file.setTrashedAt(null);
        fileAttachmentMapper.updateById(file);
        systemLogService.record(principal.uid(), "file", "restore", "file", id, file.getStorageKey(), ip);
    }

    public void purge(Long id, AuthPrincipal principal, String ip) {
        requireLogin(principal);
        requirePermission(principal, PermissionKeys.FILE_DELETE);
        FileAttachment file = mustGet(id);
        if (!"trash".equals(file.getState())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅备份区文件可彻底删除");
        }
        try {
            storageDriver.remove(file.getStorageKey());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL, "文件删除失败");
        }
        fileAttachmentMapper.deleteById(id);
        systemLogService.record(principal.uid(), "file", "purge", "file", id, file.getStorageKey(), ip);
    }

    private FileAttachment mustGet(Long id) {
        FileAttachment file = fileAttachmentMapper.selectById(id);
        if (file == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return file;
    }

    private List<FileAdminView> toViews(List<FileAttachment> records) {
        Map<Long, String> names = namesOf(records.stream().map(FileAttachment::getUploaderId).toList());
        return records.stream().map(f -> new FileAdminView(f.getId(), f.getUploaderId(),
                f.getUploaderId() == null ? null : names.get(f.getUploaderId()),
                f.getStorageKey(), f.getOriginalName(), f.getMimeType(), f.getSizeBytes(),
                f.getSha256(), f.getState(), f.getTrashedAt(), f.getCreatedAt())).toList();
    }

    private Map<Long, String> namesOf(List<Long> uploaderIds) {
        List<Long> ids = uploaderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, ids)).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    static void requireLogin(AuthPrincipal principal) {
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
    }

    static void requirePermission(AuthPrincipal principal, String key) {
        if (!principal.has(key)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问");
        }
    }
}
