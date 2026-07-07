package com.chat.im.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 用户在线状态变化事件.
 * 由 PresenceService 在用户上下线时发布, 由 WsPushService 监听后转推到对端.
 * 用 Spring 事件解耦 PresenceService 与 WsPushService, 避免 WebSocket 链路上的循环依赖.
 */
@Getter
public class PresenceChangedEvent extends ApplicationEvent {

    /** 用户 id */
    private final Long userId;
    /** 用户角色 */
    private final String role;
    /** true=上线, false=下线 */
    private final boolean online;

    public PresenceChangedEvent(Object source, Long userId, String role, boolean online) {
        super(source);
        this.userId = userId;
        this.role = role;
        this.online = online;
    }
}