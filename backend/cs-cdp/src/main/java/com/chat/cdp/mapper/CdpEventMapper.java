package com.chat.cdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.cdp.entity.CdpEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CdpEventMapper extends BaseMapper<CdpEvent> {

    /**
     * 统计某用户某事件类型在时间窗口内的次数.
     * 例: payment_failed 最近 1 小时 3 次 → 触发 PAYMENT_FAILED_3X 规则.
     */
    @Select("""
        SELECT COUNT(*) AS cnt
        FROM cdp_event
        WHERE user_id = #{userId}
          AND event_type = #{eventType}
          AND occurred_at >= #{since}
        """)
    Long countByUserAndType(Long userId, String eventType, LocalDateTime since);

    /**
     * 查询某用户最近一次某事件 (用于 ORDER_STUCK_24H).
     */
    @Select("""
        SELECT * FROM cdp_event
        WHERE user_id = #{userId} AND event_type = #{eventType}
        ORDER BY occurred_at DESC LIMIT 1
        """)
    CdpEvent findLatestByType(Long userId, String eventType);

    /**
     * 按事件类型聚合 (用于 dashboard).
     */
    @Select("""
        SELECT event_type, COUNT(*) AS cnt
        FROM cdp_event
        WHERE occurred_at >= #{since}
        GROUP BY event_type
        ORDER BY cnt DESC
        """)
    List<Map<String, Object>> aggregateByType(LocalDateTime since);
}