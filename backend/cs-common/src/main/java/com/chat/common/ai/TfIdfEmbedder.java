package com.chat.common.ai;

import java.util.*;

/**
 * TfIdfEmbedder - TF-IDF 文本向量化 (自研 AI).
 * ----------------------------------------------------------------------------
 * 256 维向量, 中英混合分词, 哈希桶映射, L2 归一化.
 *
 * 优势:
 *   - 零依赖 (不调 BERT)
 *   - 0-1ms 响应
 *   - 可解释 (每个维度对应一类 token)
 *   - 阶段 2 升级: 替换为小型 BERT (int8 量化, ~30MB)
 */
public class TfIdfEmbedder {

    private final int dim;
    private final Map<String, Double> idf;

    public TfIdfEmbedder() {
        this(256);
    }

    public TfIdfEmbedder(int dim) {
        this.dim = dim;
        this.idf = new HashMap<>();
    }

    /** 用语料训练 IDF (可选, 简化版默认给所有 token idf=1.0) */
    public void fit(List<String> corpus) {
        int total = Math.max(corpus.size(), 1);
        Map<String, Integer> df = new HashMap<>();
        for (String doc : corpus) {
            Set<String> seen = new HashSet<>();
            for (String t : tokenize(doc)) seen.add(t.toLowerCase());
            for (String t : seen) df.merge(t, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : df.entrySet()) {
            idf.put(e.getKey(), Math.log((total + 1.0) / (e.getValue() + 1.0)) + 1.0);
        }
    }

    /** 文本 -> 256 维向量 (L2 归一化) */
    public float[] embed(String text) {
        float[] vec = new float[dim];
        if (text == null || text.isEmpty()) return vec;
        String[] toks = tokenize(text);
        if (toks.length == 0) return vec;
        // TF
        Map<String, Integer> tf = new HashMap<>();
        for (String t : toks) tf.merge(t.toLowerCase(), 1, Integer::sum);
        int n = toks.length;
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            int h = Math.abs(e.getKey().hashCode()) % dim;
            double idfVal = idf.getOrDefault(e.getKey(), 1.0);
            vec[h] += (float) ((e.getValue() / (double) n) * idfVal);
        }
        // L2 normalize
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < dim; i++) vec[i] = (float) (vec[i] / norm);
        return vec;
    }

    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (String t : texts) out.add(embed(t));
        return out;
    }

    /** 余弦相似度 */
    public double cosine(float[] a, float[] b) {
        if (a == null || b == null) return 0;
        int n = Math.min(a.length, b.length);
        double dot = 0;
        for (int i = 0; i < n; i++) dot += a[i] * b[i];
        return dot;   // 输入已 L2 归一化, 直接返回 dot
    }

    /** 简单分词: 英文按词 + 中文按字 */
    public String[] tokenize(String text) {
        List<String> toks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLatin(c) || Character.isDigit(c)) {
                buf.append(c);
            } else {
                if (buf.length() > 0) { toks.add(buf.toString()); buf.setLength(0); }
                if (isCjk(c)) toks.add(String.valueOf(c));
            }
        }
        if (buf.length() > 0) toks.add(buf.toString());
        return toks.toArray(new String[0]);
    }

    private boolean isLatin(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}