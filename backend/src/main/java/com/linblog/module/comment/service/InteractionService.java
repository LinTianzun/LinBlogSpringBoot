package com.linblog.module.comment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linblog.common.BizException;
import com.linblog.common.ErrorCode;
import com.linblog.module.comment.dto.InteractionRequest;
import com.linblog.module.comment.dto.PraiseResult;
import com.linblog.module.comment.entity.Interaction;
import com.linblog.module.comment.mapper.InteractionMapper;
import com.linblog.module.content.entity.Content;
import com.linblog.module.content.mapper.ContentMapper;
import com.linblog.module.diary.entity.Diary;
import com.linblog.module.diary.mapper.DiaryMapper;
import com.linblog.security.AuthPrincipal;
import com.linblog.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 内容/日记点赞（§5.5 POST /interactions）：toggle，幂等——
 * interactions.uk_target_user 保证同用户同目标至多一条；计数走 content.praises / diaries.praises。
 * 只允许已发布目标（游客/登录一致口径），否则 40400 防探测。
 */
@Service
public class InteractionService {

    private static final String TYPE_CONTENT = "content";
    private static final String TYPE_DIARY = "diary";

    private final InteractionMapper interactionMapper;
    private final ContentMapper contentMapper;
    private final DiaryMapper diaryMapper;

    public InteractionService(InteractionMapper interactionMapper,
                              ContentMapper contentMapper,
                              DiaryMapper diaryMapper) {
        this.interactionMapper = interactionMapper;
        this.contentMapper = contentMapper;
        this.diaryMapper = diaryMapper;
    }

    @Transactional
    public PraiseResult toggle(InteractionRequest request) {
        AuthPrincipal principal = CurrentUser.get();
        if (principal == null) {
            throw new BizException(ErrorCode.UNAUTHENTICATED, "未登录");
        }
        String targetType = request.targetType() == null ? "" : request.targetType().trim();
        if (!TYPE_CONTENT.equals(targetType) && !TYPE_DIARY.equals(targetType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "点赞目标类型不合法");
        }
        if (request.targetId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "点赞目标不能为空");
        }
        requirePublishedTarget(targetType, request.targetId());

        Interaction existing = interactionMapper.selectOne(new LambdaQueryWrapper<Interaction>()
                .eq(Interaction::getTargetType, targetType)
                .eq(Interaction::getTargetId, request.targetId())
                .eq(Interaction::getUserId, principal.uid()));
        boolean active;
        if (existing != null) {
            interactionMapper.deleteById(existing.getId());
            if (TYPE_CONTENT.equals(targetType)) {
                contentMapper.decrPraises(request.targetId());
            } else {
                diaryMapper.decrPraises(request.targetId());
            }
            active = false;
        } else {
            Interaction interaction = new Interaction();
            interaction.setTargetType(targetType);
            interaction.setTargetId(request.targetId());
            interaction.setUserId(principal.uid());
            interaction.setCreatedAt(LocalDateTime.now());
            try {
                interactionMapper.insert(interaction);
            } catch (DuplicateKeyException e) {
                // 并发重复点赞：uk 兜底，视为已点赞
            }
            if (TYPE_CONTENT.equals(targetType)) {
                contentMapper.incrPraises(request.targetId());
            } else {
                diaryMapper.incrPraises(request.targetId());
            }
            active = true;
        }
        return new PraiseResult(active, currentPraiseCount(targetType, request.targetId()));
    }

    private void requirePublishedTarget(String targetType, long targetId) {
        if (TYPE_CONTENT.equals(targetType)) {
            Content content = contentMapper.selectById(targetId);
            if (content == null || !"published".equals(content.getState())) {
                throw new BizException(ErrorCode.NOT_FOUND, "内容不存在");
            }
        } else {
            Diary diary = diaryMapper.selectById(targetId);
            if (diary == null || !"published".equals(diary.getState())) {
                throw new BizException(ErrorCode.NOT_FOUND, "日记不存在");
            }
        }
    }

    private long currentPraiseCount(String targetType, long targetId) {
        if (TYPE_CONTENT.equals(targetType)) {
            Content content = contentMapper.selectById(targetId);
            return content == null || content.getPraises() == null ? 0 : content.getPraises();
        }
        Diary diary = diaryMapper.selectById(targetId);
        return diary == null || diary.getPraises() == null ? 0 : diary.getPraises();
    }
}
