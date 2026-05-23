package com.campustrade.order.timeout;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("!redis")
public class InMemoryOrderTimeoutQueue implements OrderTimeoutQueue {

    private final Map<Long, LocalDateTime> expireTimes = new ConcurrentHashMap<>();

    @Override
    public void enqueue(Long orderId, LocalDateTime expireAt) {
        expireTimes.put(orderId, expireAt);
    }

    @Override
    public List<Long> dueOrderIds(LocalDateTime now, int limit) {
        return expireTimes.entrySet().stream()
                .filter(entry -> !entry.getValue().isAfter(now))
                .sorted(Comparator.comparing(Map.Entry::getValue))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void remove(Long orderId) {
        expireTimes.remove(orderId);
    }
}
