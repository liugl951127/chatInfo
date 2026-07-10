package com.chat.cdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.cdp.entity.CdpTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CdpTagMapper extends BaseMapper<CdpTag> {

    @Select("SELECT * FROM cdp_tag WHERE user_id = #{userId}")
    List<CdpTag> findByUserId(@Param("userId") Long userId);
}