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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@LineMessageHandler
public class BotController {
    
    private final ChatModel chatModel;
    private final MessagingApiClient messagingApiClient;
    private final ChatMemory chatMemory;
    private final TransactionRepository transactionRepository;

    // Instantiate the Spring AI converter for our structural parsing target
    private final BeanOutputConverter<TransactionParseResult> parser = 
            new BeanOutputConverter<>(TransactionParseResult.class);

    public BotController(ChatModel chatModel, MessagingApiClient messagingApiClient, 
        JdbcTemplate jdbcTemplate, TransactionRepository transactionRepository) {
        this.chatModel = chatModel;
        this.messagingApiClient = messagingApiClient;
        this.transactionRepository = transactionRepository;

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
        // 1. Ensure incoming event payload contains text
        if (!(event.message() instanceof TextMessageContent messageContent)) {
            return;
        }

        String userId = event.source().userId();
        String userRawText = messageContent.text().trim();
        System.out.println("=> => => => User (" + userId + ") sent: " + userRawText + "\n");

        // /clear command: reset the memory
        if (userRawText.equalsIgnoreCase("/clear")) {
            chatMemory.clear(userId);
            
            sendLineReply(event.replyToken(), "🧹 Memory cleared! Your past financial sins are deleted from my brain. Let's start fresh!");
            return; 
        }

        // 2. Evaluate if user is declaring transactional actions
        TransactionParseResult extractedData = parseIncomingTextWithAI(userRawText);

        String aiResponse;

        // It is a transaction so we save record
        if (extractedData != null && extractedData.isTransaction()) {
            System.out.println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@Extracted Transaction Data: " + extractedData);
            // Process transactional updates & save
            Transaction transaction = new Transaction();
            transaction.setConversationId(userId);
            transaction.setAmount(extractedData.getAmount());
            transaction.setCategory(extractedData.getCategory().toUpperCase());
            transaction.setType(extractedData.getType().toUpperCase());
            transaction.setDescription(extractedData.getDescription());

            transactionRepository.save(transaction);

            BigDecimal monthlySpent = transactionRepository.getMonthlyTotalExpenses(userId);

            // Synthesize structured transaction alert context back to the AI for conversational output
            aiResponse = generateTransactionResponse(userId, userRawText, transaction, monthlySpent);
            
            // Log both the user's message and AI's response to short term memory for future context
            chatMemory.add(userId, List.of(new UserMessage(userRawText)));
            chatMemory.add(userId, List.of(new AssistantMessage(aiResponse)));
        }else{
            System.out.println("@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ No transaction data extracted, treating as casual conversation." + extractedData);
            // Standard conversational pipeline execution flow 
            chatMemory.add(userId, List.of(new UserMessage(userRawText)));
            String systemInstruction = determineSystemInstruction(userRawText);
            List<Message> messagePayload = buildMessagePayload(userId, userRawText, systemInstruction);
            aiResponse = chatModel.call(new Prompt(messagePayload)).getResult().getOutput().getText();
            chatMemory.add(userId, List.of(new AssistantMessage(aiResponse)));
        }

        // 3. Return final response payload to LINE interface
        sendLineReply(event.replyToken(), aiResponse);
    }

    /**
     * Employs structural output extraction capabilities to parse numbers out of raw strings.
     */
    private TransactionParseResult parseIncomingTextWithAI(String userRawText) {
        try {
            String parsingInstructions = 
            "You are a strict financial data extraction engine. Analyze the user's input text.\n\n" +
            "CRITICAL RULES:\n" +
            "1. You MUST always include the 'transaction' boolean field in your JSON response.\n" +
            "2. If the user is logging an expense, bill, or income, set 'transaction' to true.\n" +
            "3. If the user is just making casual conversation, set 'transaction' to false.\n" +
            "4. For transactions, extract the numeric amount, upper-case category, type, and description.\n\n" +
            parser.getFormat();

            Prompt prompt = new Prompt(List.of(
                new SystemMessage(parsingInstructions),
                new UserMessage(userRawText)
            ));

            String rawJsonResult = chatModel.call(prompt).getResult().getOutput().getText();
            System.out.println("====== GEMINI RAW JSON OUTPUT ======\n" + rawJsonResult + "\n====================================");

            return parser.convert(rawJsonResult);
        } catch (Exception e) {
            System.err.println("Error running transactional analysis layer: " + e.getMessage());
            return null; 
        }
    }

    /**
     * Synthesizes conversational context back to the model to confirm a successful ledger save with character.
     */
    private String generateTransactionResponse(String userId, String rawText, Transaction transaction, BigDecimal monthlySpent) {
        String template = String.format(
            "You are ChokinIQ, a witty, sarcastic personal finance bot. " +
            "The user just typed: '%s'. You successfully extracted and logged this data: " +
            "Type: %s, Amount: %s yen, Category: %s, Item: '%s'. " +
            "Their total recorded spending total for this current calendar month has now reached: %s yen. " +
            "Acknowledge this transaction record clearly, use funny emojis, and roast them slightly about their current spending velocity.",
            rawText, transaction.getType(), transaction.getAmount(), transaction.getCategory(), transaction.getDescription(), monthlySpent
        );

        return chatModel.call(template);
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
    
        fullPayload.add(new SystemMessage(systemInstruction));

        if (userRawText.equalsIgnoreCase("/panic")) {
            fullPayload.add(new UserMessage("I am standing at the cash register about to swipe my card for something I absolutely don't need! Stop me right now!"));
        } else {
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