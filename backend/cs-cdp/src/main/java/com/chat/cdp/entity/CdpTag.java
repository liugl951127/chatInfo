package com.chat.cdp.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CdpTag - 客户标签.
 * ----------------------------------------------------------------------------
 * 标签 key-value 存储, 每条代表一个计算结果.
 * 阶段 1 实现 30 个基础标签 (见 TagService).
 */
@Data
@TableName("cdp_tag")
public class CdpTag {

    private Long userId;
    private String tagKey;
    private String tagValue;
    private LocalDateTime computedAt;
}