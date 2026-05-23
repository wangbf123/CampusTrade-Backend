package com.campustrade.message.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.common.web.CurrentUserContext;
import com.campustrade.message.model.Message;
import com.campustrade.message.service.MessageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @GetMapping
    public ApiResponse<List<Message>> list() {
        return ApiResponse.ok(messageService.list(CurrentUserContext.require().id()));
    }

    @PutMapping("/{id}/read")
    public ApiResponse<Message> markRead(@PathVariable Long id) {
        return ApiResponse.ok(messageService.markRead(CurrentUserContext.require().id(), id));
    }
}
