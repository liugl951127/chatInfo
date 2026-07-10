package com.chat.video.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.video.entity.VideoSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VideoSessionMapper extends BaseMapper<VideoSession> {

    @Select("""
        SELECT * FROM video_session
        WHERE status IN ('INIT','CONNECTING','ACTIVE')
          AND (initiator_id = #{uid} OR peer_id = #{uid})
        ORDER BY created_at DESC
        """)
    List<VideoSession> findActiveByUser(Long uid);
}