package com.chat.common.ai;

import java.util.*;

/**
 * SentimentAnalyzer - 情感分析器 (自研 AI).
 * ----------------------------------------------------------------------------
 * 算法: 词典 + 否定翻转 + 程度副词.
 *
 * 词典:
 *   - 25 个正面词 (好/棒/优秀/满意/感谢/谢谢/赞/开心/完美/...)
 *   - 27 个负面词 (差/烂/垃圾/投诉/退款/失望/生气/愤怒/...)
 *   - 9 个否定词 (不/没/无/非/未/别/莫/勿/no/not/n't)
 *   - 14 个程度副词 (很/非常/特别/极/超级/比较/稍微/...)
 *
 * 输出: score [-1, 1] + label (angry/sad/neutral/happy) + confidence [0, 1].
 *
 * 阶段 2 升级: 用更精细的情感分类 (5-7 类).
 */
public class SentimentAnalyzer {

    public enum Label { ANGRY, SAD, NEUTRAL, HAPPY }

    public record Result(double score, Label label, double confidence) {}

    private static final Set<String> POSITIVE = Set.of(
        "好", "棒", "优秀", "满意", "喜欢", "感谢", "谢谢", "赞", "开心", "高兴",
        "完美", "贴心", "专业", "快速", "高效", "nice", "good", "great", "love",
        "推荐", "支持", "ok", "对的", "没错"
    );

    private static final Set<String> NEGATIVE = Set.of(
        "差", "烂", "垃圾", "差评", "投诉", "退款", "退钱", "失望", "生气", "愤怒",
        "烦", "麻烦", "慢", "卡", "bug", "问题", "故障", "错误", "骗", "骗子",
        "退订", "取消", "不用了", "不行", "不能", "不可", "没用", "浪费", "坑",
        "气死", "气人", "气", "死", "死了", "讨厌", "可恶", "无语", "气炸",
        "过分", "出错", "挂了", "不通", "打不开", "崩溃",
        "bad", "terrible", "awful", "hate"
    );

    private static final Set<String> NEGATIONS = Set.of(
        "不", "没", "无", "未", "别", "莫", "勿", "no", "not", "n't"
        // "非" 删掉: 会跟"非常"冲突 (非是程度副词前缀, 不是否定)
    );

    private static final Map<String, Double> INTENSIFIERS = new HashMap<>();
    static {
        INTENSIFIERS.put("很", 1.5);
        INTENSIFIERS.put("非常", 2.0);
        INTENSIFIERS.put("特别", 1.8);
        INTENSIFIERS.put("极", 2.0);
        INTENSIFIERS.put("超级", 2.0);
        INTENSIFIERS.put("比较", 1.2);
        INTENSIFIERS.put("稍微", 0.7);
        INTENSIFIERS.put("一点", 0.8);
        INTENSIFIERS.put("太", 1.8);
        INTENSIFIERS.put("very", 1.5);
        INTENSIFIERS.put("extremely", 2.0);
        INTENSIFIERS.put("really", 1.5);
        INTENSIFIERS.put("so", 1.5);
    }

    public Result analyze(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(0.0, Label.NEUTRAL, 0.0);
        }
        String[] toks = tokenizeWithBigrams(text);
        double score = 0.0;
        int hits = 0;
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            double intensifier = 1.0;
            // 看前 1-2 个 token 是否有 intensifier
            for (int k = Math.max(0, i - 2); k < i; k++) {
                Double v = INTENSIFIERS.get(toks[k].toLowerCase());
                if (v != null) intensifier = Math.max(intensifier, v);
            }
            // 否定: 前 1-2 个有否定词
            boolean negated = false;
            for (int k = Math.max(0, i - 2); k < i; k++) {
                if (NEGATIONS.contains(toks[k].toLowerCase())) { negated = true; break; }
            }
            if (POSITIVE.contains(t.toLowerCase())) {
                score += intensifier;
                hits++;
                if (negated) score -= intensifier * 2;
            } else if (NEGATIVE.contains(t.toLowerCase())) {
                score -= intensifier;
                hits++;
                if (negated) score += intensifier * 2;
            }
        }
        if (hits == 0) {
            return new Result(0.0, Label.NEUTRAL, 0.0);
        }
        double normalized = Math.max(-1.0, Math.min(1.0, score / Math.max(hits, 1)));
        double confidence = Math.min(1.0, hits / 5.0);
        Label label;
        if (normalized < -0.3)      label = Label.ANGRY;
        else if (normalized < 0)    label = Label.SAD;
        else if (normalized > 0.3)  label = Label.HAPPY;
        else                        label = Label.NEUTRAL;
        return new Result(round(normalized, 3), label, round(confidence, 3));
    }

    /**
     * 滑窗分词: 英文按词 + 中文 1字/2字. 程度副词作为整体保留不被拆.
     * 例如 "气死我了" -> [气, 我, 死, 了, 气我, 我死, 死了]
     *      "非常好"   -> [非常, 常, 好, 常好]   (非 + 常 被识别为 1 个 intensifier)
     */
    private String[] tokenizeWithBigrams(String text) {
        List<String> toks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        List<String> cnSeq = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLatin(c) || Character.isDigit(c)) {
                flushCn(cnSeq, toks);
                cnSeq.clear();
                buf.append(c);
            } else {
                if (buf.length() > 0) { toks.add(buf.toString()); buf.setLength(0); }
                if (isCjk(c)) cnSeq.add(String.valueOf(c));
                else { flushCn(cnSeq, toks); cnSeq.clear(); }
            }
        }
        if (buf.length() > 0) toks.add(buf.toString());
        flushCn(cnSeq, toks);
        return toks.toArray(new String[0]);
    }

    private void flushCn(List<String> cn, List<String> out) {
        // 1) 优先识别 INTENSIFIERS 整词 (2字, 保留为单 token, 不拆 1字)
        Set<String> intensKeys = INTENSIFIERS.keySet();
        for (int i = 0; i < cn.size(); ) {
            boolean matched = false;
            if (i + 1 < cn.size()) {
                String bigram = cn.get(i) + cn.get(i + 1);
                if (intensKeys.contains(bigram)) {
                    out.add(bigram);                              // 作为整体
                    out.add(cn.get(i + 1));                       // 也保留 1字避免丢后续情感词
                    i += 2;
                    matched = true;
                }
            }
            if (!matched) {
                out.add(cn.get(i));
                i += 1;
            }
        }
        // 2) 补 2字 bigram (其它词, 用于其它分析)
        for (int i = 0; i < cn.size() - 1; i++) {
            String bg = cn.get(i) + cn.get(i + 1);
            if (!out.contains(bg)) out.add(bg);
        }
    }

    private boolean isLatin(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private double round(double v, int n) {
        double m = Math.pow(10, n);
        return Math.round(v * m) / m;
    }
}