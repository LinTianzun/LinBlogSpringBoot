package com.linblog.module.permission.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * role_permissions（权限矩阵覆盖值，联合主键）。
 * 覆盖语义：角色有行 → 表值为准；无行 → 用代码内默认矩阵。
 */
@Mapper
public interface RolePermissionMapper {

    @Select("SELECT perm_key FROM role_permissions WHERE role_code = #{roleCode}")
    List<String> selectKeysByRole(@Param("roleCode") String roleCode);

    @Delete("DELETE FROM role_permissions WHERE role_code = #{roleCode}")
    int deleteByRole(@Param("roleCode") String roleCode);

    @Insert("INSERT INTO role_permissions (role_code, perm_key) VALUES (#{roleCode}, #{permKey})")
    int insert(@Param("roleCode") String roleCode, @Param("permKey") String permKey);
}
