package com.linblog.module.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.media.entity.FileAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface FileAttachmentMapper extends BaseMapper<FileAttachment> {

    /** 按状态聚合字节数与条数（§5.7 storage-status：主区/备份区字节、2GiB 配额、80% 告警）。 */
    @Select("SELECT state, COUNT(*) AS cnt, IFNULL(SUM(size_bytes), 0) AS total_bytes "
            + "FROM files GROUP BY state")
    List<Map<String, Object>> selectStateSummary();
}
