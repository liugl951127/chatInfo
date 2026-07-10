package com.chat.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CommunityPost - 客户社区帖子.
 */
@Data
@TableName("community_post")
public class CommunityPost {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String category;            // QA / EXPERIENCE / FEEDBACK
    private String tags;                 // JSON 数组
    private Integer viewCount;
    private Integer replyCount;
    private Integer likeCount;
    private Integer qualityScore;        // AI 质量分 0-100
    private String status;               // PUBLISHED / HIDDEN / DELETED
    private Integer isExpertAnswer;      // 0/1
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}