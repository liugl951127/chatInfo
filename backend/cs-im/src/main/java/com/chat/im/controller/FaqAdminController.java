package com.chat.im.controller;

import com.chat.common.ai.FaqLearner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * FaqAdminController - FAQ 候选池管理 (V3.1 新增).
 * ----------------------------------------------------------------------------
 * 业务: 客户问的问题无答案时, 自动收集到 Redis 候选池.
 *       运营人员 (ADMIN) 可批量审核入库, 提升 FAQ 覆盖率.
 */
@Tag(name = "FAQ 管理")
@RestController
@RequestMapping("/api/im/faq")
@RequiredArgsConstructor
public class FaqAdminController {

    private final FaqLearner learner;

    @Operation(summary = "Top 候选 (按频次倒序)")
    @GetMapping("/candidates")
    public Set<Map<String, Object>> top(@RequestParam(defaultValue = "50") int n) {
        return learner.topN(n);
    }

    @Operation(summary = "清理低频候选 (score < threshold)")
    @DeleteMapping("/candidates/cleanup")
    public Map<String, Object> cleanup(@RequestParam(defaultValue = "3") int threshold) {
        long removed = learner.cleanup(threshold);
        return Map.of("removed", removed, "threshold", threshold);
    }
}
