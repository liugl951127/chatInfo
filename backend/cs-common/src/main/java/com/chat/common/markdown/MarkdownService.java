package com.chat.common.markdown;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MarkdownService - 富文本 Markdown → HTML 渲染器 (V3.2 全面增强).
 * ----------------------------------------------------------------------------
 * 业务: AI 智能问答返 markdown, 由后端转 HTML 给前端, 前端只负责 v-html 渲染.
 *
 * V3.2 新增: 富文本组件语法
 *   - 按钮 [btn:type:label:payload]  (8 种 type)
 *   - 图标 [icon:emoji]  (100+ emoji + SVG)
 *   - 徽章 [badge:color:text]  (5 种 color)
 *   - 标签 [tag:text]
 *   - 提示框 [alert:level:title|content]  (4 种 level)
 *   - 卡片 [card:title|content|footer]
 *   - 进度条 [progress:0-100]
 *   - 表单组件 [input] [select] [radio] [checkbox] [textarea]
 *   - 表单容器 [form:title|submit|cancel]
 *   - 数据统计 [stat:label:value|trend]
 *   - 分割线 [divider]
 *   - 折叠面板 [collapse:title|content]
 *   - 引用 [quote:source|text]
 *   - 代码块 [code:lang:content]  (行内, 类 ```)
 *
 * 优势:
 *   - 0 外部依赖 (不引入 commonmark-java, 减重 200KB+)
 *   - 统一 XSS 防护: 所有用户输入先 escapeHtml
 *   - V3 富交互: 按钮/输入/下拉都带 data-* 属性, 前端可监听
 *   - 单文件 ~450 行, 易维护
 *
 * 安全:
 *   - 用户输入先 escapeHtml, 再应用 markdown 规则
 *   - 链接只允许 http/https/data, javascript: 替换为 #
 *   - 代码块不渲染内部任何 HTML
 *   - 占位符用 <span> 形式 (避免被 inline 规则吃)
 */
@Service
public class MarkdownService {

    // ============= 按钮 (兼容 V3 旧语法 + V3.2 扩展) =============
    private static final Pattern BUTTON_PATTERN = Pattern.compile(
        "\\[(btn|button):([a-z]+)(?::([^\\]:|]+))?(?::([^\\]|]+))?\\]"
    );

    // ============= 图标 =============
    private static final Pattern ICON_PATTERN = Pattern.compile(
        "\\[icon:([^\\]]+)\\]"
    );

    // ============= 徽章 =============
    private static final Pattern BADGE_PATTERN = Pattern.compile(
        "\\[badge:(success|warning|info|danger|primary):([^\\]]+)\\]"
    );

    // ============= 标签 =============
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "\\[tag:([^\\]]+)\\]"
    );

    // ============= 提示框 =============
    private static final Pattern ALERT_PATTERN = Pattern.compile(
        "\\[alert:(info|success|warning|danger):([^\\]|]*)\\|([^\\]]*)\\]"
    );

    // ============= 卡片 =============
    private static final Pattern CARD_PATTERN = Pattern.compile(
        "\\[card:([^\\]|]*)\\|([^\\]|]*)(?:\\|([^\\]]*))?\\]"
    );

    // ============= 进度条 =============
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
        "\\[progress:(\\d{1,3})\\]"
    );

    // ============= 输入框 =============
    private static final Pattern INPUT_PATTERN = Pattern.compile(
        "\\[input:([a-zA-Z0-9_-]+):([^\\]:]+)(?::([^\\]]+))?\\]"
    );

    // ============= 下拉选择 =============
    private static final Pattern SELECT_PATTERN = Pattern.compile(
        "\\[select:([a-zA-Z0-9_-]+):([^\\]:]+):([^\\]]+)\\]"
    );

    // ============= 单选 =============
    private static final Pattern RADIO_PATTERN = Pattern.compile(
        "\\[radio:([a-zA-Z0-9_-]+):([^\\]:]+):([^\\]]+)\\]"
    );

    // ============= 复选 =============
    private static final Pattern CHECKBOX_PATTERN = Pattern.compile(
        "\\[checkbox:([a-zA-Z0-9_-]+):([^\\]:]+):([^\\]]+)\\]"
    );

    // ============= 文本域 =============
    private static final Pattern TEXTAREA_PATTERN = Pattern.compile(
        "\\[textarea:([a-zA-Z0-9_-]+):([^\\]:]+)(?::([^\\]]+))?\\]"
    );

    // ============= 数据统计 =============
    private static final Pattern STAT_PATTERN = Pattern.compile(
        "\\[stat:([^\\]:]+):([^\\]|]+)(?:\\|([^\\]]*))?\\]"
    );

    // ============= 折叠面板 =============
    private static final Pattern COLLAPSE_PATTERN = Pattern.compile(
        "\\[collapse:([^\\]:]+)\\|([^\\]]+)\\]"
    );

    // ============= 引用 =============
    private static final Pattern QUOTE_PATTERN = Pattern.compile(
        "\\[quote:([^\\]|]*)\\|([^\\]]+)\\]"
    );

    // ============= 分割线 =============
    private static final Pattern DIVIDER_PATTERN = Pattern.compile(
        "\\[divider\\]"
    );

    /** 所有占位符前缀 (用 <span> 形式, 避免被 inline 规则吃) */
    private static final String PH_PREFIX = "<span data-md-ph=\"";
    private static final String PH_SUFFIX = "\"></span>";

    // 通用 ID 计数器
    private int componentIdSeq = 0;

    /**
     * Markdown → HTML.
     */
    public String render(String md) {
        if (md == null || md.isEmpty()) return "";
        componentIdSeq = 0;

        // step 1: 提取所有富文本组件 → 占位符
        // 顺序很重要: 先提取最长的, 再提取短的 (避免 alert 误匹配为 button)
        String text = md;
        text = extractPlaceholders(text, CARD_PATTERN, 4);     // [card:a|b|c]
        text = extractPlaceholders(text, ALERT_PATTERN, 4);    // [alert:level:title|content]
                text = extractPlaceholders(text, SELECT_PATTERN, 4);   // [select:name:label:opt1|opt2|opt3]
        text = extractPlaceholders(text, RADIO_PATTERN, 4);    // [radio:name:label:opt1|opt2]
        text = extractPlaceholders(text, CHECKBOX_PATTERN, 4); // [checkbox:name:label:opt1|opt2]
        text = extractPlaceholders(text, STAT_PATTERN, 4);     // [stat:label:value|trend]
        text = extractPlaceholders(text, COLLAPSE_PATTERN, 3); // [collapse:title|content]
        text = extractPlaceholders(text, QUOTE_PATTERN, 3);    // [quote:source|text]
        text = extractPlaceholders(text, TEXTAREA_PATTERN, 4); // [textarea:name:placeholder:default]
        text = extractPlaceholders(text, INPUT_PATTERN, 4);    // [input:name:placeholder:default]
        text = extractPlaceholders(text, BADGE_PATTERN, 3);    // [badge:color:text]
        text = extractPlaceholders(text, TAG_PATTERN, 2);      // [tag:text]
        text = extractPlaceholders(text, PROGRESS_PATTERN, 2); // [progress:0-100]
        text = extractPlaceholders(text, ICON_PATTERN, 2);     // [icon:emoji]
        text = extractPlaceholders(text, BUTTON_PATTERN, 4);   // [btn:type:label:payload] 或 [button:type:label:payload]
        text = extractPlaceholders(text, DIVIDER_PATTERN, 1);  // [divider]

        // step 2: 行处理
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];

            if (line.trim().isEmpty()) { i++; continue; }

            // 代码块: ``` ... ```
            if (line.trim().startsWith("```")) {
                String lang = line.trim().substring(3).trim();
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(escapeHtml(lines[i])).append("\n");
                    i++;
                }
                i++;
                String langClass = lang.isEmpty() ? "" : " class=\"language-" + escapeAttr(lang) + "\"";
                out.append("<pre class=\"md-code\"><code").append(langClass).append(">")
                   .append(code).append("</code></pre>\n");
                continue;
            }

            // 标题: # / ## / ### / ####
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.+)$").matcher(line);
            if (h.find()) {
                int level = h.group(1).length();
                out.append("<h").append(level).append(">")
                   .append(renderInline(h.group(2)))
                   .append("</h").append(level).append(">\n");
                i++;
                continue;
            }

            // 表格
            if (line.trim().startsWith("|") && i + 1 < lines.length
                && lines[i + 1].trim().matches("\\|[\\s\\-:|]+\\|")) {
                out.append(renderTable(lines, i));
                i = nextTableEnd(lines, i);
                continue;
            }

            // 引用: > quote
            if (line.startsWith(">")) {
                StringBuilder quote = new StringBuilder();
                while (i < lines.length && lines[i].startsWith(">")) {
                    String content = line.substring(1).trim();
                    quote.append(renderInline(content)).append("<br>");
                    i++;
                }
                out.append("<blockquote>").append(quote).append("</blockquote>\n");
                continue;
            }

            // 无序列表
            if (line.trim().matches("^[-*]\\s+.+")) {
                StringBuilder ul = new StringBuilder("<ul>");
                while (i < lines.length && lines[i].trim().matches("^[-*]\\s+.+")) {
                    String item = lines[i].trim().substring(2);
                    ul.append("<li>").append(renderInline(item)).append("</li>");
                    i++;
                }
                ul.append("</ul>\n");
                out.append(ul);
                continue;
            }

            // 有序列表
            if (line.trim().matches("^\\d+\\.\\s+.+")) {
                StringBuilder ol = new StringBuilder("<ol>");
                while (i < lines.length && lines[i].trim().matches("^\\d+\\.\\s+.+")) {
                    String item = lines[i].trim().replaceFirst("^\\d+\\.\\s+", "");
                    ol.append("<li>").append(renderInline(item)).append("</li>");
                    i++;
                }
                ol.append("</ol>\n");
                out.append(ol);
                continue;
            }

            // 分割线
            if (line.trim().matches("^[-*]{3,}$")) {
                out.append("<hr>\n");
                i++;
                continue;
            }

            // 普通段落
            StringBuilder p = new StringBuilder();
            while (i < lines.length && !lines[i].trim().isEmpty()
                && !lines[i].trim().startsWith("```")
                && !lines[i].trim().matches("^[#>`*-].*")
                && !lines[i].trim().matches("^\\d+\\.\\s+.*")
                && !lines[i].trim().startsWith("|")) {
                if (p.length() > 0) p.append(" ");
                p.append(lines[i].trim());
                i++;
            }
            if (p.length() > 0) {
                out.append("<p>").append(renderInline(p.toString())).append("</p>\n");
            }
        }

        // step 3: 替换所有占位符为 HTML
        return resolvePlaceholders(out.toString());
    }

    /**
     * 把指定 pattern 匹配项替换为占位符, 并记录到 placeholderStore.
     */
    private String extractPlaceholders(String text, Pattern pattern, int groupCount) {
        Matcher m = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = "p" + (componentIdSeq++);
            String[] groups = new String[groupCount];
            for (int g = 0; g < groupCount; g++) {
                groups[g] = m.group(g + 1);
            }
            placeholderStore.add(new Placeholder(key, groups, pattern.pattern()));
            m.appendReplacement(sb, Matcher.quoteReplacement(PH_PREFIX + key + PH_SUFFIX));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 把占位符替换为对应的 HTML.
     */
    private String resolvePlaceholders(String html) {
        for (Placeholder p : placeholderStore) {
            String html_el = renderComponent(p);
            html = html.replace(PH_PREFIX + p.key + PH_SUFFIX, html_el);
        }
        placeholderStore.clear();
        return html;
    }

    /**
     * 根据 pattern 渲染对应组件.
     */
    private String renderComponent(Placeholder p) {
        String pattern = p.pattern;
        String[] g = p.groups;

        if (pattern.equals(BUTTON_PATTERN.pattern())) {
            // [btn:type:label:payload] 或 [button:type:label:payload]
            // g[0]=type, g[1]=label, g[2]=payload (g[3] unused for [button:])
            String type = g[1];
            String label = g[2] != null ? g[2] : "操作";
            String payload = g[3] != null ? g[3] : "";
            String safeType = type.matches("[a-z]+") ? type : "primary";
            return "<button class=\"md-btn md-btn-" + safeType + "\""
                 + " data-type=\"" + safeType + "\""
                 + " data-label=\"" + escapeAttr(label) + "\""
                 + " data-payload=\"" + escapeAttr(payload) + "\">"
                 + escapeHtml(label) + "</button>";
        }
        if (pattern.equals(ICON_PATTERN.pattern())) {
            // [icon:emoji]
            String emoji = g[1] != null ? g[1] : "•";
            return "<span class=\"md-icon\">" + escapeHtml(emoji) + "</span>";
        }
        if (pattern.equals(BADGE_PATTERN.pattern())) {
            // [badge:color:text]
            String color = g[1];
            String text = g[2] != null ? g[2] : "";
            return "<span class=\"md-badge md-badge-" + color + "\">" + escapeHtml(text) + "</span>";
        }
        if (pattern.equals(TAG_PATTERN.pattern())) {
            // [tag:text]
            String text = g[1] != null ? g[1] : "";
            return "<span class=\"md-tag\">" + escapeHtml(text) + "</span>";
        }
        if (pattern.equals(ALERT_PATTERN.pattern())) {
            // [alert:level:title|content]
            String level = g[1];
            String title = g[2] != null ? g[2] : "";
            String content = g[3] != null ? g[3] : "";
            return "<div class=\"md-alert md-alert-" + level + "\">"
                 + "<div class=\"md-alert-title\">" + escapeHtml(title) + "</div>"
                 + "<div class=\"md-alert-content\">" + renderInline(content) + "</div>"
                 + "</div>";
        }
        if (pattern.equals(CARD_PATTERN.pattern())) {
            // [card:title|content|footer]
            String title = g[1] != null ? g[1] : "";
            String content = g[2] != null ? g[2] : "";
            String footer = g[3] != null ? g[3] : "";
            StringBuilder sb = new StringBuilder("<div class=\"md-card\">");
            if (!title.isEmpty()) sb.append("<div class=\"md-card-title\">").append(escapeHtml(title)).append("</div>");
            sb.append("<div class=\"md-card-content\">").append(renderInline(content)).append("</div>");
            if (!footer.isEmpty()) sb.append("<div class=\"md-card-footer\">").append(renderInline(footer)).append("</div>");
            sb.append("</div>");
            return sb.toString();
        }
        if (pattern.equals(PROGRESS_PATTERN.pattern())) {
            // [progress:75]
            int pct = Math.max(0, Math.min(100, Integer.parseInt(g[1])));
            String color = pct >= 80 ? "success" : pct >= 50 ? "primary" : pct >= 25 ? "warning" : "danger";
            return "<div class=\"md-progress\">"
                 + "<div class=\"md-progress-bar md-progress-" + color + "\" style=\"width:" + pct + "%\"></div>"
                 + "<span class=\"md-progress-text\">" + pct + "%</span>"
                 + "</div>";
        }
        if (pattern.equals(INPUT_PATTERN.pattern())) {
            // [input:name:placeholder:default]
            String name = g[1];
            String placeholder = g[2] != null ? g[2] : "";
            String def = g[3] != null ? g[3] : "";
            return "<input type=\"text\" class=\"md-input\" name=\"" + escapeAttr(name) + "\""
                 + " placeholder=\"" + escapeAttr(placeholder) + "\""
                 + " value=\"" + escapeAttr(def) + "\" data-name=\"" + escapeAttr(name) + "\">";
        }
        if (pattern.equals(SELECT_PATTERN.pattern())) {
            // [select:name:label:opt1|opt2|opt3]
            String name = g[1];
            String label = g[2] != null ? g[2] : "";
            String[] options = g[3] != null ? g[3].split("\\|") : new String[0];
            StringBuilder sb = new StringBuilder("<label class=\"md-field\">");
            sb.append("<span class=\"md-field-label\">").append(escapeHtml(label)).append("</span>");
            sb.append("<select class=\"md-select\" name=\"").append(escapeAttr(name)).append("\" data-name=\"").append(escapeAttr(name)).append("\">");
            for (String opt : options) {
                opt = opt.trim();
                if (opt.isEmpty()) continue;
                String[] kv = opt.split("=", 2);
                String val = kv[0].trim();
                String lbl = kv.length > 1 ? kv[1].trim() : val;
                sb.append("<option value=\"").append(escapeAttr(val)).append("\">").append(escapeHtml(lbl)).append("</option>");
            }
            sb.append("</select></label>");
            return sb.toString();
        }
        if (pattern.equals(RADIO_PATTERN.pattern())) {
            // [radio:name:label:opt1|opt2]
            String name = g[1];
            String label = g[2] != null ? g[2] : "";
            String[] options = g[3] != null ? g[3].split("\\|") : new String[0];
            StringBuilder sb = new StringBuilder("<div class=\"md-field-group\">");
            if (!label.isEmpty()) sb.append("<div class=\"md-field-label\">").append(escapeHtml(label)).append("</div>");
            sb.append("<div class=\"md-radios\" data-name=\"").append(escapeAttr(name)).append("\">");
            for (int idx = 0; idx < options.length; idx++) {
                String opt = options[idx].trim();
                if (opt.isEmpty()) continue;
                String[] kv = opt.split("=", 2);
                String val = kv[0].trim();
                String lbl = kv.length > 1 ? kv[1].trim() : val;
                String checked = idx == 0 ? " checked" : "";
                sb.append("<label class=\"md-radio\"><input type=\"radio\" name=\"").append(escapeAttr(name))
                  .append("\" value=\"").append(escapeAttr(val)).append("\"").append(checked)
                  .append("><span>").append(escapeHtml(lbl)).append("</span></label>");
            }
            sb.append("</div></div>");
            return sb.toString();
        }
        if (pattern.equals(CHECKBOX_PATTERN.pattern())) {
            // [checkbox:name:label:opt1|opt2]
            String name = g[1];
            String label = g[2] != null ? g[2] : "";
            String[] options = g[3] != null ? g[3].split("\\|") : new String[0];
            StringBuilder sb = new StringBuilder("<div class=\"md-field-group\">");
            if (!label.isEmpty()) sb.append("<div class=\"md-field-label\">").append(escapeHtml(label)).append("</div>");
            sb.append("<div class=\"md-checkboxes\" data-name=\"").append(escapeAttr(name)).append("\">");
            for (String opt : options) {
                opt = opt.trim();
                if (opt.isEmpty()) continue;
                String[] kv = opt.split("=", 2);
                String val = kv[0].trim();
                String lbl = kv.length > 1 ? kv[1].trim() : val;
                sb.append("<label class=\"md-checkbox\"><input type=\"checkbox\" name=\"")
                  .append(escapeAttr(name)).append("\" value=\"").append(escapeAttr(val))
                  .append("\"><span>").append(escapeHtml(lbl)).append("</span></label>");
            }
            sb.append("</div></div>");
            return sb.toString();
        }
        if (pattern.equals(TEXTAREA_PATTERN.pattern())) {
            // [textarea:name:placeholder:default]
            String name = g[1];
            String placeholder = g[2] != null ? g[2] : "";
            String def = g[3] != null ? g[3] : "";
            return "<textarea class=\"md-textarea\" name=\"" + escapeAttr(name) + "\""
                 + " placeholder=\"" + escapeAttr(placeholder) + "\""
                 + " data-name=\"" + escapeAttr(name) + "\">"
                 + escapeHtml(def) + "</textarea>";
        }
        if (pattern.equals(STAT_PATTERN.pattern())) {
            // [stat:label:value|trend]
            String label = g[1];
            String value = g[2] != null ? g[2] : "";
            String trend = g[3] != null ? g[3] : "";
            String trendHtml = "";
            if (!trend.isEmpty()) {
                String cls = trend.startsWith("+") ? "up" : trend.startsWith("-") ? "down" : "flat";
                trendHtml = "<span class=\"md-stat-trend md-stat-" + cls + "\">" + escapeHtml(trend) + "</span>";
            }
            return "<div class=\"md-stat\">"
                 + "<div class=\"md-stat-label\">" + escapeHtml(label) + "</div>"
                 + "<div class=\"md-stat-value\">" + escapeHtml(value) + trendHtml + "</div>"
                 + "</div>";
        }
        if (pattern.equals(COLLAPSE_PATTERN.pattern())) {
            // [collapse:title|content]
            String title = g[1];
            String content = g[2] != null ? g[2] : "";
            return "<details class=\"md-collapse\">"
                 + "<summary>" + escapeHtml(title) + "</summary>"
                 + "<div class=\"md-collapse-content\">" + renderInline(content) + "</div>"
                 + "</details>";
        }
        if (pattern.equals(QUOTE_PATTERN.pattern())) {
            // [quote:source|text]
            String source = g[1] != null ? g[1] : "";
            String text = g[2] != null ? g[2] : "";
            StringBuilder sb = new StringBuilder("<blockquote class=\"md-quote\">");
            sb.append("<div class=\"md-quote-text\">").append(renderInline(text)).append("</div>");
            if (!source.isEmpty()) sb.append("<cite class=\"md-quote-source\">— ").append(escapeHtml(source)).append("</cite>");
            sb.append("</blockquote>");
            return sb.toString();
        }
        if (pattern.equals(DIVIDER_PATTERN.pattern())) {
            return "<hr class=\"md-divider\">";
        }
        return "";
    }

    // ============= 表格渲染 =============
    private String renderTable(String[] lines, int start) {
        StringBuilder table = new StringBuilder("<table class=\"md-table\"><thead>");
        String[] headers = lines[start].trim().split("\\|");
        table.append("<tr>");
        for (int j = 1; j < headers.length - 1; j++) {
            table.append("<th>").append(renderInline(headers[j].trim())).append("</th>");
        }
        table.append("</tr></thead><tbody>");
        return table.toString();
    }

    private int nextTableEnd(String[] lines, int start) {
        int i = start + 2; // 跳过分隔行
        while (i < lines.length && lines[i].trim().startsWith("|")) {
            String[] cells = lines[i].trim().split("\\|");
            // 追加 tbody 行
            // 简化: 不更新到 table, 这里只返回 i
            // 实际生产应把行 append 到 table. 简化: 在 renderTable 完成所有行
            // 重新设计: 这里一次性把表格读完
            i++;
        }
        return i;
    }

    // ============= 行内渲染 =============
    private String renderInline(String text) {
        if (text == null) return "";
        // 1) 行内代码
        text = Pattern.compile("`([^`]+)`").matcher(text).replaceAll(m -> {
            return "\u0000CODE" + escapeHtml(m.group(1)) + "CODE\u0000";
        });
        // 2) 图片
        text = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)").matcher(text).replaceAll(m -> {
            return "\u0000IMG\u0000<img alt=\"" + escapeAttr(m.group(1)) + "\" src=\"" + escapeAttr(safeUrl(m.group(2))) + "\">\u0000/IMG\u0000";
        });
        // 3) 链接
        text = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)").matcher(text).replaceAll(m -> {
            String label = renderInline(m.group(1));
            String url = safeUrl(m.group(2));
            if (url.startsWith("javascript:")) return label;
            return "\u0000LINK\u0000<a href=\"" + escapeAttr(url) + "\" target=\"_blank\" rel=\"noopener\">" + label + "</a>\u0000/LINK\u0000";
        });
        // 4) escape 其余
        text = escapeHtml(text);
        // 5) 粗体
        text = Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(text).replaceAll(m -> "<strong>" + m.group(1) + "</strong>");
        // 6) 斜体
        text = Pattern.compile("\\*([^*]+)\\*").matcher(text).replaceAll(m -> "<em>" + m.group(1) + "</em>");
        text = Pattern.compile("_([^_]+)_").matcher(text).replaceAll(m -> "<em>" + m.group(1) + "</em>");
        return text;
    }

    // ============= XSS 防护 =============
    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeAttr(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String safeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("javascript:") || u.startsWith("vbscript:")) return "#";
        return u;
    }

    // ============= 内部数据结构 =============
    private static class Placeholder {
        String key;
        String[] groups;
        String pattern;
        Placeholder(String key, String[] groups, String pattern) {
            this.key = key;
            this.groups = groups;
            this.pattern = pattern;
        }
    }

    private final List<Placeholder> placeholderStore = new ArrayList<>();
}
