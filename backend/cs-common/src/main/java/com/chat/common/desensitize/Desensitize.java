package com.chat.common.desensitize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Desensitize - 数据脱敏注解.
 * ----------------------------------------------------------------------------
 * 用途: 标注在 DTO/Entity 字段上, Jackson 序列化时自动脱敏.
 * 支持: 6 种规则.
 *   - MOBILE: 138****5678
 *   - EMAIL:  a***@example.com
 *   - ID_CARD: 110***********1234
 *   - NAME:   张* (中文 1 字 + *) / Zhang*** (英文 5+ 字符)
 *   - PASSWORD: ******
 *   - BANK_CARD: 6222 **** **** 1234
 *
 * 用法: 在 DTO 字段上 @Desensitize(type = Desensitize.Type.MOBILE)
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Desensitize {
    Type value() default Type.DEFAULT;

    enum Type {
        DEFAULT,
        MOBILE,      // 手机号
        EMAIL,       // 邮箱
        ID_CARD,     // 身份证
        NAME,        // 姓名
        PASSWORD,    // 密码
        BANK_CARD    // 银行卡
    }
}