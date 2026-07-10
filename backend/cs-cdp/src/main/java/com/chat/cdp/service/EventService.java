package com.chat.cdp.service;

import com.chat.cdp.entity.CdpEvent;
import com.chat.cdp.mapper.CdpEventMapper;
import com.chat.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * EventService - 客户事件采集服务.
 * ----------------------------------------------------------------------------
 * 职责:
 *   - recordEvent: 上报事件 (前端 SDK / 后端其他模块调用)
 *   - 落 cdp_event 表, 同时打 LOG 给规则引擎消费
 *   - 阶段 1: 同步落表 (后续阶段 2 改成 Redis Stream + 异步消费)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final CdpEventMapper eventMapper;
    private final ObjectMapper mapper;

    /**
     * 上报事件.
     * @param userId 用户 ID (从 UserContext 自动取)
     * @param eventType 事件类型 (e.g. page_view/chat_start)
     * @param payload 事件详情
     * @param sessionId 关联会话 (可选)
     */
    @Transactional
    public void recordEvent(Long userId, String eventType, Map<String, Object> payload, Long sessionId) {
        if (userId == null) userId = UserContext.userId();
        if (userId == null) {
            log.warn("[cdp] event ignored: no userId, type={}", eventType);
            return;
        }
        try {
            CdpEvent e = new CdpEvent();
            e.setUserId(userId);
            e.setSessionId(sessionId);
            e.setEventType(eventType);
            e.setPayload(mapper.writeValueAsString(payload == null ? Map.of() : payload));
            e.setOccurredAt(LocalDateTime.now());
            eventMapper.insert(e);
            log.debug("[cdp] event recorded: user={} type={} session={}", userId, eventType, sessionId);
        } catch (Exception ex) {
            log.error("[cdp] event record failed: type={}", eventType, ex);
        }
    }

    /**
     * 查询某事件时间窗口内的次数 (规则引擎用).
     */
    public long countRecent(Long userId, String eventType, LocalDateTime since) {
        Long cnt = eventMapper.countByUserAndType(userId, eventType, since);
        return cnt == null ? 0L : cnt;
    }
}