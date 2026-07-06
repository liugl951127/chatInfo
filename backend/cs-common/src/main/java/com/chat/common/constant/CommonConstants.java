package com.chat.common.constant;

/**
 * 通用常量。
 */
public final class CommonConstants {

    private CommonConstants() {}

    /** 用户角色 */
    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_AGENT    = "AGENT";
    public static final String ROLE_SYSTEM   = "SYSTEM";

    /** 会话状态 */
    public static final String SESSION_WAITING = "WAITING";
    public static final String SESSION_ACTIVE  = "ACTIVE";
    public static final String SESSION_CLOSED  = "CLOSED";

    /** 消息类型 */
    public static final String MSG_TEXT   = "TEXT";
    public static final String MSG_IMAGE  = "IMAGE";
    public static final String MSG_FILE   = "FILE";
    public static final String MSG_SYSTEM = "SYSTEM";

    /** JWT Header */
    public static final String AUTH_HEADER = "Authorization";
    public static final String AUTH_PREFIX = "Bearer ";

    /** 用户上下文 (Spring Request attribute key) */
    public static final String CTX_USER_ID    = "ctx.userId";
    public static final String CTX_USERNAME   = "ctx.username";
    public static final String CTX_ROLE       = "ctx.role";
    public static final String CTX_NICKNAME   = "ctx.nickname";

    /** Redis key 前缀 */
    public static final String REDIS_SESSION_QUEUE   = "chat:queue:waiting";          // 等待分配的会话 (List<Long> sessionId)
    public static final String REDIS_AGENT_ONLINE    = "chat:agent:online";            // 在线坐席 set
    public static final String REDIS_AGENT_SESSION   = "chat:agent:session:";          // 坐席当前会话 hash agentId -> sessionId
    public static final String REDIS_CUSTOMER_SESSION = "chat:customer:session:";      // 客户当前会话 customerId -> sessionId
    public static final String REDIS_WS_PUSH_CHANNEL = "chat:ws:push";                // 跨实例 WS 推送通道前缀
    public static final String REDIS_OFFLINE_MSG     = "chat:offline:";                // 离线消息 list 前缀
}