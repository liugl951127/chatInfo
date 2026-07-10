package com.chat.community.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chat.common.ai.LocalAiService;
import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.community.entity.CommunityPost;
import com.chat.community.entity.CommunityReply;
import com.chat.community.mapper.CommunityPostMapper;
import com.chat.community.mapper.CommunityReplyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CommunityService - 客户社区服务.
 * ----------------------------------------------------------------------------
 * 帖子/回复 CRUD + AI 质量分 (用自研 AI LocalAiService).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private final CommunityPostMapper postMapper;
    private final CommunityReplyMapper replyMapper;
    private final LocalAiService ai;

    /**
     * 发帖.
     */
    @Transactional
    public ApiResponse<CommunityPost> createPost(String title, String content, String category) {
        Long uid = UserContext.userId();
        if (title == null || title.isEmpty() || content == null || content.isEmpty()) {
            return ApiResponse.fail(400, "标题和内容不能为空");
        }
        CommunityPost p = new CommunityPost();
        p.setUserId(uid);
        p.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        p.setContent(content);
        p.setCategory(category == null ? "QA" : category);
        p.setViewCount(0);
        p.setReplyCount(0);
        p.setLikeCount(0);
        p.setStatus("PUBLISHED");
        p.setIsExpertAnswer(0);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        // AI 质量分 (异步, 这里同步简单算)
        try {
            var sent = ai.analyzeSentiment(content);
            p.setQualityScore((sent.getScore() > 0 || content.length() > 50) ? 70 : 50);
        } catch (Exception e) {
            p.setQualityScore(50);
        }
        postMapper.insert(p);
        return ApiResponse.ok(p);
    }

    /**
     * 列表.
     */
    public List<CommunityPost> listRecent(int limit) {
        return postMapper.listRecent(Math.min(Math.max(limit, 1), 100));
    }

    public List<CommunityPost> listByCategory(String category, int limit) {
        return postMapper.listByCategory(category, Math.min(Math.max(limit, 1), 100));
    }

    public List<CommunityPost> listByUser(Long uid) {
        return postMapper.listByUser(uid);
    }

    /**
     * 详情 + view++.
     */
    public CommunityPost getById(Long id) {
        CommunityPost p = postMapper.selectById(id);
        if (p != null) postMapper.incView(id);
        return p;
    }

    /**
     * 回复.
     */
    @Transactional
    public ApiResponse<CommunityReply> reply(Long postId, String content, Long parentId) {
        Long uid = UserContext.userId();
        if (content == null || content.isEmpty()) {
            return ApiResponse.fail(400, "内容不能为空");
        }
        CommunityPost p = postMapper.selectById(postId);
        if (p == null || !"PUBLISHED".equals(p.getStatus())) {
            return ApiResponse.fail(404, "帖子不存在");
        }
        CommunityReply r = new CommunityReply();
        r.setPostId(postId);
        r.setUserId(uid);
        r.setParentId(parentId);
        r.setContent(content);
        r.setLikeCount(0);
        r.setAccepted(0);
        r.setQualityScore(50);
        r.setCreatedAt(LocalDateTime.now());
        replyMapper.insert(r);
        postMapper.incReply(postId);
        return ApiResponse.ok(r);
    }

    /**
     * 列出帖子的回复.
     */
    public List<CommunityReply> listReplies(Long postId) {
        return replyMapper.listByPost(postId);
    }

    /**
     * 采纳回复.
     */
    @Transactional
    public ApiResponse<Void> acceptReply(Long replyId) {
        Long uid = UserContext.userId();
        CommunityReply r = replyMapper.selectById(replyId);
        if (r == null) return ApiResponse.fail(404, "回复不存在");
        CommunityPost p = postMapper.selectById(r.getPostId());
        if (p == null || !p.getUserId().equals(uid)) {
            return ApiResponse.fail(403, "只有发帖人可以采纳");
        }
        r.setAccepted(1);
        replyMapper.updateById(r);
        return ApiResponse.ok();
    }

    /**
     * 点赞.
     */
    @Transactional
    public ApiResponse<Void> likePost(Long id) {
        CommunityPost p = postMapper.selectById(id);
        if (p == null) return ApiResponse.fail(404, "帖子不存在");
        p.setLikeCount((p.getLikeCount() == null ? 0 : p.getLikeCount()) + 1);
        postMapper.updateById(p);
        return ApiResponse.ok();
    }
}