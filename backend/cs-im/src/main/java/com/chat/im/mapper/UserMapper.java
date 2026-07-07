package com.chat.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.im.entity.Agent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 查询坐席信息 (cs-im 不需要密码, 只取 id / nickname / skill_tags / status).
 */
@Mapper
public interface UserMapper extends BaseMapper<Agent> {
}