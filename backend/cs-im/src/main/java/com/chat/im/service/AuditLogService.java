package com.chat.im.service;

import com.chat.common.security.UserContext;
import com.chat.im.entity.AuditLog;
import com.chat.im.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计日志 (异步写入, 不阻塞业务).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /** 异步落库 */
    @Async
    public void log(Long userId, String action, String target, String detail) {
        try {
            AuditLog row = new AuditLog();
            row.setUserId(userId);
            row.setAction(action);
            row.setTarget(target);
            row.setDetail(detail);
            row.setIp(currentIp());
            auditLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("[audit] write failed: {}", e.getMessage());
        }
    }

    public void log(String action, String target, String detail) {
        log(UserContext.userId(), action, target, detail);
    }

    private String currentIp() {
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attr == null) return null;
            HttpServletRequest req = attr.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}