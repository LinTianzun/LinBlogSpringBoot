package com.linblog.module.diary.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.common.PageQuery;
import com.linblog.common.PageResult;
import com.linblog.module.diary.dto.DiaryCreateRequest;
import com.linblog.module.diary.dto.DiaryUpdateRequest;
import com.linblog.module.diary.dto.DiaryView;
import com.linblog.module.diary.entity.Diary;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.module.audit.service.SystemLogService;
import com.linblog.module.content.service.WeatherValidator;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 日记（§5.4）：一天一篇（uk_author_date）→40900；weather 白名单；
 * 无 publish 权限键，状态流转用 manage 权限（manageAll 或 manageOwn+归属）。
 */
@Service
public class DiaryService {

    private final DiaryMapper diaryMapper;
    private final SystemLogService systemLogService;

    public DiaryService(DiaryMapper diaryMapper, SystemLogService systemLogService) {
        this.diaryMapper = diaryMapper;
        this.systemLogService = systemLogService;
    }

    public PageResult<DiaryView> page(PageQuery query, String state, boolean viewAll, Long uid) {
        Page<Diary> page = new Page<>(query.getPage(), query.getPageSize());
        LambdaQueryWrapper<Diary> wrapper = new LambdaQueryWrapper<>();
        if (!viewAll) {
            wrapper.eq(Diary::getAuthorId, uid);
        }
        if (state != null && !state.isBlank()) {
            wrapper.eq(Diary::getState, state);
        }
        wrapper.orderByDesc(Diary::getDiaryDate);
        diaryMapper.selectPage(page, wrapper);
        List<DiaryView> views = page.getRecords().stream().map(this::toView).toList();
        return PageResult.of(views, page.getTotal());
    }

    @Transactional
    public DiaryView create(DiaryCreateRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        if (!principal.has(com.linblog.module.permission.PermissionKeys.DIARY_CREATE)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限执行该操作");
        }
        WeatherValidator.validate(request.weather());
        String state = request.state() == null || request.state().isBlank()
                ? "draft" : request.state();
        if (!"draft".equals(state) && !"published".equals(state)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态取值不合法");
        }
        Diary diary = new Diary();
        diary.setAuthorId(principal.uid());
        diary.setDiaryDate(request.diaryDate());
        diary.setWeather(request.weather());
        diary.setMood(request.mood());
        diary.setBody(request.body());
        diary.setState(state);
        diary.setViews(0);
        diary.setPraises(0);
        diary.setCreatedAt(LocalDateTime.now());
        diary.setUpdatedAt(LocalDateTime.now());
        try {
            diaryMapper.insert(diary);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "该日期已有日记");
        }
        systemLogService.record(principal.uid(), "diary", "create", "diary", diary.getId(), null, null);
        return toView(diary);
    }

    public DiaryView get(Long id, boolean viewAll, Long uid) {
        Diary diary = mustGet(id, viewAll, uid);
        return toView(diary);
    }

    /** 门户日记详情（§5.4）：仅 published，防探测 40400。 */
    public DiaryView portalGet(Long id) {
        Diary diary = diaryMapper.selectById(id);
        if (diary == null || !"published".equals(diary.getState())) {
            throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
        }
        return toView(diary);
    }

    @Transactional
    public DiaryView update(Long id, DiaryUpdateRequest request) {
        AuthPrincipal principal = requireManage(id);
        Diary diary = diaryMapper.selectById(id);
        if (request.diaryDate() != null) {
            diary.setDiaryDate(request.diaryDate());
        }
        if (request.weather() != null) {
            WeatherValidator.validate(request.weather());
            diary.setWeather(request.weather());
        }
        if (request.mood() != null) {
            diary.setMood(request.mood());
        }
        if (request.body() != null) {
            diary.setBody(request.body());
        }
        if (request.state() != null && !request.state().isBlank()) {
            validateState(request.state());
            diary.setState(request.state());
        }
        diary.setUpdatedAt(LocalDateTime.now());
        try {
            diaryMapper.updateById(diary);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "该日期已有日记");
        }
        systemLogService.record(principal.uid(), "diary", "update", "diary", id, null, null);
        return toView(diary);
    }

    @Transactional
    public void delete(Long id) {
        AuthPrincipal principal = requireManage(id);
        diaryMapper.deleteById(id);
        systemLogService.record(principal.uid(), "diary", "delete", "diary", id, null, null);
    }

    private Diary mustGet(Long id, boolean viewAll, Long uid) {
        Diary diary = diaryMapper.selectById(id);
        if (diary == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
        }
        if (!viewAll && (uid == null || !uid.equals(diary.getAuthorId()))) {
            throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
        }
        return diary;
    }

    private void validateState(String state) {
        if (!List.of("draft", "published").contains(state)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "状态取值不合法");
        }
    }

    private AuthPrincipal requireManage(Long id) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        boolean manageAll = principal.has(com.linblog.module.permission.PermissionKeys.DIARY_MANAGE_ALL);
        if (!manageAll) {
            boolean manageOwn = principal.has(com.linblog.module.permission.PermissionKeys.DIARY_MANAGE_OWN);
            Diary diary = diaryMapper.selectById(id);
            if (!manageOwn || diary == null || diary.getAuthorId() == null
                    || principal.uid() != diary.getAuthorId()) {
                throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
            }
        }
        return principal;
    }

    private DiaryView toView(Diary diary) {
        return new DiaryView(diary.getId(), diary.getAuthorId(), null, diary.getDiaryDate(),
                diary.getWeather(), diary.getMood(), diary.getBody(), diary.getState(),
                diary.getViews(), diary.getPraises(), diary.getCreatedAt(), diary.getUpdatedAt());
    }
}
