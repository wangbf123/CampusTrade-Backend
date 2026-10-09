package com.campustrade.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.message.model.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
    @Insert("""
            INSERT INTO message(event_id, receiver_id, type, title, content, related_id, is_read, created_at)
            VALUES(#{eventId}, #{receiverId}, #{type}, #{title}, #{content}, #{relatedId}, #{readFlag}, #{createdAt})
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIdempotently(Message message);

    // A current read sees the winning concurrent insert even under a pre-existing REPEATABLE READ snapshot.
    @Select("SELECT id, event_id, receiver_id, type, title, content, related_id, is_read AS read_flag, created_at FROM message WHERE event_id=#{eventId} FOR SHARE")
    Message selectCurrentByEventId(@Param("eventId") String eventId);
}
