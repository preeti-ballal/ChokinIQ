package com.backend.chokiniq.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.backend.chokiniq.model.Transaction;

@Repository
public class TransactionRepository {
    
    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Inserts a new transaction record directly into your Supabase DB - table user_transactions.
     */
    public void save(Transaction transaction) {
        String sql = "INSERT INTO user_transactions " + 
                    "(conversation_id, amount, category, type, description) " +
                    "VALUES (?, ?, ?, ?::transaction_type, ?)";
        
        jdbcTemplate.update(sql,
                transaction.getConversationId(),
                transaction.getAmount(),
                transaction.getCategory(),
                transaction.getType(), // Must match 'INCOME' or 'EXPENSE'
                transaction.getDescription()
        );
    }

    /**
     * Fetches all transactions belonging to a specific LINE user.
     */
    public List<Transaction> findByConversationId(String conversationId) {
        String sql = "SELECT id, conversation_id, amount, category, type, description, timestamp " +
                     "FROM user_transactions WHERE conversation_id = ? ORDER BY timestamp DESC";

        return jdbcTemplate.query(sql, new TransactionRowMapper(), conversationId);
    }
    
    /**
     * Calculates the sum total of all expenses for a user in the current calendar month.
     */
    public BigDecimal getMonthlyTotalExpenses(String conversationId) {
        // COALESCE ensures we return 0 instead of null when there are no matching records
        String sql = "SELECT COALESCE(SUM(amount), 0) FROM user_transactions " +
                     "WHERE conversation_id = ? " +
                     "AND type = 'EXPENSE' " +
                     "AND timestamp >= DATE_TRUNC('month', CURRENT_TIMESTAMP)";
        
        return jdbcTemplate.queryForObject(sql, BigDecimal.class, conversationId);
    }

    /**
     * Maps database result rows directly back into our Lombok-powered Transaction object.
     */
    private static class TransactionRowMapper implements RowMapper<Transaction> {
        @Override
        public Transaction mapRow(ResultSet rs, int rowNum) throws SQLException {
            Transaction transaction = new Transaction();
            transaction.setId(rs.getString("id"));
            transaction.setConversationId(rs.getString("conversation_id"));
            transaction.setAmount(rs.getBigDecimal("amount"));
            transaction.setCategory(rs.getString("category"));
            transaction.setType(rs.getString("type"));
            transaction.setDescription(rs.getString("description"));
            transaction.setTimestamp(rs.getObject("timestamp", OffsetDateTime.class));
            return transaction;
        }
    }
}
