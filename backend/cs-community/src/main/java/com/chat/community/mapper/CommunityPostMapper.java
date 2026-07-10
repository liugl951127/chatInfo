package com.chat.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.community.entity.CommunityPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface CommunityPostMapper extends BaseMapper<CommunityPost> {

    @Select("""
        SELECT * FROM community_post
        WHERE status = 'PUBLISHED'
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<CommunityPost> listRecent(int limit);

    @Select("""
        SELECT * FROM community_post
        WHERE status = 'PUBLISHED' AND category = #{category}
        ORDER BY created_at DESC
        LIMIT #{limit}
        """)
    List<CommunityPost> listByCategory(String category, int limit);

    @Select("""
        SELECT * FROM community_post
        WHERE status = 'PUBLISHED' AND user_id = #{uid}
        ORDER BY created_at DESC
        """)
    List<CommunityPost> listByUser(Long uid);

    @Update("UPDATE community_post SET view_count = view_count + 1 WHERE id = #{id}")
    int incView(Long id);

    @Update("UPDATE community_post SET reply_count = reply_count + 1 WHERE id = #{id}")
    int incReply(Long id);
}