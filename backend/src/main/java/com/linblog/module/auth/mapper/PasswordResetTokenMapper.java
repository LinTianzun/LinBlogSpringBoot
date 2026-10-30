package com.linblog.module.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.auth.entity.PasswordResetToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface PasswordResetTokenMapper extends BaseMapper<PasswordResetToken> {

    /** 一次性标记使用：仅未使用行生效（并发下 affected=0 视为已用）。 */
    @Update("""
            UPDATE password_reset_tokens
               SET used_at = #{usedAt}
             WHERE id = #{id} AND used_at IS NULL
            """)
    int markUsed(@Param("id") Long id, @Param("usedAt") LocalDateTime usedAt);

    /** 新请求作废旧令牌。 */
    @Update("""
            UPDATE password_reset_tokens
               SET used_at = #{usedAt}
             WHERE user_id = #{userId} AND used_at IS NULL
            """)
    int invalidateAllForUser(@Param("userId") Long userId, @Param("usedAt") LocalDateTime usedAt);
}
