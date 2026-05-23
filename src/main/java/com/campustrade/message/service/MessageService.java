package com.campustrade.message.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.message.model.Message;
import com.campustrade.message.repository.MessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageService {

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

    public List<Message> list(Long receiverId) {
        return messageRepository.findByReceiverId(receiverId);
    }

    public Message markRead(Long userId, Long messageId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> BizException.notFound("消息不存在"));
        if (!message.getReceiverId().equals(userId)) {
            throw BizException.forbidden("只能读取自己的消息");
        }
        message.setRead(true);
        return messageRepository.save(message);
    }
}
