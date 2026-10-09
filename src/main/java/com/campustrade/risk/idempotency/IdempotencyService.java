package com.campustrade.risk.idempotency;

import com.campustrade.common.exception.BizException;
import com.campustrade.order.dto.CreateAppointmentRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

@Service
public class IdempotencyService {

    private final IdempotencyStore idempotencyStore;
    private final Duration appointmentTtl;

    public IdempotencyService(
            IdempotencyStore idempotencyStore,
            @Value("${app.idempotency.appointment-ttl-seconds:300}") long appointmentTtlSeconds
    ) {
        this.idempotencyStore = idempotencyStore;
        this.appointmentTtl = Duration.ofSeconds(appointmentTtlSeconds);
    }

    public void guardAppointmentSubmit(Long userId, Long itemId, String idempotencyKey, String requestFingerprint) {
        String normalizedKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? "fingerprint:" + sha256(userId + ":" + itemId + ":" + requestFingerprint)
                : "header:" + userId + ":" + idempotencyKey.trim();
        boolean acquired = idempotencyStore.tryAcquire("idem:appointment:" + normalizedKey, appointmentTtl);
        if (!acquired) {
            throw BizException.conflict("检测到重复提交，请稍后再试");
        }
    }

    public String normalizeExplicitKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim();
        if (normalized.length() > 128 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw BizException.badRequest("幂等键最多128个字符且不能包含控制字符");
        }
        return normalized;
    }

    public String appointmentRequestHash(Long itemId, CreateAppointmentRequest request) {
        // Length-prefix the nullable note so different payloads cannot share a delimiter encoding.
        String note = request.note();
        return sha256(itemId + "\n" + request.expectedTime() + "\n"
                + (note == null ? "-1:" : note.length() + ":" + note));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
