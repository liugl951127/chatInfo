package com.chat.cdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CdpEvent - 客户行为事件流.
 * ----------------------------------------------------------------------------
 * append-only 表, 记录用户的所有关键行为.
 * 阶段 1 关键事件类型:
 *   - page_view: 页面访问
 *   - chat_start: 开始会话
 *   - chat_end: 结束会话
 *   - order_paid: 订单支付
 *   - order_logistics_stuck: 物流停滞 (定时任务检测)
 *   - payment_failed: 支付失败
 *   - user_inactive: 用户流失 (定时检测)
 *   - vip_upgrade: VIP 升级
 *   - profile_update: 资料更新
 */
@Data
@TableName("cdp_event")
public class CdpEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long sessionId;
    private String eventType;
    private String payload;
    private LocalDateTime occurredAt;
}