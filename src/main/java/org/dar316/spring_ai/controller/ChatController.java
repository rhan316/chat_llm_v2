package org.dar316.spring_ai.controller;

import jakarta.validation.Valid;
import org.dar316.spring_ai.dto.chat.ChatRequest;
import org.dar316.spring_ai.dto.chat.ChatResponse;
import org.dar316.spring_ai.service.chat.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Objects;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = Objects.requireNonNull(chatService, "chatService must not be null");
    }

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ChatResponse chat(@Valid @RequestBody ChatRequest chatRequest) {
        String answer = chatService.chat(chatRequest);

        return new ChatResponse(chatRequest.conversationId(), answer);
    }

    @PostMapping(
            value = "/stream",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public Flux<String> streamChat(@Valid @RequestBody ChatRequest chatRequest) {
        return chatService.streamChat(chatRequest);
    }
}
