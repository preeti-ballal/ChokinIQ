package com.backend.chokiniq.controller;

import com.backend.chokiniq.model.Transaction;
import com.backend.chokiniq.model.TransactionParseResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Component
public class BotControllerHelper {

    // Smell #3 Fixed: Single Source of Truth for business logic constants
    public static final BigDecimal BUDGET_LIMIT = new BigDecimal("100000");

    private final ChatModel chatModel;
    private final BeanOutputConverter<TransactionParseResult> parser;

    public BotControllerHelper(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.parser = new BeanOutputConverter<>(TransactionParseResult.class);
    }

    /**
     * Checks if the incoming string is an administrative slash command.
     */
    public boolean isSlashCommand(String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        return trimmed.equalsIgnoreCase("/clear") 
            || trimmed.equalsIgnoreCase("/stats") 
            || trimmed.equalsIgnoreCase("/panic");
    }

    /**
     * Employs structural output extraction capabilities to parse numbers out of raw strings.
     */
    public TransactionParseResult parseIncomingTextWithAI(String userRawText) {
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
    public String generateTransactionResponse(String rawText, Transaction transaction, BigDecimal monthlySpent, boolean isOverBudget) {
        String budgetStatusContext = isOverBudget
            ? String.format("CRITICAL WARNING: The user has officially crossed their monthly budget limit of %s yen! " +
              "You must change your tone to absolute panic and hilarious aggression. " +
              "Tell them they are financially doomed, yell at them to stop spending entirely, and lock up their wallet.", BUDGET_LIMIT)
            : "The user is still within their safe monthly spending limits. Acknowledge the transaction record clearly, " +
              "use funny emojis, and give them a casual, witty reminder about their spending velocity.";

        String template = String.format(
            "You are ChokinIQ, a witty, sarcastic personal finance bot. " +
            "The user just typed: '%s'. You successfully extracted and logged this data: " +
            "Type: %s, Amount: %s yen, Category: %s, Item: '%s'. " +
            "Their total recorded spending total for this current calendar month has now reached: %s yen.\n\n" +
            "CURRENT STATUS CONTEXT:\n%s",
            rawText, transaction.getType(), transaction.getAmount(), transaction.getCategory(), transaction.getDescription(), monthlySpent, budgetStatusContext
        );

        return chatModel.call(template);
    }

    /**
     * Synthesizes a clean, structured financial health check dashboard for the /stats command.
     */
    public String generateMonthlyStatsSummary(BigDecimal monthlySpent) {
        BigDecimal remainingBudget = BUDGET_LIMIT.subtract(monthlySpent);
        
        String budgetStatus = remainingBudget.signum() >= 0 
            ? String.format("Safe! You have %s yen left before hitting your limit.", remainingBudget)
            : String.format("🚨 CRITICAL CRASH! You are over budget by %s yen!", remainingBudget.abs());

        String template = String.format(
            "You are ChokinIQ, a witty, sarcastic personal finance bot. " +
            "The user just requested their monthly financial dashboard summary.\n\n" +
            "HERE ARE THE DIRECT DATABASE METRICS FOR THIS CALENDAR MONTH:\n" +
            "- Current Total Expenses: %s yen\n" +
            "- Strict Budget Target: %s yen\n" +
            "- Status: %s\n\n" +
            "Format your response as a sleek, easy-to-read mini-dashboard text report using clean bullet points and emojis. " +
            "Conclude with a sharp, funny one-sentence commentary on their current relationship with money.",
            monthlySpent, BUDGET_LIMIT, budgetStatus
        );

        return chatModel.call(template);
    }

    /**
     * Compiles the contextual system message and chat memory window into a cohesive list.
     */
    public List<Message> buildMessagePayload(String userRawText, List<Message> historicalMessages) {
        List<Message> fullPayload = new ArrayList<>();
        
        // Smell #1 Fixed: We keep system messages dynamic, but history is always preserved down below
        String systemInstruction = userRawText.equalsIgnoreCase("/panic")
            ? "You are ChokinIQ in EMERGENCY ROAST MODE. The user is about to make a catastrophic impulse purchase. " +
              "Completely destroy their urge to spend money. Be brutally funny, short, use ALL CAPS for emphasis, " +
              "and demand they put the credit card down immediately! 🚨🛑💸"
            : "You are ChokinIQ, a witty and slightly sarcastic personal finance assistant. Keep your answers brief, fun, and always use emojis.";

        fullPayload.add(new SystemMessage(systemInstruction));
        fullPayload.addAll(historicalMessages); // Memory leak plugged!
        
        return fullPayload;
    }
}