package com.chat.im.service;

import com.chat.common.dto.MessageDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 离线消息存储 (Redis List, LTRIM 200)。
 * 用户上线后拉取 /api/im/offline/drain 消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineMessageStore {

    private static final int MAX_OFFLINE = 200;
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public void push(Long userId, MessageDTO msg) {
        try {
            String key = "chat:offline:" + userId;
            String json = mapper.writeValueAsString(msg);
            redis.opsForList().leftPush(key, json);
            redis.opsForList().trim(key, 0, MAX_OFFLINE - 1);
            redis.expire(key, TTL);
        } catch (JsonProcessingException e) {
            log.error("offline push failed", e);
        }
    }

    public List<MessageDTO> drain(Long userId) {
        String key = "chat:offline:" + userId;
        Long size = redis.opsForList().size(key);
        if (size == null || size == 0) return Collections.emptyList();
        List<String> raw = redis.opsForList().range(key, 0, size - 1);
        redis.delete(key);
        if (raw == null) return Collections.emptyList();
        return raw.stream().map(s -> {
            try { return mapper.readValue(s, MessageDTO.class); }
            catch (Exception e) { return null; }
        }).filter(java.util.Objects::nonNull).toList();
    }

    public long size(Long userId) {
        Long s = redis.opsForList().size("chat:offline:" + userId);
        return s == null ? 0 : s;
    }
}