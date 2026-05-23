package com.campustrade.message.repository;

import com.campustrade.message.model.Message;

import java.util.List;
import java.util.Optional;

public interface MessageRepository {

    Message save(Message message);

    List<Message> findByReceiverId(Long receiverId);

    Optional<Message> findById(Long id);

    Optional<Message> findByEventId(String eventId);
}
