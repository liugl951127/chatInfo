package com.chat.im.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.im.entity.Agent;
import com.chat.im.mapper.UserMapper;
import com.chat.im.service.AgentStatusService;
import com.chat.im.service.PresenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "坐席")
@RestController
@RequestMapping("/api/im/agent")
@RequiredArgsConstructor
public class AgentController {

    private final UserMapper userMapper;
    private final PresenceService presenceService;
    private final AgentStatusService agentStatusService;

    @Operation(summary = "列出所有坐席 (含在线状态/技能) — 用于转接下拉")
    @GetMapping("/list")
    public ApiResponse<List<Map<String, Object>>> list() {
        Long uid = UserContext.userId();
        Set<String> onlineIds = presenceService.onlineAgents();
        List<Agent> all = userMapper.selectList(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getRole, "AGENT")
                .eq(Agent::getStatus, 1)
                .orderByAsc(Agent::getId));
        List<Map<String, Object>> result = all.stream().map(a -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", a.getId());
            m.put("nickname", a.getNickname());
            m.put("skillTags", a.getSkillTags());
            m.put("online", onlineIds != null && onlineIds.contains(String.valueOf(a.getId())));
            m.put("status", agentStatusService.getStatus(a.getId()));
            m.put("self", a.getId().equals(uid));
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.ok(result);
    }
}