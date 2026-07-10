package com.chat.prediction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.prediction.entity.PredictionEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface PredictionEventMapper extends BaseMapper<PredictionEvent> {

    /**
     * 检查用户某规则今天是否已触发 (防刷).
     */
    @Select("""
        SELECT COUNT(*) FROM prediction_event
        WHERE user_id = #{userId} AND rule_code = #{ruleCode}
          AND created_at >= #{since}
        """)
    long countTodayByUserAndRule(Long userId, String ruleCode, LocalDateTime since);
}