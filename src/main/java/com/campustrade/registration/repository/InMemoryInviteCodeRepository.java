package com.campustrade.registration.repository;

import com.campustrade.registration.model.InviteCode;
import com.campustrade.registration.model.InviteCodeStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Repository
@Profile("!mysql")
public class InMemoryInviteCodeRepository implements InviteCodeRepository {

    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, InviteCode> inviteCodes = new ConcurrentHashMap<>();
    private final Map<String, Long> codeIndex = new ConcurrentHashMap<>();

    @Override
    public synchronized InviteCode save(InviteCode inviteCode) {
        LocalDateTime now = LocalDateTime.now();
        if (inviteCode.getId() == null) {
            inviteCode.setId(idGenerator.getAndIncrement());
            inviteCode.setCreatedAt(now);
        }
        inviteCode.setUpdatedAt(now);
        inviteCodes.put(inviteCode.getId(), inviteCode);
        codeIndex.put(inviteCode.getCode(), inviteCode.getId());
        return inviteCode;
    }

    @Override
    public Optional<InviteCode> findByCode(String code) {
        Long id = codeIndex.get(code);
        return id == null ? Optional.empty() : Optional.ofNullable(inviteCodes.get(id));
    }

    @Override
    public List<InviteCode> findByStatus(InviteCodeStatus status, int page, int size) {
        int offset = (page - 1) * size;
        return inviteCodes.values().stream()
                .filter(inviteCode -> status == null || inviteCode.getStatus() == status)
                .sorted(Comparator.comparing(InviteCode::getCreatedAt).thenComparing(InviteCode::getId).reversed())
                .skip(offset)
                .limit(size)
                .toList();
    }

    @Override
    public synchronized boolean consumeIfAvailable(String code, LocalDateTime now) {
        Optional<InviteCode> optional = findByCode(code);
        if (optional.isEmpty()) {
            return false;
        }
        InviteCode inviteCode = optional.get();
        if (inviteCode.getStatus() != InviteCodeStatus.ACTIVE) {
            return false;
        }
        if (inviteCode.getUsedCount() >= inviteCode.getMaxUses()) {
            return false;
        }
        if (inviteCode.getExpiresAt() != null && !inviteCode.getExpiresAt().isAfter(now)) {
            return false;
        }
        inviteCode.setUsedCount(inviteCode.getUsedCount() + 1);
        inviteCode.setUpdatedAt(now);
        return true;
    }
}
