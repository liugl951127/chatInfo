package com.chat.im.dto;

import lombok.Data;

@Data
public class UploadResult {
    private String fileUrl;       // 相对路径, 客户端拿到后通过 GET /api/im/file/raw?path=xxx 拉
    private String fileName;      // 原始文件名
    private Long fileSize;        // 字节
    private String mimeType;      // 如 application/pdf, image/png
    private String storagePath;   // 服务端存储路径 (调试用, 前端不用)
}