package com.campustrade.registration.repository;

import com.campustrade.registration.model.InviteCode;
import com.campustrade.registration.model.InviteCodeStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface InviteCodeRepository {

    InviteCode save(InviteCode inviteCode);

    Optional<InviteCode> findByCode(String code);

    List<InviteCode> findByStatus(InviteCodeStatus status, int page, int size);

    boolean consumeIfAvailable(String code, LocalDateTime now);
}
