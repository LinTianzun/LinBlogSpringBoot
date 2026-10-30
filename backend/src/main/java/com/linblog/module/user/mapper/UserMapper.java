package com.linblog.module.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** token_version +1：改密/重置/重放告警后旧 access 全失效。 */
    @Update("UPDATE users SET token_version = token_version + 1 WHERE id = #{id}")
    int bumpTokenVersion(@Param("id") Long id);
}
