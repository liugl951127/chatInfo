package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * cs-im 端只读的坐席精简视图, 避免依赖 cs-auth 的实体。
 */
@Data
@TableName("user")
public class Agent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String skillTags;
    private Integer status;
}