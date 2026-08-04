package com.campustrade.user.repository;

import com.campustrade.user.model.User;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryUserRepository implements UserRepository {

    private final AtomicLong idGenerator = new AtomicLong(1000);
    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final Map<String, Long> usernameIndex = new ConcurrentHashMap<>();

    @Override
    public synchronized User save(User user) {
        LocalDateTime now = LocalDateTime.now();
        if (user.getId() == null) {
            user.setId(idGenerator.incrementAndGet());
            user.setCreatedAt(now);
        }
        user.setUpdatedAt(now);
        users.put(user.getId(), user);
        usernameIndex.put(user.getUsername(), user.getId());
        return user;
    }

    @Override
    public Optional<User> findById(Long id) {
        return Optional.ofNullable(users.get(id));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        Long id = usernameIndex.get(username);
        return id == null ? Optional.empty() : findById(id);
    }
}
