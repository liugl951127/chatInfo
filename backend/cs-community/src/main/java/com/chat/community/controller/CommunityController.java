package com.chat.community.controller;

import com.chat.common.api.ApiResponse;
import com.chat.community.entity.CommunityPost;
import com.chat.community.entity.CommunityReply;
import com.chat.community.service.CommunityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * CommunityController - 客户社区 REST 控制器.
 * ----------------------------------------------------------------------------
 * 端点:
 *   - POST /api/community/posts                    发帖
 *   - GET  /api/community/posts?limit=N           最新帖子
 *   - GET  /api/community/posts/category/{cat}     分类
 *   - GET  /api/community/posts/user/{uid}         某用户帖子
 *   - GET  /api/community/posts/{id}               详情 (view++)
 *   - POST /api/community/posts/{id}/reply         回复
 *   - GET  /api/community/posts/{id}/replies       列出回复
 *   - POST /api/community/replies/{id}/accept      采纳
 *   - POST /api/community/posts/{id}/like          点赞
 */
@Tag(name = "客户社区")
@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService service;

    @Operation(summary = "发帖")
    @PostMapping("/posts")
    public ApiResponse<CommunityPost> createPost(@RequestBody Map<String, Object> body) {
        String title = (String) body.get("title");
        String content = (String) body.get("content");
        String category = (String) body.get("category");
        return service.createPost(title, content, category);
    }

    @Operation(summary = "最新帖子")
    @GetMapping("/posts")
    public ApiResponse<List<CommunityPost>> listRecent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.listRecent(limit));
    }

    @Operation(summary = "分类列表")
    @GetMapping("/posts/category/{category}")
    public ApiResponse<List<CommunityPost>> listByCategory(@PathVariable String category,
                                                          @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.listByCategory(category, limit));
    }

    @Operation(summary = "某用户帖子")
    @GetMapping("/posts/user/{uid}")
    public ApiResponse<List<CommunityPost>> listByUser(@PathVariable Long uid) {
        return ApiResponse.ok(service.listByUser(uid));
    }

    @Operation(summary = "帖子详情")
    @GetMapping("/posts/{id}")
    public ApiResponse<CommunityPost> get(@PathVariable Long id) {
        return ApiResponse.ok(service.getById(id));
    }

    @Operation(summary = "回复")
    @PostMapping("/posts/{id}/reply")
    public ApiResponse<CommunityReply> reply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String content = (String) body.get("content");
        Long parentId = body.get("parentId") == null ? null
                : Long.valueOf(body.get("parentId").toString());
        return service.reply(id, content, parentId);
    }

    @Operation(summary = "列出帖子回复")
    @GetMapping("/posts/{id}/replies")
    public ApiResponse<List<CommunityReply>> listReplies(@PathVariable Long id) {
        return ApiResponse.ok(service.listReplies(id));
    }

    @Operation(summary = "采纳回复")
    @PostMapping("/replies/{id}/accept")
    public ApiResponse<Void> acceptReply(@PathVariable Long id) {
        return service.acceptReply(id);
    }

    @Operation(summary = "点赞")
    @PostMapping("/posts/{id}/like")
    public ApiResponse<Void> like(@PathVariable Long id) {
        return service.likePost(id);
    }
}