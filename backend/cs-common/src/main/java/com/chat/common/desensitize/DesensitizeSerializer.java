package com.chat.common.desensitize;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

/**
 * DesensitizeSerializer - 脱敏 Jackson 序列化器.
 * ----------------------------------------------------------------------------
 * 自动读取字段上的 @Desensitize 注解, 按规则脱敏后写入 JSON.
 * 由 DesensitizeModule 注册.
 */
public class DesensitizeSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final Desensitize.Type type;

    public DesensitizeSerializer(Desensitize.Type type) {
        this.type = type == null ? Desensitize.Type.DEFAULT : type;
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
            throws JsonMappingException {
        if (property == null) return this;
        Desensitize ann = property.getAnnotation(Desensitize.class);
        if (ann == null) return this;
        return new DesensitizeSerializer(ann.value());
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider sp) throws IOException {
        if (value == null) { gen.writeNull(); return; }
        gen.writeString(mask(value, type));
    }

    /** 脱敏入口 */
    public static String mask(String value, Desensitize.Type type) {
        if (value == null || value.isEmpty()) return value;
        switch (type) {
            case MOBILE:
                return value.matches("^1[3-9]\\d{9}$")
                    ? value.substring(0, 3) + "****" + value.substring(7) : value;
            case EMAIL: {
                int at = value.indexOf('@');
                if (at <= 0) return value;
                String local = value.substring(0, at);
                if (local.length() <= 1) return "*" + value.substring(at);
                return local.charAt(0) + "***" + value.substring(at);
            }
            case ID_CARD:
                return value.length() >= 15
                    ? value.substring(0, 4) + "*".repeat(Math.max(0, value.length() - 8)) + value.substring(value.length() - 4)
                    : value;
            case NAME:
                if (value.length() == 0) return value;
                if (value.length() == 1) return value;
                // 中日韩 1 字 + *, 西方 5+ 字符截断
                char first = value.charAt(0);
                boolean isCjk = first >= 0x4E00 && first <= 0x9FFF;
                if (isCjk) return first + "*";
                return value.length() <= 4 ? value : value.substring(0, 4) + "***";
            case PASSWORD:
                return "******";
            case BANK_CARD:
                return value.length() >= 8
                    ? value.substring(0, 4) + " **** **** " + value.substring(value.length() - 4)
                    : value;
            default:
                return value;
        }
    }
}