package com.chat.common.security;

import com.chat.common.constant.CommonConstants;

/**
 * UserContext - 当前请求用户上下文工具.
 * ----------------------------------------------------------------------------
 * 存储: ThreadLocal (真正的线程局部, 不仅限于 Web 请求)
 * 由 WebMvc 拦截器 / GatewayFilter / @Async 事件 / MQ 消费者 注入.
 *
 * 异步线程 (如 @Async) 没有 RequestContextHolder, 之前用 RequestContextHolder
 * 会返 null. 改用 ThreadLocal 后, 异步线程也能持有上下文.
 *
 * 生命周期: 每个请求结束或异步任务结束后, 业务应调 clear() 避免线程复用泄漏.
 * (Spring 异步任务框架会自动管理 ThreadLocal 吗? 答: 不会, 需手动清理)
 */
public final class UserContext {

    private static final ThreadLocal<Long>    UID      = new ThreadLocal<>();
    private static final ThreadLocal<String>  USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String>  ROLE     = new ThreadLocal<>();
    private static final ThreadLocal<String>  NICK     = new ThreadLocal<>();

    private UserContext() {}

    public static void set(Long userId, String username, String role, String nickname) {
        if (userId != null)     UID.set(userId);     else UID.remove();
        if (username != null)   USERNAME.set(username); else USERNAME.remove();
        if (role != null)       ROLE.set(role);     else ROLE.remove();
        if (nickname != null)   NICK.set(nickname); else NICK.remove();
    }

    public static Long userId() { return UID.get(); }
    public static String username() { return USERNAME.get(); }
    public static String role() { return ROLE.get(); }
    public static String nickname() { return NICK.get(); }

    /** 清理当前线程上下文 (业务在异步任务完成时调用) */
    public static void clear() {
        UID.remove();
        USERNAME.remove();
        ROLE.remove();
        NICK.remove();
    }
}
