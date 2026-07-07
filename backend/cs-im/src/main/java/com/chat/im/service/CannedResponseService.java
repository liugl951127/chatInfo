package com.chat.im.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chat.common.api.ApiResponse;
import com.chat.common.security.UserContext;
import com.chat.im.entity.CannedResponse;
import com.chat.im.mapper.CannedResponseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CannedResponseService {

    private final CannedResponseMapper mapper;

    /**
     * 列出快捷回复:
     *   - skill 不为空: 返回通用 (skill_tag IS NULL) + 该技能的
     *   - skill 为空: 返回所有 (按技能分组)
     */
    public ApiResponse<List<CannedResponse>> list(String skillTag) {
        LambdaQueryWrapper<CannedResponse> q = new LambdaQueryWrapper<>();
        if (skillTag != null && !skillTag.isEmpty()) {
            q.and(w -> w.isNull(CannedResponse::getSkillTag).or().eq(CannedResponse::getSkillTag, skillTag));
        }
        q.orderByAsc(CannedResponse::getSkillTag).orderByAsc(CannedResponse::getId);
        return ApiResponse.ok(mapper.selectList(q));
    }

    public ApiResponse<CannedResponse> create(String skillTag, String title, String content) {
        Long uid = UserContext.userId();
        CannedResponse r = new CannedResponse();
        r.setSkillTag(skillTag);
        r.setTitle(title);
        r.setContent(content);
        r.setCreatedBy(uid);
        mapper.insert(r);
        return ApiResponse.ok(r);
    }

    public ApiResponse<Void> delete(Long id) {
        mapper.deleteById(id);
        return ApiResponse.ok();
    }
}