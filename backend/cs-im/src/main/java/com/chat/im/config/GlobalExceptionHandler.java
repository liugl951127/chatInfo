package com.chat.im.config;

import com.chat.common.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegal(IllegalArgumentException ex) {
        return ApiResponse.fail(400, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Void> handleState(IllegalStateException ex) {
        return ApiResponse.fail(409, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleAll(Exception ex) {
        log.error("server error", ex);
        return ApiResponse.fail(500, "server error: " + ex.getMessage());
    }

    /** WebSocket 端异常, 推回给当前用户 */
    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public Map<String, Object> handleWs(Exception ex) {
        log.warn("[ws] handler error: {}", ex.getMessage());
        return Map.of("code", 500, "message", ex.getMessage());
    }
}