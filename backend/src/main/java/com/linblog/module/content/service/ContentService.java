package com.linblog.module.content.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.content.dto.ContentCreateRequest;
import com.linblog.module.content.dto.ContentStateRequest;
import com.linblog.module.content.dto.ContentUpdateRequest;
import com.linblog.module.content.dto.ContentView;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.entity.ContentLabel;
import com.linblog.module.content.entity.Label;
import com.linblog.module.content.mapper.ContentLabelMapper;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.content.mapper.LabelMapper;
import com.linblog.module.media.entity.Media;
import com.linblog.module.media.mapper.MediaMapper;
import com.linblog.module.user.entity.User;
import com.linblog.module.user.mapper.UserMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内容域（§5.3）：article/image 双类型；state=draft/published/hidden/deleted（deleted 即回收站）。
 * 状态机：draft→{published,deleted}；published→{hidden,deleted}；hidden→{published,draft,deleted}；
 * deleted→{draft}（还原）。article 进入 published 或 published→hidden 需 article.publish。
 */
@Service
public class ContentService {

    public static final String TYPE_ARTICLE = "article";
    public static final String TYPE_IMAGE = "image";
    public static final String STATE_DRAFT = "draft";
    public static final String STATE_PUBLISHED = "published";
    public static final String STATE_HIDDEN = "hidden";
    public static final String STATE_DELETED = "deleted";

    private final ContentMapper contentMapper;
    private final ContentLabelMapper contentLabelMapper;
    private final LabelMapper labelMapper;
    private final MediaMapper mediaMapper;
    private final UserMapper userMapper;
    private final ContentJsonValidator jsonValidator;
    private final SystemLogService systemLogService;

    public ContentService(ContentMapper contentMapper, ContentLabelMapper contentLabelMapper,
                          LabelMapper labelMapper, MediaMapper mediaMapper, UserMapper userMapper,
                          ContentJsonValidator jsonValidator, SystemLogService systemLogService) {
        this.contentMapper = contentMapper;
        this.contentLabelMapper = contentLabelMapper;
        this.labelMapper = labelMapper;
        this.mediaMapper = mediaMapper;
        this.userMapper = userMapper;
        this.jsonValidator = jsonValidator;
        this.systemLogService = systemLogService;
    }

    // ---------- 管理端 ----------

    public PageResult<ContentView> adminPage(PageQuery query, String contentType, String state,
                                             Long labelId, boolean viewAll, Long uid) {
        Page<Content> page = new Page<>(query.getPage(), query.getPageSize());
        LambdaQueryWrapper<Content> wrapper = new LambdaQueryWrapper<>();
        if (!viewAll) {
            wrapper.eq(Content::getAuthorId, uid);
        }
        if (contentType != null && !contentType.isBlank()) {
            wrapper.eq(Content::getContentType, contentType);
        }
        if (state != null && !state.isBlank()) {
            wrapper.eq(Content::getState, state);
        }
        if (labelId != null) {
            wrapper.inSql(Content::getId, "SELECT content_id FROM content_labels WHERE label_id = " + labelId);
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(Content::getTitle, query.getKeyword());
        }
        wrapper.orderByDesc(Content::getIsPinned).orderByDesc(Content::getUpdatedAt);
        contentMapper.selectPage(page, wrapper);
        List<ContentView> views = toViews(page.getRecords(), false);
        return PageResult.of(views, page.getTotal());
    }

    @Transactional
    public ContentView create(ContentCreateRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        String type = request.contentType();
        if (!TYPE_ARTICLE.equals(type) && !TYPE_IMAGE.equals(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内容类型不合法");
        }
        String createKey = TYPE_ARTICLE.equals(type)
                ? com.linblog.module.permission.PermissionKeys.ARTICLE_CREATE
                : com.linblog.module.permission.PermissionKeys.GALLERY_CREATE;
        if (!principal.has(createKey)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限执行该操作");
        }
        String title = request.title();
        if (title == null || title.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标题不能为空");
        }
        title = title.trim();
        if (title.length() > 150) {
            throw new BizException(ErrorCode.BAD_REQUEST, "标题不能超过 150 字");
        }
        if (request.introduce() != null && request.introduce().length() > 512) {
            throw new BizException(ErrorCode.BAD_REQUEST, "摘要不能超过 512 字");
        }
        if (TYPE_IMAGE.equals(type) && request.coverMediaId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图片内容必须指定封面");
        }
        String plainText = "";
        if (TYPE_ARTICLE.equals(type)) {
            if (request.body() == null || request.body().isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "正文不能为空");
            }
            plainText = jsonValidator.validateAndExtract(request.body());
        }
        String state = request.state() == null || request.state().isBlank() ? STATE_DRAFT : request.state();
        if (!List.of(STATE_DRAFT, STATE_PUBLISHED).contains(state)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态取值不合法");
        }
        if (STATE_PUBLISHED.equals(state) && TYPE_ARTICLE.equals(type)
                && !principal.has(com.linblog.module.permission.PermissionKeys.ARTICLE_PUBLISH)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限发布该内容");
        }
        Content content = new Content();
        content.setAuthorId(principal.uid());
        content.setContentType(type);
        content.setTitle(title);
        content.setIntroduce(request.introduce());
        content.setBody(TYPE_ARTICLE.equals(type) ? request.body() : null);
        content.setPlainText(plainText);
        content.setCoverMediaId(request.coverMediaId());
        content.setState(state);
        content.setViews(0);
        content.setPraises(0);
        content.setCommentsCount(0);
        content.setIsPinned(false);
        if (STATE_PUBLISHED.equals(state)) {
            content.setPublishedAt(LocalDateTime.now());
        }
        contentMapper.insert(content);
        saveLabels(content.getId(), request.labelIds());
        systemLogService.record(principal.uid(), "content", "create", type, content.getId(), title, null);
        return toView(content, true);
    }

    public ContentView adminGet(Long id, AuthPrincipal principal) {
        Content content = contentMapper.selectById(id);
        // §7.3 防探测：读取类不满足 → 40400
        if (content == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        if (canViewAll(principal, content.getContentType())) {
            return toView(content, true);
        }
        boolean own = canViewOwn(principal, content.getContentType())
                && content.getAuthorId() != null && principal.uid() == content.getAuthorId();
        if (!own) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        return toView(content, true);
    }

    /** 管理端读范围：viewAll 按内容类型判定；null 类型视为聚合列表（任一 viewAll）。 */
    public static boolean canViewAll(AuthPrincipal principal, String contentType) {
        if (principal == null) {
            return false;
        }
        if (contentType == null || contentType.isBlank()) {
            return principal.hasAny(com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_ALL,
                    com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_ALL);
        }
        return principal.has(TYPE_ARTICLE.equals(contentType)
                ? com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_ALL
                : com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_ALL);
    }

    public static boolean canViewOwn(AuthPrincipal principal, String contentType) {
        if (principal == null) {
            return false;
        }
        if (contentType == null || contentType.isBlank()) {
            return principal.hasAny(com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_OWN,
                    com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_OWN);
        }
        return principal.has(TYPE_ARTICLE.equals(contentType)
                ? com.linblog.module.permission.PermissionKeys.ARTICLE_VIEW_OWN
                : com.linblog.module.permission.PermissionKeys.GALLERY_VIEW_OWN);
    }

    @Transactional
    public ContentView update(Long id, ContentUpdateRequest request) {
        AuthPrincipal principal = requireManage(id);
        Content content = contentMapper.selectById(id);
        if (request.title() != null) {
            String title = request.title().trim();
            if (title.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "标题不能为空");
            }
            if (title.length() > 150) {
                throw new BizException(ErrorCode.BAD_REQUEST, "标题不能超过 150 字");
            }
            content.setTitle(title);
        }
        if (request.introduce() != null) {
            if (request.introduce().length() > 512) {
                throw new BizException(ErrorCode.BAD_REQUEST, "摘要不能超过 512 字");
            }
            content.setIntroduce(request.introduce());
        }
        if (request.body() != null) {
            if (TYPE_ARTICLE.equals(content.getContentType())) {
                content.setBody(request.body());
                content.setPlainText(jsonValidator.validateAndExtract(request.body()));
            }
        }
        if (request.coverMediaId() != null) {
            content.setCoverMediaId(request.coverMediaId());
        }
        if (request.isPinned() != null) {
            content.setIsPinned(request.isPinned());
        }
        contentMapper.updateById(content);
        if (request.labelIds() != null) {
            saveLabels(id, request.labelIds());
        }
        systemLogService.record(principal.uid(), "content", "update", content.getContentType(),
                id, content.getTitle(), null);
        return toView(content, true);
    }

    @Transactional
    public ContentView changeState(Long id, ContentStateRequest request) {
        AuthPrincipal principal = requireManage(id);
        Content content = contentMapper.selectById(id);
        String target = request.state();
        if (target == null || !List.of(STATE_DRAFT, STATE_PUBLISHED, STATE_HIDDEN).contains(target)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态取值不合法");
        }
        String current = content.getState();
        if (current.equals(STATE_DELETED)) {
            // 回收站内容走 restore 专用入口（target=draft）
            if (target.equals(STATE_DRAFT)) {
                content.setState(STATE_DRAFT);
                contentMapper.updateById(content);
                systemLogService.record(principal.uid(), "content", "restore",
                        content.getContentType(), id, content.getTitle(), null);
                return toView(content, true);
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "回收站内容仅可还原为草稿");
        }
        if (current.equals(target)) {
            return toView(content, true);
        }
        boolean isArticle = TYPE_ARTICLE.equals(content.getContentType());
        boolean enteringPublished = STATE_PUBLISHED.equals(target) && !STATE_PUBLISHED.equals(current);
        boolean leavingPublished = STATE_PUBLISHED.equals(current) && STATE_HIDDEN.equals(target);
        if (isArticle && (enteringPublished || leavingPublished)
                && !principal.has(com.linblog.module.permission.PermissionKeys.ARTICLE_PUBLISH)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限发布该内容");
        }
        content.setState(target);
        if (enteringPublished) {
            content.setPublishedAt(LocalDateTime.now());
        }
        contentMapper.updateById(content);
        systemLogService.record(principal.uid(), "content", "state_" + target,
                content.getContentType(), id, content.getTitle(), null);
        return toView(content, true);
    }

    @Transactional
    public void delete(Long id) {
        AuthPrincipal principal = requireManage(id);
        Content content = contentMapper.selectById(id);
        if (content.getState().equals(STATE_DELETED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内容已在回收站");
        }
        content.setState(STATE_DELETED);
        contentMapper.updateById(content);
        // §7.5：封面无其他非 deleted 内容引用 → media 转 trash
        cleanupCover(content);
        systemLogService.record(principal.uid(), "content", "trash", content.getContentType(),
                id, content.getTitle(), null);
    }

    @Transactional
    public void purge(Long id) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        Content content = contentMapper.selectById(id);
        if (content == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        String manageAll = TYPE_ARTICLE.equals(content.getContentType())
                ? com.linblog.module.permission.PermissionKeys.ARTICLE_MANAGE_ALL
                : com.linblog.module.permission.PermissionKeys.GALLERY_MANAGE_ALL;
        if (!principal.has(manageAll)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限执行该操作");
        }
        if (!content.getState().equals(STATE_DELETED)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "仅回收站内容可彻底删除");
        }
        contentLabelMapper.deleteByContentId(id);
        contentMapper.deleteById(id);
        systemLogService.record(principal.uid(), "content", "purge", content.getContentType(),
                id, content.getTitle(), null);
    }

    /** 清理调度（§7.9）：物理删除超过保留期的回收站内容。 */
    @Transactional
    public int purgeExpired(LocalDateTime cutoff) {
        List<Content> expired = contentMapper.selectList(new LambdaQueryWrapper<Content>()
                .eq(Content::getState, STATE_DELETED)
                .lt(Content::getUpdatedAt, cutoff));
        for (Content content : expired) {
            contentLabelMapper.deleteByContentId(content.getId());
            contentMapper.deleteById(content.getId());
        }
        return expired.size();
    }

    // ---------- 门户 ----------

    public PageResult<ContentView> portalPage(PageQuery query, String contentType, Long labelId) {
        Page<Content> page = new Page<>(query.getPage(), query.getPageSize());
        LambdaQueryWrapper<Content> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Content::getState, STATE_PUBLISHED);
        if (contentType != null && !contentType.isBlank()) {
            wrapper.eq(Content::getContentType, contentType);
        }
        if (labelId != null) {
            wrapper.inSql(Content::getId, "SELECT content_id FROM content_labels WHERE label_id = " + labelId);
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.and(w -> w.like(Content::getTitle, query.getKeyword())
                    .or()
                    .apply("MATCH(plain_text) AGAINST({0} IN NATURAL LANGUAGE MODE)", query.getKeyword()));
        }
        wrapper.orderByDesc(Content::getIsPinned).orderByDesc(Content::getPublishedAt);
        contentMapper.selectPage(page, wrapper);
        List<ContentView> views = toViews(page.getRecords(), false);
        return PageResult.of(views, page.getTotal());
    }

    public List<ContentView> portalPinned() {
        List<Content> pinned = contentMapper.selectList(new LambdaQueryWrapper<Content>()
                .eq(Content::getState, STATE_PUBLISHED)
                .eq(Content::getIsPinned, true)
                .orderByDesc(Content::getPublishedAt)
                .last("LIMIT 5"));
        return toViews(pinned, false);
    }

    public List<Label> portalLabels() {
        return labelMapper.selectList(new LambdaQueryWrapper<Label>().orderByAsc(Label::getId));
    }

    /** 门户详情：仅 published；?preview=1 时作者/管理可看非 published。 */
    public ContentView portalGet(Long id, boolean preview, AuthPrincipal principal) {
        Content content = contentMapper.selectById(id);
        if (content == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        if (!STATE_PUBLISHED.equals(content.getState())) {
            boolean allowed = preview && principal != null
                    && (principal.hasAny(com.linblog.module.permission.PermissionKeys.ARTICLE_MANAGE_ALL,
                            com.linblog.module.permission.PermissionKeys.GALLERY_MANAGE_ALL)
                        || (content.getAuthorId() != null && principal.uid() == content.getAuthorId()));
            if (!allowed) {
                throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
            }
        }
        return toView(content, true);
    }

    // ---------- 内部 ----------

    private AuthPrincipal requireManage(Long id) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        boolean isArticle;
        Content content = contentMapper.selectById(id);
        if (content == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
        }
        isArticle = TYPE_ARTICLE.equals(content.getContentType());
        String manageAll = isArticle ? com.linblog.module.permission.PermissionKeys.ARTICLE_MANAGE_ALL
                : com.linblog.module.permission.PermissionKeys.GALLERY_MANAGE_ALL;
        if (principal.has(manageAll)) {
            return principal;
        }
        String manageOwn = isArticle ? com.linblog.module.permission.PermissionKeys.ARTICLE_MANAGE_OWN
                : com.linblog.module.permission.PermissionKeys.GALLERY_MANAGE_OWN;
        if (principal.has(manageOwn) && content.getAuthorId() != null
                && principal.uid() == content.getAuthorId()) {
            return principal;
        }
        // 操作类：归属不符 → 40400（§7.3 防探测）
        throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
    }

    private void saveLabels(Long contentId, List<Long> labelIds) {
        contentLabelMapper.deleteByContentId(contentId);
        if (labelIds == null || labelIds.isEmpty()) {
            return;
        }
        for (Long labelId : new LinkedHashSet<>(labelIds)) {
            if (labelId == null || labelMapper.selectById(labelId) == null) {
                continue;
            }
            ContentLabel cl = new ContentLabel();
            cl.setContentId(contentId);
            cl.setLabelId(labelId);
            contentLabelMapper.insert(cl);
        }
    }

    private void cleanupCover(Content content) {
        if (content.getCoverMediaId() == null) {
            return;
        }
        long refs = mediaMapper.countCoverReferences(content.getCoverMediaId(), content.getId());
        if (refs == 0) {
            Media media = mediaMapper.selectById(content.getCoverMediaId());
            if (media != null && "active".equals(media.getState())) {
                media.setState("trash");
                media.setTrashedAt(LocalDateTime.now());
                mediaMapper.updateById(media);
            }
        }
    }

    private List<ContentView> toViews(List<Content> records, boolean withBody) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = records.stream().map(Content::getAuthorId).collect(Collectors.toSet());
        Map<Long, String> authorNames = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getId, authorIds)).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        Map<Long, Content> byId = records.stream()
                .collect(Collectors.toMap(Content::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<Long>> labelIdMap = new java.util.HashMap<>();
        Map<Long, List<String>> labelNameMap = new java.util.HashMap<>();
        if (!byId.isEmpty()) {
            List<ContentLabel> relations = contentLabelMapper.selectList(new LambdaQueryWrapper<ContentLabel>()
                    .in(ContentLabel::getContentId, byId.keySet()));
            Set<Long> labelIds = relations.stream().map(ContentLabel::getLabelId).collect(Collectors.toSet());
            Map<Long, String> labelNames = labelIds.isEmpty() ? Map.of()
                    : labelMapper.selectList(new LambdaQueryWrapper<Label>().in(Label::getId, labelIds))
                            .stream().collect(Collectors.toMap(Label::getId, Label::getName, (a, b) -> a));
            for (ContentLabel relation : relations) {
                labelIdMap.computeIfAbsent(relation.getContentId(), k -> new ArrayList<>()).add(relation.getLabelId());
                String name = labelNames.get(relation.getLabelId());
                if (name != null) {
                    labelNameMap.computeIfAbsent(relation.getContentId(), k -> new ArrayList<>()).add(name);
                }
            }
        }
        List<ContentView> views = new ArrayList<>(records.size());
        for (Content content : records) {
            views.add(new ContentView(content.getId(), content.getContentType(), content.getTitle(),
                    content.getIntroduce(), withBody ? content.getBody() : null,
                    withBody ? content.getPlainText() : null, content.getCoverMediaId(),
                    content.getState(), content.getPublishedAt(), content.getViews(),
                    content.getPraises(), content.getCommentsCount(), content.getIsPinned(),
                    content.getAuthorId(), authorNames.get(content.getAuthorId()),
                    labelIdMap.get(content.getId()), labelNameMap.get(content.getId()),
                    content.getCreatedAt(), content.getUpdatedAt()));
        }
        return views;
    }

    private ContentView toView(Content content, boolean withBody) {
        return toViews(List.of(content), withBody).get(0);
    }
}
