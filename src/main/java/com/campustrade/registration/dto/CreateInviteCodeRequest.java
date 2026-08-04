package com.campustrade.registration.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateInviteCodeRequest(
        @Size(max = 32) String code,
        @Min(1) Integer maxUses,
        LocalDateTime expiresAt,
        @Size(max = 200) String remark
) {
}
