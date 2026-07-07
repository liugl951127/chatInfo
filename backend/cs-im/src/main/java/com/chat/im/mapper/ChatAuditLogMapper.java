package com.chat.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.im.entity.ChatAuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatAuditLogMapper extends BaseMapper<ChatAuditLog> {
}