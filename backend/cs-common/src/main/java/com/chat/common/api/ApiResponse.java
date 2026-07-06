package com.chat.common.api;

import lombok.Data;

/**
 * 统一 API 响应包装。
 *
 * <pre>
 * { "code": 0, "message": "ok", "data": { ... } }
 * </pre>
 */
@Data
public class ApiResponse<T> {

    /** 0 表示成功, 其它表示业务错误码 */
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> fail(String message) {
        return fail(500, message);
    }
}