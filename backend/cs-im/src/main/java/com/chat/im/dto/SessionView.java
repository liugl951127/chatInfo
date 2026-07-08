package com.chat.im.dto;

import com.chat.im.entity.ChatSession;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图 (在 ChatSession 基础上, 补充对端在线状态等前端需要的信息).
 *   - peerOnline: 对端 (坐席视角下的客户 / 客户视角下的坐席) 是否在线
 *   - peerLastSeen: 对端最近一次活动时间 (当前实现未持久化, 暂返回 null)
 */
@Data
public class SessionView {
    private Long id;
    private String sessionNo;
    private Long customerId;
    private Long agentId;
    private String skillTag;
    private String status;
    private Long transferredFromAgentId;
    private String transferReason;
    private String lastMessage;
    private Integer rating;
    private String ratingComment;
    private LocalDateTime ratedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime closedAt;

    /** 对端在线状态: true 在线 / false 离线 / null 未知 */
    private Boolean peerOnline;
    /** 是否机器人会话 (0=人工 1=智能客服) */
    private Integer isBot;

    public static SessionView from(ChatSession s, Boolean peerOnline) {
        SessionView v = new SessionView();
        v.id = s.getId();
        v.sessionNo = s.getSessionNo();
        v.customerId = s.getCustomerId();
        v.agentId = s.getAgentId();
        v.skillTag = s.getSkillTag();
        v.status = s.getStatus();
        v.transferredFromAgentId = s.getTransferredFromAgentId();
        v.transferReason = s.getTransferReason();
        v.lastMessage = s.getLastMessage();
        v.rating = s.getRating();
        v.ratingComment = s.getRatingComment();
        v.ratedAt = s.getRatedAt();
        v.createdAt = s.getCreatedAt();
        v.updatedAt = s.getUpdatedAt();
        v.closedAt = s.getClosedAt();
        v.peerOnline = peerOnline;
        v.isBot = s.getIsBot();
        return v;
    }
}