package com.chat.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.im.entity.MessageReceipt;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageReceiptMapper extends BaseMapper<MessageReceipt> {
}