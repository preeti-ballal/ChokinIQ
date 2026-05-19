package com.backend.chokiniq.controller;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

@LineMessageHandler
public class BotController {
    
    private final ChatModel chatModel;
    private final MessagingApiClient messagingApiClient;

    public BotController(ChatModel chatModel, MessagingApiClient messagingApiClient) {
        this.chatModel = chatModel;
        this.messagingApiClient = messagingApiClient;
    }

    @EventMapping
    public void handleTextMessageEvent(MessageEvent event) {
        // 1. Ensure incoming message is text
        if (!(event.message() instanceof TextMessageContent messageContent)) {
            return;
        }

        String userRawText = messageContent.text();
        System.out.println("=> => => => User sent: " + userRawText + "\n");

        // 2. Build the AI Persona & Prompt
        String systemText = "You are ChokinIQ, a witty and slightly sarcastic personal finance assistant. Keep your answers brief, fun, and always use emojis.";
        
        SystemMessage systemMessage = new SystemMessage(systemText);
        UserMessage userMessage = new UserMessage(userRawText);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

        // 3. Call the model and extract ONLY the text content
        String aiResponse = chatModel.call(prompt).getResult().getOutput().getText();

        // 4. Reply back to LINE
        messagingApiClient.replyMessage(new ReplyMessageRequest(
                event.replyToken(),
                List.of(new TextMessage(aiResponse)),
                false
        ));
    }
}