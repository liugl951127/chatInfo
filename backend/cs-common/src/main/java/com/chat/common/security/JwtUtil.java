package com.chat.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 签发与解析工具。
 * <p>
 * secret 必须 ≥ 32 字符 (HS256 要求)。
 */
public final class JwtUtil {

    private JwtUtil() {}

    private static final String CLAIM_UID  = "uid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NICK = "nick";

    /** 默认有效期 24h */
    public static final long DEFAULT_TTL_MS = 24 * 3600 * 1000L;

    public static String issue(String secret, Long userId, String username, String role, String nickname) {
        return issue(secret, userId, username, role, nickname, DEFAULT_TTL_MS);
    }

    public static String issue(String secret, Long userId, String username, String role, String nickname, long ttlMs) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_UID,  userId);
        claims.put(CLAIM_ROLE, role);
        claims.put(CLAIM_NICK, nickname);
        Date now = new Date();
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlMs))
                .signWith(key)
                .compact();
    }

    /** 解析失败时抛 JwtException */
    public static Claims parse(String secret, String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static boolean tryParse(String secret, String token) {
        try {
            parse(secret, token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public static Long uid(Claims c)   { return c.get(CLAIM_UID,  Long.class); }
    public static String role(Claims c) { return c.get(CLAIM_ROLE, String.class); }
    public static String nick(Claims c) { return c.get(CLAIM_NICK, String.class); }
}