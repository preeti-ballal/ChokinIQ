package com.backend.chokiniq.model;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class TransactionParseResult {
    private boolean transaction; // True if the user is logging income/expenses, false if it's casual chat
    private BigDecimal amount;     // Extracted mathematical value
    private String category;       // Extracted bucket (e.g., FOOD, RENT, SALARY, UTILITIES)
    private String type;           // Must be strictly extracted as 'INCOME' or 'EXPENSE'
    private String description;    // The clean item name (e.g., 'ramen', 'bonus payment')
}
