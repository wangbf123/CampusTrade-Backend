package com.campustrade.message.repository;

import com.campustrade.message.model.Message;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryMessageRepository implements MessageRepository {

    private final AtomicLong idGenerator = new AtomicLong(4000);
    private final Map<Long, Message> messages = new ConcurrentHashMap<>();
    private final Map<String, Long> eventIdIndex = new ConcurrentHashMap<>();

    @Override
    public synchronized Message save(Message message) {
        if (message.getEventId() != null && eventIdIndex.containsKey(message.getEventId())) {
            return messages.get(eventIdIndex.get(message.getEventId()));
        }
        if (message.getId() == null) {
            message.setId(idGenerator.incrementAndGet());
            message.setCreatedAt(LocalDateTime.now());
        }
        messages.put(message.getId(), message);
        if (message.getEventId() != null) {
            eventIdIndex.put(message.getEventId(), message.getId());
        }
        return message;
    }

    @Override
    public List<Message> findByReceiverId(Long receiverId) {
        return messages.values().stream()
                .filter(message -> message.getReceiverId().equals(receiverId))
                .sorted(Comparator.comparing(Message::getCreatedAt).reversed())
                .toList();
    }

    @Override
    public List<Message> findByReceiverId(Long receiverId, int page, int size) {
        return findByReceiverId(receiverId).stream()
                .skip(offset(page, size))
                .limit(Math.max(1, size))
                .toList();
    }

    @Override
    public Optional<Message> findById(Long id) {
        return Optional.ofNullable(messages.get(id));
    }

    @Override
    public Optional<Message> findByEventId(String eventId) {
        Long id = eventIdIndex.get(eventId);
        return id == null ? Optional.empty() : findById(id);
    }

    private long offset(int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, size);
        return (long) (safePage - 1) * safeSize;
    }
}
