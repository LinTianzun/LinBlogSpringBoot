package com.linblog.module.guestbook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linblog.module.guestbook.entity.Guestbook;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GuestbookMapper extends BaseMapper<Guestbook> {
}
