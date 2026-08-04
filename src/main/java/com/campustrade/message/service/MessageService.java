package com.campustrade.message.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.message.model.Message;
import com.campustrade.message.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public void send(Long receiverId, String type, String title, String content, Long relatedId) {
        sendIfAbsent(null, receiverId, type, title, content, relatedId);
    }

    public void sendIfAbsent(String eventId, Long receiverId, String type, String title, String content, Long relatedId) {
        if (eventId != null && messageRepository.findByEventId(eventId).isPresent()) {
            return;
        }
        Message message = new Message();
        message.setEventId(eventId);
        message.setReceiverId(receiverId);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setRelatedId(relatedId);
        message.setRead(false);
        messageRepository.save(message);
    }

    public List<Message> list(Long receiverId, int page, int size) {
        return messageRepository.findByReceiverId(receiverId, safePage(page), safeSize(size));
    }

    public Message markRead(Long userId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> BizException.notFound("Message does not exist"));
        if (!message.getReceiverId().equals(userId)) {
            throw BizException.forbidden("Only your own messages can be read");
        }
        message.setRead(true);
        return messageRepository.save(message);
    }

    private int safePage(int page) {
        return Math.max(1, page);
    }

    private int safeSize(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }
}
