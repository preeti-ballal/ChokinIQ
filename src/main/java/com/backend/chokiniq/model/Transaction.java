package com.backend.chokiniq.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import lombok.Data;

@Data
public class Transaction {
    private String id;
    private String conversationId;
    private BigDecimal amount;
    private String category;
    private String type; // INCOME or EXPENSE
    private String description;
    private OffsetDateTime timestamp;
}