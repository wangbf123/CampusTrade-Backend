package com.campustrade.risk.idempotency;

import com.campustrade.common.exception.BizException;
import com.campustrade.order.dto.OrderResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Repository
@Profile("mysql")
public class MysqlAppointmentIdempotencyRepository implements AppointmentIdempotencyRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MysqlAppointmentIdempotencyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public OrderResponse execute(Long userId, String key, String requestHash, Supplier<OrderResponse> operation) {
        // Duplicate requests wait for the owning transaction; a rollback frees the key.
        jdbc.update("""
                INSERT INTO appointment_idempotency (user_id, request_key, request_hash)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE request_key = request_key
                """, userId, key, requestHash);
        StoredRequest stored = jdbc.queryForObject("""
                SELECT request_hash, response_json FROM appointment_idempotency
                WHERE user_id = ? AND request_key = ? FOR UPDATE
                """, (result, row) -> new StoredRequest(result.getString(1), result.getString(2)), userId, key);
        if (stored == null || !requestHash.equals(stored.hash())) {
            throw BizException.conflict("幂等键已用于不同的预约请求");
        }
        try {
            if (stored.json() != null) {
                return objectMapper.readValue(stored.json(), OrderResponse.class);
            }
            OrderResponse response = operation.get();
            jdbc.update("""
                    UPDATE appointment_idempotency SET order_id = ?, response_json = ?
                    WHERE user_id = ? AND request_key = ?
                    """, response.id(), objectMapper.writeValueAsString(response), userId, key);
            return response;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot persist or replay appointment response", exception);
        }
    }

    private record StoredRequest(String hash, String json) { }
}
