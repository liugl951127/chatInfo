package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("canned_response")
public class CannedResponse {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String skillTag;
    private String title;
    private String content;
    private Long createdBy;
    private LocalDateTime createdAt;
}