package com.chat.im.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEvent;

/**
 * 客户在机器人会话中请求转人工事件.
 * MessageService 检测到后 publish, SessionService @EventListener 消费.
 * 解耦两个 service 避免循环依赖.
 */
@Getter
public class TransferToHumanEvent extends ApplicationEvent {
    private final Long customerId;
    private final Long oldSessionId;
    private final String skillTag;

    public TransferToHumanEvent(Object source, Long customerId, Long oldSessionId, String skillTag) {
        super(source);
        this.customerId = customerId;
        this.oldSessionId = oldSessionId;
        this.skillTag = skillTag;
    }
}
