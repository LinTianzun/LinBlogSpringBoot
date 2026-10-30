package com.linblog.module.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.auth.entity.RefreshToken;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /** 标记轮换：仅未吊销行生效（并发重放时 affected=0）。 */
    @Update("""
            UPDATE refresh_tokens
               SET rotated_to_id = #{rotatedToId}, revoked_at = #{revokedAt}
             WHERE id = #{id} AND revoked_at IS NULL
            """)
    int markRotated(@Param("id") Long id, @Param("rotatedToId") Long rotatedToId,
                    @Param("revokedAt") LocalDateTime revokedAt);

    /** 吊销整个 family（重放处置）。 */
    @Update("""
            UPDATE refresh_tokens
               SET revoked_at = #{revokedAt}
             WHERE family_id = #{familyId} AND revoked_at IS NULL
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") LocalDateTime revokedAt);

    /** 吊销某用户全部刷新令牌（改密/禁用/重置密码）。 */
    @Update("""
            UPDATE refresh_tokens
               SET revoked_at = #{revokedAt}
             WHERE user_id = #{userId} AND revoked_at IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("revokedAt") LocalDateTime revokedAt);

    /** 过期清理（CleanupScheduler）。 */
    @Delete("DELETE FROM refresh_tokens WHERE expires_at < #{before}")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
