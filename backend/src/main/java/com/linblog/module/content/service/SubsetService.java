package com.linblog.module.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.content.dto.SubsetCreateRequest;
import com.linblog.module.content.dto.SubsetItemsRequest;
import com.linblog.module.content.dto.SubsetUpdateRequest;
import com.linblog.module.content.entity.Subset;
import com.linblog.module.content.entity.SubsetItem;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.content.mapper.SubsetItemMapper;
import com.linblog.module.content.mapper.SubsetMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 合集管理：§5 未定义端点，本实现自设计（汇报偏差）。
 * 权限：create 需 article.create|gallery.create；读需 viewAll（全部）或 viewOwn（仅 own）；
 * 管理（update/items/delete）需 manageAll 或为 owner。
 */
@Service
public class SubsetService {

    private final SubsetMapper subsetMapper;
    private final SubsetItemMapper subsetItemMapper;
    private final ContentMapper contentMapper;
    private final SystemLogService systemLogService;

    public SubsetService(SubsetMapper subsetMapper, SubsetItemMapper subsetItemMapper,
                         ContentMapper contentMapper, SystemLogService systemLogService) {
        this.subsetMapper = subsetMapper;
        this.subsetItemMapper = subsetItemMapper;
        this.contentMapper = contentMapper;
        this.systemLogService = systemLogService;
    }

    public PageResult<Subset> page(PageQuery query, boolean viewAll, Long uid) {
        Page<Subset> page = new Page<>(query.getPage(), query.getPageSize());
        LambdaQueryWrapper<Subset> wrapper = new LambdaQueryWrapper<>();
        if (!viewAll) {
            wrapper.eq(Subset::getOwnerId, uid);
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(Subset::getName, query.getKeyword());
        }
        wrapper.orderByDesc(Subset::getId);
        subsetMapper.selectPage(page, wrapper);
        return PageResult.of(page.getRecords(), page.getTotal());
    }

    @Transactional
    public Subset create(SubsetCreateRequest request) {
        AuthPrincipal principal = requireManagePermission(
                com.linblog.module.permission.PermissionKeys.ARTICLE_CREATE,
                com.linblog.module.permission.PermissionKeys.GALLERY_CREATE);
        Subset subset = new Subset();
        subset.setName(request.name().trim());
        subset.setDescription(request.description());
        subset.setCoverMediaId(request.coverMediaId());
        subset.setOwnerId(principal.uid());
        subset.setCreatedAt(LocalDateTime.now());
        subsetMapper.insert(subset);
        saveItems(subset.getId(), request.contentIds());
        systemLogService.record(principal.uid(), "subset", "create", "subset", subset.getId(),
                subset.getName(), null);
        return subset;
    }

    public Subset get(Long id) {
        Subset subset = subsetMapper.selectById(id);
        if (subset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
        }
        return subset;
    }

    @Transactional
    public void update(Long id, SubsetUpdateRequest request) {
        AuthPrincipal principal = requireOwnership(id);
        Subset subset = subsetMapper.selectById(id);
        if (subset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
        }
        if (request.name() != null && !request.name().isBlank()) {
            subset.setName(request.name().trim());
        }
        if (request.description() != null) {
            subset.setDescription(request.description());
        }
        if (request.coverMediaId() != null) {
            subset.setCoverMediaId(request.coverMediaId());
        }
        subsetMapper.updateById(subset);
        systemLogService.record(principal.uid(), "subset", "update", "subset", id,
                subset.getName(), null);
    }

    @Transactional
    public void replaceItems(Long id, SubsetItemsRequest request) {
        AuthPrincipal principal = requireOwnership(id);
        if (subsetMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
        }
        subsetItemMapper.deleteBySubsetId(id);
        saveItems(id, request.contentIds());
        systemLogService.record(principal.uid(), "subset", "update_items", "subset", id, null, null);
    }

    public List<SubsetItem> itemsOf(Long id) {
        if (subsetMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
        }
        return subsetItemMapper.selectList(new LambdaQueryWrapper<SubsetItem>()
                .eq(SubsetItem::getSubsetId, id)
                .orderByAsc(SubsetItem::getSort));
    }

    @Transactional
    public void delete(Long id) {
        AuthPrincipal principal = requireOwnership(id);
        Subset subset = subsetMapper.selectById(id);
        if (subset == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
        }
        subsetItemMapper.deleteBySubsetId(id);
        subsetMapper.deleteById(id);
        systemLogService.record(principal.uid(), "subset", "delete", "subset", id,
                subset.getName(), null);
    }

    private void saveItems(Long subsetId, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return;
        }
        int sort = 0;
        for (Long contentId : contentIds) {
            if (contentId != null && contentMapper.selectById(contentId) != null) {
                SubsetItem item = new SubsetItem();
                item.setSubsetId(subsetId);
                item.setContentId(contentId);
                item.setSort(sort++);
                try {
                    subsetItemMapper.insert(item);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // 同一内容重复加入合集：跳过
                }
            }
        }
    }

    private AuthPrincipal requireManagePermission(String... anyOf) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.hasAny(anyOf)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限执行该操作");
        }
        return principal;
    }

    private AuthPrincipal requireOwnership(Long subsetId) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        boolean manageAll = principal.has(com.linblog.module.permission.PermissionKeys.ARTICLE_MANAGE_ALL)
                || principal.has(com.linblog.module.permission.PermissionKeys.GALLERY_MANAGE_ALL);
        if (!manageAll) {
            boolean viewOwn = principal.has(com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_OWN)
                    || principal.has(com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_OWN);
            Subset subset = subsetMapper.selectById(subsetId);
            if (!viewOwn || subset == null || subset.getOwnerId() == null
                    || principal.uid() != subset.getOwnerId()) {
                // 防探测：无归属权限或不属于本人一律 40400
                throw new BizException(ErrorCode.NOT_FOUND, "合集不存在");
            }
        }
        return principal;
    }
}
