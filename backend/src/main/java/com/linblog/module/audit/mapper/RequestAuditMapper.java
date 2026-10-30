package com.linblog.module.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.audit.entity.RequestAudit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface RequestAuditMapper extends BaseMapper<RequestAudit> {

    @Delete("DELETE FROM request_audits WHERE created_at < #{before}")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
