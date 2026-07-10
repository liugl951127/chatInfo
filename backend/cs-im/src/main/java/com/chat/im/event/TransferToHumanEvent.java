package com.chat.im.event;

import lombok.Getter;                                                       // Lombok: 自动生成 getter
import org.springframework.context.ApplicationEvent;                        // Spring 事件基类

/**
 * 客户在机器人会话中请求转人工事件.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - MessageService 检测到客户在 bot 会话里发 "人工" / "真人" 等关键词时
 *     publishEvent() 发布本事件
 *   - SessionService 通过 @EventListener @Async 异步消费, 执行转人工逻辑
 *     (关闭 bot 会话 + 创建新人工会话 + 推 BOT_TRANSFER 事件给前端)
 *
 * 设计要点:
 *   - 用 Spring ApplicationEvent 解耦 MessageService 和 SessionService
 *     解决两个 service 互相依赖导致的 Spring 6 循环依赖错误
 *   - 事件携带 customerId + oldSessionId + skillTag, 消费者不需要 UserContext
 *     (因为 @Async 异步, 原线程的 ThreadLocal UserContext 可能已失效)
 *   - 消费者必须自己实现幂等性 (重复收到同一事件时不能重复创建会话)
 */
@Getter                                                                    // 自动生成 getCustomerId/getOldSessionId/getSkillTag
public class TransferToHumanEvent extends ApplicationEvent {

    /** 发起转人工的客户 ID (用于定位 owner + 推 BOT_TRANSFER 事件) */
    private final Long customerId;
    /** 当前 bot 会话 ID (将被关闭) */
    private final Long oldSessionId;
    /** 技能标签 (创建新人工会话时保留同一技能) */
    private final String skillTag;

    /**
     * 构造事件.
     * @param source 事件源 (一般是发布事件的 service 自身, this)
     * @param customerId 客户 ID
     * @param oldSessionId bot 会话 ID
     * @param skillTag 技能标签
     */
    public TransferToHumanEvent(Object source, Long customerId, Long oldSessionId, String skillTag) {
        super(source);                                                      // ApplicationEvent 必须有 source
        this.customerId = customerId;
        this.oldSessionId = oldSessionId;
        this.skillTag = skillTag;
    }
}