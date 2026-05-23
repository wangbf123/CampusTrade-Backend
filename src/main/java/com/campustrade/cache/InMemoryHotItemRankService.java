package com.campustrade.cache;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
@Profile("!redis")
public class InMemoryHotItemRankService implements HotItemRankService {

    private final Map<Long, LongAdder> scores = new ConcurrentHashMap<>();

    @Override
    public void recordView(Long itemId) {
        scores.computeIfAbsent(itemId, ignored -> new LongAdder()).increment();
    }

    @Override
    public List<Long> topItemIds(int limit) {
        return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, LongAdder>>comparingLong(entry -> entry.getValue().sum()).reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public void remove(Long itemId) {
        scores.remove(itemId);
    }
}
