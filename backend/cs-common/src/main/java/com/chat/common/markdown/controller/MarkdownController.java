package com.chat.common.markdown.controller;

import com.chat.common.markdown.MarkdownService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * MarkdownController - Markdown 渲染 REST 端点 (V3.2 新增).
 * ----------------------------------------------------------------------------
 * 业务: Agent 编辑 markdown 后, 实时调用此端点转 HTML, 前端预览.
 *   - 不依赖 AI, 纯 markdown → HTML
 *   - 复用 MarkdownService
 *
 * 端点:
 *   - POST /api/md/render  { markdown: "..." } → { html: "...", size: 1234 }
 *   - POST /api/md/preview { markdown: "..." } → { html: "..." }  (同 render, 语义清晰)
 */
@RestController
@RequestMapping("/api/md")
@RequiredArgsConstructor
public class MarkdownController {

    private final MarkdownService markdown;

    /**
     * Markdown → HTML 渲染.
     */
    @PostMapping("/render")
    public Map<String, Object> render(@RequestBody Map<String, String> body) {
        String md = body.getOrDefault("markdown", "");
        long t0 = System.currentTimeMillis();
        String html = markdown.render(md);
        long ms = System.currentTimeMillis() - t0;
        Map<String, Object> out = new HashMap<>();
        out.put("html", html);
        out.put("size", html.length());
        out.put("latencyMs", ms);
        return out;
    }

    /**
     * Markdown 预览 (同 render).
     */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, String> body) {
        return render(body);
    }
}
