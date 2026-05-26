package com.backend.chokiniq.controller;

import com.backend.chokiniq.model.Transaction;
import com.backend.chokiniq.model.TransactionParseResult;
import com.backend.chokiniq.repository.TransactionRepository;
import com.linecorp.bot.messaging.client.MessagingApiClient;
import com.linecorp.bot.messaging.model.ReplyMessageRequest;
import com.linecorp.bot.messaging.model.TextMessage;
import com.linecorp.bot.spring.boot.handler.annotation.EventMapping;
import com.linecorp.bot.spring.boot.handler.annotation.LineMessageHandler;
import com.linecorp.bot.webhook.model.MessageEvent;
import com.linecorp.bot.webhook.model.TextMessageContent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

@LineMessageHandler
public class BotController {
    
    private final ChatModel chatModel;
    private final MessagingApiClient messagingApiClient;
    private final ChatMemory chatMemory;
    private final TransactionRepository transactionRepository;
    private final BotControllerHelper helper;

    public BotController(ChatModel chatModel, MessagingApiClient messagingApiClient, 
                         JdbcTemplate jdbcTemplate, TransactionRepository transactionRepository,
                         BotControllerHelper helper) {
        this.chatModel = chatModel;
        this.messagingApiClient = messagingApiClient;
        this.transactionRepository = transactionRepository;
        this.helper = helper;

        JdbcChatMemoryRepository repository = JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .build();

        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(10)
                .build();
    }

    @EventMapping
    public void handleTextMessageEvent(MessageEvent event) {
        if (!(event.message() instanceof TextMessageContent messageContent)) {
            return;
        }

        String userId = event.source().userId();
        String userRawText = messageContent.text().trim();
        System.out.println("=> => => => User (" + userId + ") sent: " + userRawText + "\n");

        // 1. Structural Guard: Early administrative command routing
        if (helper.isSlashCommand(userRawText)) {
            handleSlashCommands(userId, userRawText, event.replyToken());
            return;
        }

        // 🧠 Core Structural Fix: Add incoming user message to chat memory immediately
        // This ensures the conversational pipeline tracks state flawlessly for both paths
        chatMemory.add(userId, List.of(new UserMessage(userRawText)));

        // 2. The Extraction Processing Loop
        TransactionParseResult extractedData = helper.parseIncomingTextWithAI(userRawText);
        String aiResponse;

        if (extractedData != null && extractedData.isTransaction()) {
            Transaction transaction = new Transaction();
            transaction.setConversationId(userId);
            transaction.setAmount(extractedData.getAmount());
            transaction.setCategory(extractedData.getCategory().toUpperCase());
            transaction.setType(extractedData.getType().toUpperCase());
            transaction.setDescription(extractedData.getDescription());

            transactionRepository.save(transaction);

            BigDecimal monthlySpent = transactionRepository.getMonthlyTotalExpenses(userId);
            boolean isOverBudget = monthlySpent.compareTo(BotControllerHelper.BUDGET_LIMIT) > 0;

            aiResponse = helper.generateTransactionResponse(userRawText, transaction, monthlySpent, isOverBudget);
        } else {
            // Standard conversational generation path
            var messagePayload = helper.buildMessagePayload(userRawText, chatMemory.get(userId));
            aiResponse = chatModel.call(new Prompt(messagePayload)).getResult().getOutput().getText();
        }

        // Save AI response to memory history and dispatch back out via the LINE client
        chatMemory.add(userId, List.of(new AssistantMessage(aiResponse)));
        sendLineReply(event.replyToken(), aiResponse);
    }

    /**
     * Centralized administrative routing module. Channels remain isolated cleanly.
     */
    private void handleSlashCommands(String userId, String command, String replyToken) {
        if (command.equalsIgnoreCase("/clear")) {
            chatMemory.clear(userId);
            sendLineReply(replyToken, "🧹 Memory cleared! Your past financial sins are deleted from my brain. Let's start fresh!");
        } else if (command.equalsIgnoreCase("/stats")) {
            BigDecimal monthlySpent = transactionRepository.getMonthlyTotalExpenses(userId);
            String statsSummary = helper.generateMonthlyStatsSummary(monthlySpent);
            sendLineReply(replyToken, statsSummary);
        } else if (command.equalsIgnoreCase("/panic")) {
            // Log the request to memory so subsequent user context works smoothly
            chatMemory.add(userId, List.of(new UserMessage(command)));
            
            var messagePayload = helper.buildMessagePayload(command, chatMemory.get(userId));
            String aiResponse = chatModel.call(new Prompt(messagePayload)).getResult().getOutput().getText();
            
            chatMemory.add(userId, List.of(new AssistantMessage(aiResponse)));
            sendLineReply(replyToken, aiResponse);
        }
    }

    /**
     * Standardized gateway method ensuring the external LINE SDK remains bound only to this controller level.
     */
    private void sendLineReply(String replyToken, String textContent) {
        messagingApiClient.replyMessage(new ReplyMessageRequest(
                replyToken,
                List.of(new TextMessage(textContent)),
                false
        ));
    }
}