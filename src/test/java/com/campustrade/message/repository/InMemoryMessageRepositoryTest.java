package com.campustrade.message.repository;

import com.campustrade.message.model.Message;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryMessageRepositoryTest {

    @Test
    void shouldPageMessagesByNewestFirst() {
        InMemoryMessageRepository repository = new InMemoryMessageRepository();
        LocalDateTime base = LocalDateTime.now();
        repository.save(message(1L, 100L, base.minusMinutes(3)));
        repository.save(message(2L, 100L, base.minusMinutes(2)));
        repository.save(message(3L, 100L, base.minusMinutes(1)));
        repository.save(message(4L, 101L, base));

        List<Message> page = repository.findByReceiverId(100L, 1, 2);

        assertEquals(List.of(3L, 2L), page.stream().map(Message::getId).toList());
    }

    private Message message(Long id, Long receiverId, LocalDateTime createdAt) {
        Message message = new Message();
        message.setId(id);
        message.setEventId("event-" + id);
        message.setReceiverId(receiverId);
        message.setType("ORDER_COMPLETED");
        message.setTitle("Order completed");
        message.setContent("Remember to review");
        message.setRelatedId(300L + id);
        message.setRead(false);
        message.setCreatedAt(createdAt);
        return message;
    }
}
