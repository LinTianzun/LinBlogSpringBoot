package com.linblog.module.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.message.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 私信会话表访问（M4）：M4 概览沿用全站未读统计 sumUnread()；
 * 会话 CRUD 与双方未读计数由私信模块使用。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /** 全站未读私信总数（a_unread + b_unread）。 */
    @Select("SELECT IFNULL(SUM(a_unread + b_unread), 0) FROM conversations")
    long sumUnread();

    @Update("UPDATE conversations SET a_unread = a_unread + 1 WHERE id = #{id}")
    int incrAUnread(@Param("id") Long id);

    @Update("UPDATE conversations SET b_unread = b_unread + 1 WHERE id = #{id}")
    int incrBUnread(@Param("id") Long id);

    @Update("UPDATE conversations SET a_unread = 0 WHERE id = #{id}")
    int clearAUnread(@Param("id") Long id);

    @Update("UPDATE conversations SET b_unread = 0 WHERE id = #{id}")
    int clearBUnread(@Param("id") Long id);

    @Update("UPDATE conversations SET last_message_at = #{ts} WHERE id = #{id}")
    int touchLastMessage(@Param("id") Long id, @Param("ts") LocalDateTime ts);

    /** 单个用户的跨会话未读总数（只统计自己一侧的计数）。 */
    @Select("SELECT COALESCE(SUM(CASE WHEN user_a_id = #{uid} THEN a_unread "
            + "WHEN user_b_id = #{uid} THEN b_unread ELSE 0 END), 0) "
            + "FROM conversations WHERE user_a_id = #{uid} OR user_b_id = #{uid}")
    long sumUnreadFor(@Param("uid") Long uid);
}
