package com.campustrade.message.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.message.mapper.MessageMapper;
import com.campustrade.message.model.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("mysql")
public class MysqlMessageRepository implements MessageRepository {

    private final MessageMapper messageMapper;

    public MysqlMessageRepository(MessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Override
    public Message save(Message message) {
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(LocalDateTime.now());
        }
        if (message.getEventId() != null) {
            messageMapper.insertIdempotently(message);
            return Optional.ofNullable(messageMapper.selectCurrentByEventId(message.getEventId()))
                    .orElseThrow(() -> new IllegalStateException("Idempotent notification insert returned no row"));
        }
        messageMapper.insert(message);
        return message;
    }

    @Override
    public List<Message> findByReceiverId(Long receiverId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverId, receiverId)
                .orderByDesc(Message::getCreatedAt));
    }

    @Override
    public List<Message> findByReceiverId(Long receiverId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        long offset = (long) (safePage - 1) * safeSize;
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getReceiverId, receiverId)
                .orderByDesc(Message::getCreatedAt)
                .orderByDesc(Message::getId)
                .last("LIMIT " + offset + ", " + safeSize));
    }

    @Override
    public Optional<Message> findById(Long id) {
        return Optional.ofNullable(messageMapper.selectById(id));
    }

    @Override
    public Optional<Message> findByEventId(String eventId) {
        Message message = messageMapper.selectOne(new LambdaQueryWrapper<Message>()
                .eq(Message::getEventId, eventId)
                .last("LIMIT 1"));
        return Optional.ofNullable(message);
    }
}
