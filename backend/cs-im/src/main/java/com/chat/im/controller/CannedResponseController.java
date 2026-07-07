package com.chat.im.controller;

import com.chat.common.api.ApiResponse;
import com.chat.im.entity.CannedResponse;
import com.chat.im.service.CannedResponseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "快捷回复")
@RestController
@RequestMapping("/api/im/canned")
@RequiredArgsConstructor
public class CannedResponseController {

    private final CannedResponseService service;

    @Operation(summary = "列出快捷回复 (按技能过滤)")
    @GetMapping("/list")
    public ApiResponse<List<CannedResponse>> list(@RequestParam(required = false) String skill) {
        return service.list(skill);
    }

    @Operation(summary = "创建快捷回复")
    @PostMapping("/create")
    public ApiResponse<CannedResponse> create(@RequestParam(required = false) String skill,
                                              @RequestParam String title,
                                              @RequestParam String content) {
        return service.create(skill, title, content);
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }
}