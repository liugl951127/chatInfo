package com.chat.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.im.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}