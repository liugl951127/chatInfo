package com.chat.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.community.entity.CommunityReply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityReplyMapper extends BaseMapper<CommunityReply> {

    @Select("""
        SELECT * FROM community_reply
        WHERE post_id = #{postId}
        ORDER BY accepted DESC, quality_score DESC, created_at ASC
        """)
    List<CommunityReply> listByPost(Long postId);
}