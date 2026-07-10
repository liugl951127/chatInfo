package com.chat.voice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.voice.entity.VoiceCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface VoiceCallMapper extends BaseMapper<VoiceCall> {

    @Select("""
        SELECT * FROM voice_call
        WHERE caller_id = #{uid} AND status IN ('RINGING','CONNECTED','ON_HOLD')
        ORDER BY created_at DESC
        """)
    List<VoiceCall> findActiveByCaller(Long uid);
}