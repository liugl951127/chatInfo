package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_record")
public class ChatRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Long userId;
    private String userRole;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** 结束原因: NORMAL / PAGE_CLOSE / PROCESS_KILLED / ERROR / USER_STOP */
    private String endReason;
    private Integer chunkCount;
    private Long totalBytes;
    /** 用户是否给予明确录制同意 (合规要求) */
    private Boolean consentGiven;
    private LocalDateTime createdAt;
}