package com.chat.community.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;             // MP QueryWrapper
import com.chat.common.ai.LocalAiService;                                     // 自研 AI (情感分析)
import com.chat.common.api.ApiResponse;                                        // 统一响应
import com.chat.common.security.UserContext;                                   // 当前用户 ThreadLocal
import com.chat.community.entity.CommunityPost;                               // 帖子实体
import com.chat.community.entity.CommunityReply;                              // 回复实体
import com.chat.community.mapper.CommunityPostMapper;                         // 帖子 DAO
import com.chat.community.mapper.CommunityReplyMapper;                        // 回复 DAO
import lombok.RequiredArgsConstructor;                                        // final 字段注入
import lombok.extern.slf4j.Slf4j;                                              // 日志
import org.springframework.stereotype.Service;                                 // Spring Bean
import org.springframework.transaction.annotation.Transactional;                // 事务

import java.time.LocalDateTime;                                                 // 时间戳
import java.util.List;                                                          // 列表

/**
 * CommunityService - 客户社区 (问答) 服务.
 * ----------------------------------------------------------------------------
 * 帖子/回复 CRUD + AI 质量分 (用自研 AI LocalAiService 评估内容质量).
 *
 * 数据模型 (双层结构):
 *   - CommunityPost (帖子, 1 层): 一篇问答/讨论的根
 *   - CommunityReply (回复, 2 层): 帖子的回答/评论
 *     - parentId: 可空, 实现简单的"楼中楼"层级
 *     - accepted: 0/1, 是否被发帖人采纳为"最佳答案"
 *
 * 核心能力:
 *   - 发帖/回复 (CRUD)
 *   - acceptReply: 接受答案 (仅发帖人可调, 标记 best answer)
 *   - 点赞 (likePost)
 *   - 详情 + view++ (浏览量自增)
 *   - 分类/用户 列表
 *   - AI 质量分 (用情感分 + 长度启发)
 *
 * 设计意图:
 *   - 接受答案权限: 仅发帖人 (避免乱采纳)
 *   - 视图累加: 浏览量每次 +1 (用 SQL 原子自增, 避免并发覆盖)
 *   - 质量分: AI 情感分 + 长度阈值, 启发式评分 (0-100)
 *   - 软删除: 暂不实现, 用 status=DELETED 标识 (后续扩展)
 *
 * @author V3 Team
 * @since 2025-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    /** 帖子 DAO */
    private final CommunityPostMapper postMapper;
    /** 回复 DAO */
    private final CommunityReplyMapper replyMapper;
    /** 自研 AI (情感分析) */
    private final LocalAiService ai;

    /**
     * 发帖.
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验: 登录 + 标题/内容非空
     *   step 2: 构建帖子实体 (截断标题 200 字符)
     *   step 3: AI 质量分 (情感 + 长度启发)
     *   step 4: 持久化
     *
     * @param title    帖子标题. 业务含义: 问题/讨论的概要
     *                 取值范围: 1-200 字符 (超出截断)
     *                 影响: 列表展示, 搜索索引
     * @param content  帖子内容. 业务含义: 详细描述
     *                 取值范围: 任意字符串
     *                 影响: 详情展示, 长度影响质量分
     * @param category 分类. 业务含义: 板块 (QA/DISCUSS/ANNOUNCEMENT)
     *                 取值范围: 业务自定义, 默认 "QA"
     *                 影响: 分类筛选
     * @return ApiResponse 包装的 CommunityPost
     *         - 成功: code=0, data=持久化后的帖子 (含 id/createdAt)
     *         - 失败: 400(空标题/内容) / 401(未登录)
     */
    @Transactional
    public ApiResponse<CommunityPost> createPost(String title, String content, String category) {
        // 1) 鉴权 + 校验
        Long uid = UserContext.userId();
        if (title == null || title.isEmpty() || content == null || content.isEmpty()) {
            return ApiResponse.fail(400, "标题和内容不能为空");
        }
        // 2) 构建实体
        CommunityPost p = new CommunityPost();
        p.setUserId(uid);                                                      // 作者
        // 标题截断 200 字符 (避免 DB 截断异常)
        p.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        p.setContent(content);
        p.setCategory(category == null ? "QA" : category);                    // 默认问答板块
        // 初始统计 (0)
        p.setViewCount(0);
        p.setReplyCount(0);
        p.setLikeCount(0);
        p.setStatus("PUBLISHED");                                              // 直接发布 (无审核)
        p.setIsExpertAnswer(0);                                                // 非专家回答
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        // 3) AI 质量分 (启发式: 情感 + 长度)
        try {
            // 情感分析: 越积极分越高
            var sent = ai.analyzeSentiment(content);
            // 规则: 情感 > 0 或长度 > 50 → 70 分, 否则 50 分
            p.setQualityScore((sent.getScore() > 0 || content.length() > 50) ? 70 : 50);
        } catch (Exception e) {
            // AI 异常兜底: 给默认分
            p.setQualityScore(50);
        }
        // 4) 持久化
        postMapper.insert(p);
        return ApiResponse.ok(p);
    }

    /**
     * 列表 (按时间倒序, 全分类).
     *
     * @param limit 返回条数. 业务含义: 分页大小
     *              取值范围: 1-100 (自动 clamp)
     *              影响: 决定返回多少条
     * @return 帖子列表
     */
    public List<CommunityPost> listRecent(int limit) {
        // DAO 内部已 order by created_at desc, limit clamp
        return postMapper.listRecent(Math.min(Math.max(limit, 1), 100));
    }

    /**
     * 按分类列表.
     *
     * @param category 分类名. 业务含义: 板块筛选
     *                 取值范围: 自定义字符串
     *                 影响: 仅返该分类的帖子
     * @param limit    返回条数
     * @return 帖子列表
     */
    public List<CommunityPost> listByCategory(String category, int limit) {
        return postMapper.listByCategory(category, Math.min(Math.max(limit, 1), 100));
    }

    /**
     * 按用户列表 (我的帖子).
     *
     * @param uid 用户 ID. 业务含义: 作者筛选
     *            取值范围: Long > 0
     *            影响: 仅返该用户发的帖子
     * @return 该用户的所有帖子
     */
    public List<CommunityPost> listByUser(Long uid) {
        return postMapper.listByUser(uid);
    }

    /**
     * 详情 + view++.
     * <p>
     * 每次访问都自增 view_count, 用 SQL 原子操作避免并发覆盖.
     *
     * @param id 帖子 ID. 业务含义: 要查看的帖子
     *           取值范围: Long > 0
     *           影响: 触发 view_count + 1
     * @return 帖子实体 (无则 null)
     */
    public CommunityPost getById(Long id) {
        // 查详情
        CommunityPost p = postMapper.selectById(id);
        if (p != null) {
            // 存在则 view++ (SQL 原子操作, 并发安全)
            postMapper.incView(id);
        }
        return p;
    }

    /**
     * 回复帖子 (双层结构第二层).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 校验: 登录 + 内容非空 + 帖子存在 + 已发布
     *   step 2: 构建回复实体 (含 parentId 楼中楼)
     *   step 3: 持久化 + 帖子的 reply_count + 1
     *
     * @param postId   帖子 ID. 业务含义: 要回复的帖子
     *                 取值范围: Long > 0 且存在
     *                 影响: 帖子 reply_count + 1
     * @param content  回复内容. 业务含义: 回答/评论
     *                 取值范围: 任意字符串
     *                 影响: 详情展示
     * @param parentId 父回复 ID (可空). 业务含义: 楼中楼的父级
     *                 取值范围: Long > 0 或 null
     *                 影响: null=顶级回复, 非空=楼中楼
     * @return ApiResponse 包装的 CommunityReply
     *         - 成功: code=0, data=持久化后的回复
     *         - 失败: 400(空内容) / 404(帖子不存在)
     */
    @Transactional
    public ApiResponse<CommunityReply> reply(Long postId, String content, Long parentId) {
        // 1) 鉴权 + 校验
        Long uid = UserContext.userId();
        if (content == null || content.isEmpty()) {
            return ApiResponse.fail(400, "内容不能为空");
        }
        // 帖子校验
        CommunityPost p = postMapper.selectById(postId);
        if (p == null || !"PUBLISHED".equals(p.getStatus())) {
            return ApiResponse.fail(404, "帖子不存在");
        }
        // 2) 构建回复实体
        CommunityReply r = new CommunityReply();
        r.setPostId(postId);                                                   // 所属帖子
        r.setUserId(uid);                                                      // 回复作者
        r.setParentId(parentId);                                               // 楼中楼父级
        r.setContent(content);
        r.setLikeCount(0);
        r.setAccepted(0);                                                      // 默认未采纳
        r.setQualityScore(50);                                                 // 默认质量分
        r.setCreatedAt(LocalDateTime.now());
        // 3) 持久化
        replyMapper.insert(r);
        // 帖子的回复数 + 1 (SQL 原子)
        postMapper.incReply(postId);
        return ApiResponse.ok(r);
    }

    /**
     * 列出帖子的所有回复 (按时间升序, 含楼中楼).
     *
     * @param postId 帖子 ID. 业务含义: 要列回复的帖子
     *               取值范围: Long > 0
     *               影响: 决定返回哪些回复
     * @return 回复列表 (DAO 内部按时间升序)
     */
    public List<CommunityReply> listReplies(Long postId) {
        return replyMapper.listByPost(postId);
    }

    /**
     * 接受回复为最佳答案 (核心方法, 仅发帖人可调).
     * ----------------------------------------------------------------------------
     * 算法:
     *   step 1: 查回复
     *   step 2: 查回复所属帖子
     *   step 3: 权限校验: 调用方必须是发帖人
     *   step 4: 标 accepted=1
     *
     * @param replyId 回复 ID. 业务含义: 要采纳的回答
     *                取值范围: Long > 0 且存在
     *                影响: 标 accepted=1
     * @return ApiResponse<Void>
     *         - 成功: code=0
     *         - 失败: 403(非发帖人) / 404(回复/帖子不存在)
     */
    @Transactional
    public ApiResponse<Void> acceptReply(Long replyId) {
        // 1) 拿回复
        Long uid = UserContext.userId();
        CommunityReply r = replyMapper.selectById(replyId);
        if (r == null) return ApiResponse.fail(404, "回复不存在");
        // 2) 拿帖子, 校验发帖人
        CommunityPost p = postMapper.selectById(r.getPostId());
        if (p == null || !p.getUserId().equals(uid)) {
            return ApiResponse.fail(403, "只有发帖人可以采纳");
        }
        // 3) 标 accepted=1 (这里没去重之前的 accepted, 允许多采纳, 后续可加 unique)
        r.setAccepted(1);
        replyMapper.updateById(r);
        return ApiResponse.ok();
    }

    /**
     * 点赞帖子 (浏览量自增).
     * <p>
     * 简化: 不做去重, 每次调用 + 1 (后续可加 user_id 去重表).
     *
     * @param id 帖子 ID. 业务含义: 要点赞的帖子
     *           取值范围: Long > 0 且存在
     *           影响: 帖子的 like_count + 1
     * @return ApiResponse<Void>
     *         - 成功: code=0
     *         - 失败: 404(帖子不存在)
     */
    @Transactional
    public ApiResponse<Void> likePost(Long id) {
        CommunityPost p = postMapper.selectById(id);
        if (p == null) return ApiResponse.fail(404, "帖子不存在");
        // like_count + 1 (读后写, 高并发下有 lost update, 生产建议用 SQL 原子)
        p.setLikeCount((p.getLikeCount() == null ? 0 : p.getLikeCount()) + 1);
        postMapper.updateById(p);
        return ApiResponse.ok();
    }
}
