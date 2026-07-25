package com.chat.im.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chat.im.entity.ChatRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * ChatRecordMapper - 录像主表 DAO.
 * ----------------------------------------------------------------------------
 * V3.3 新增: atomicAppendChunk - SQL 原子 +1 chunkCount / +N bytes.
 * 解决: 高并发上传 / 重试风暴下 read-modify-write 丢失更新 (Lost Update).
 */
@Mapper
public interface ChatRecordMapper extends BaseMapper<ChatRecord> {

    /**
     * 原子追加分片统计.
     * <p>
     * SQL 端 UPDATE 同时 +1 chunkCount 和 +N total_bytes, 避免 Java 层 read-modify-write
     * 竞态. 即使多个 chunk 并发上传, 也保证最终值 = 实际成功 chunk 数 * size.
     *
     * @param recordId 录像 id
     * @param bytes    本次分片字节数
     * @return 影响行数 (1=成功, 0=record 不存在)
     */
    @Update("UPDATE chat_record SET chunk_count = chunk_count + 1, total_bytes = total_bytes + #{bytes} WHERE id = #{recordId}")
    int atomicAppendChunk(@Param("recordId") Long recordId, @Param("bytes") long bytes);
}
