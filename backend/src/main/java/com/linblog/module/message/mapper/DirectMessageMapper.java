package com.linblog.module.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.message.entity.DirectMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DirectMessageMapper extends BaseMapper<DirectMessage> {

    /** 收件人清未读时，把会话内对方发来的未读消息标记已读时间。 */
    @Update("UPDATE direct_messages SET read_at = #{ts} "
            + "WHERE conversation_id = #{conversationId} AND sender_id <> #{uid} AND read_at IS NULL")
    int markConversationRead(@Param("conversationId") Long conversationId,
                             @Param("uid") Long uid,
                             @Param("ts") LocalDateTime ts);
}
