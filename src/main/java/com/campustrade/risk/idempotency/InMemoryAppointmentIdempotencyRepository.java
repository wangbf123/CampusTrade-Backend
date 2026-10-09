package com.campustrade.risk.idempotency;

import com.campustrade.common.exception.BizException;
import com.campustrade.order.dto.OrderResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Repository
@Profile("!mysql")
public class InMemoryAppointmentIdempotencyRepository implements AppointmentIdempotencyRepository {
    private final Map<RequestKey, CompletedRequest> completed = new HashMap<>();

    @Override
    public synchronized OrderResponse execute(Long userId, String key, String requestHash,
                                               Supplier<OrderResponse> operation) {
        RequestKey requestKey = new RequestKey(userId, key);
        CompletedRequest existing = completed.get(requestKey);
        if (existing != null) {
            if (!existing.hash().equals(requestHash)) {
                throw BizException.conflict("幂等键已用于不同的预约请求");
            }
            return existing.response();
        }
        OrderResponse response = operation.get();
        completed.put(requestKey, new CompletedRequest(requestHash, response));
        return response;
    }

    private record RequestKey(Long userId, String key) { }
    private record CompletedRequest(String hash, OrderResponse response) { }
}
