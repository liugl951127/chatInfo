package com.chat.success.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.success.entity.HealthScoreHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface HealthScoreMapper extends BaseMapper<HealthScoreHistory> {

    @Select("SELECT * FROM success_health_score_history WHERE user_id = #{uid} ORDER BY created_at DESC LIMIT 1")
    HealthScoreHistory findLatest(Long uid);

    @Select("SELECT * FROM success_health_score_history WHERE user_id = #{uid} ORDER BY created_at DESC LIMIT 30")
    List<HealthScoreHistory> findHistory(Long uid);
}