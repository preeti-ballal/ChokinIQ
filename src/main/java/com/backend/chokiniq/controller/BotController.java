package com.backend.chokiniq.controller;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;
import org.springframework.ai.chat.model.ChatModel;
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
        if (!(event.message() instanceof TextMessageContent messageContent)) {
            return;
        }

        // 1. Get user message safely using TextMessageContent
        String userMessage = messageContent.text();
        System.out.println("User sent: " + userMessage);

        // 2. Get AI Response
        String aiResponse = chatModel.call(userMessage);

        // 3. Reply
        messagingApiClient.replyMessage(new ReplyMessageRequest(
                event.replyToken(),
                List.of(new TextMessage(aiResponse)),
                false
        ));
    }
}
