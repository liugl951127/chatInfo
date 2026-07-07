package com.chat.im.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_record_chunk")
public class ChatRecordChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recordId;
    private Integer sequenceNo;
    private String mimeType;
    private Integer durationMs;
    private Integer byteSize;
    private String storagePath;
    private LocalDateTime uploadedAt;
}