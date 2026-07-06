package com.chat.common.security;

import com.chat.common.constant.CommonConstants;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前请求用户上下文工具。
 * <p>
 * 由 WebMvc 拦截器或 GatewayFilter 注入,业务代码可直接静态读取。
 */
public final class UserContext {

    private UserContext() {}

    public static void set(Long userId, String username, String role, String nickname) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra == null) {
            return;
        }
        ra.setAttribute(CommonConstants.CTX_USER_ID,    userId,    RequestAttributes.SCOPE_REQUEST);
        ra.setAttribute(CommonConstants.CTX_USERNAME,   username,  RequestAttributes.SCOPE_REQUEST);
        ra.setAttribute(CommonConstants.CTX_ROLE,       role,      RequestAttributes.SCOPE_REQUEST);
        ra.setAttribute(CommonConstants.CTX_NICKNAME,   nickname,  RequestAttributes.SCOPE_REQUEST);
    }

    public static Long userId() {
        return (Long) get(CommonConstants.CTX_USER_ID);
    }

    public static String username() {
        return (String) get(CommonConstants.CTX_USERNAME);
    }

    public static String role() {
        return (String) get(CommonConstants.CTX_ROLE);
    }

    public static String nickname() {
        return (String) get(CommonConstants.CTX_NICKNAME);
    }

    private static Object get(String key) {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        return ra == null ? null : ra.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
    }
}