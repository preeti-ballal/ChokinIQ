package com.backend.chokiniq.controller;

import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@LineMessageHandler
public class BotController {
    
    private final ChatModel chatModel;
    private final MessagingApiClient messagingApiClient;
    private final ChatMemory chatMemory;

    public BotController(ChatModel chatModel, MessagingApiClient messagingApiClient, JdbcTemplate jdbcTemplate) {
        this.chatModel = chatModel;
        this.messagingApiClient = messagingApiClient;
        
        // Connect the modern storage engine to your Supabase chat_history table
        JdbcChatMemoryRepository repository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .build();

        // Restrict memory context to the last 10 messages to optimize token spending
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(10)
                .build();
    }

    @EventMapping
    public void handleTextMessageEvent(MessageEvent event) {
        // 1. Structural Guard: Ensure incoming event payload contains text
        if (!(event.message() instanceof TextMessageContent messageContent)) {
            return;
        }

        String userId = event.source().userId();
        String userRawText = messageContent.text().trim();
        System.out.println("=> => => => User (" + userId + ") sent: " + userRawText + "\n");

        // INTERCEPT & EXECUTE DATABASE PURGE ---
        if (userRawText.equalsIgnoreCase("/clear")) {
            // Drop every single record matching this conversation_id inside the Supabase table
            chatMemory.clear(userId);
            
            sendLineReply(event.replyToken(), "🧹 Memory cleared! Your past financial sins are deleted from my brain. Let's start fresh!");
            return; 
        }

        // 2. State-Persistence: Log the user's incoming message to Supabase
        chatMemory.add(userId, List.of(new UserMessage(userRawText)));

        // 3. Dynamic Strategy Evaluation (Determine Persona Rules & Context Inputs)
        String systemInstruction = determineSystemInstruction(userRawText);
        List<Message> messagePayload = buildMessagePayload(userId, userRawText, systemInstruction);

        // 4. Remote Execution: Send payload to Gemini Flash-Lite
        String aiResponse = chatModel.call(new Prompt(messagePayload)).getResult().getOutput().getText();

        // 5. State-Persistence: Log ChokinIQ's response to memory history
        chatMemory.add(userId, List.of(new AssistantMessage(aiResponse)));

        // 6. Direct Handshake: Send the text bubble back to user's device
        sendLineReply(event.replyToken(), aiResponse);
    }

    /**
     * Determines the persona rules based on commands like /panic.
     */
    private String determineSystemInstruction(String userRawText) {
        if (userRawText.equalsIgnoreCase("/panic")) {
            return "You are ChokinIQ in EMERGENCY ROAST MODE. The user is about to make a catastrophic impulse purchase. " +
                   "Completely destroy their urge to spend money. Be brutally funny, short, use ALL CAPS for emphasis, " +
                   "and demand they put the credit card down immediately! 🚨🛑💸";
        }
        return "You are ChokinIQ, a witty and slightly sarcastic personal finance assistant. Keep your answers brief, fun, and always use emojis.";
    }

    /**
     * Compiles the contextual system message and chat memory window into a cohesive list.
     */
    private List<Message> buildMessagePayload(String userId, String userRawText, String systemInstruction) {
        List<Message> fullPayload = new ArrayList<>();
        
        // System instructions must ALWAYS be evaluated first by LLMs to stick to the persona
        fullPayload.add(new SystemMessage(systemInstruction));

        if (userRawText.equalsIgnoreCase("/panic")) {
            // For /panic, bypass long history to keep the text immediate, snappy, and heavily situational
            fullPayload.add(new UserMessage("I am standing at the cash register about to swipe my card for something I absolutely don't need! Stop me right now!"));
        } else {
            // Inject the last messages fetched dynamically from Supabase
            fullPayload.addAll(chatMemory.get(userId));
        }
        
        return fullPayload;
    }

    /**
     * Encapsulates the LINE API response client calls to keep main handlers uncluttered.
     */
    private void sendLineReply(String replyToken, String textContent) {
        messagingApiClient.replyMessage(new ReplyMessageRequest(
                replyToken,
                List.of(new TextMessage(textContent)),
                false
        ));
    }
}