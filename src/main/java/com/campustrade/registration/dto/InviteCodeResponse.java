package com.campustrade.registration.dto;

import com.campustrade.registration.model.InviteCode;
import com.campustrade.registration.model.InviteCodeStatus;

import java.time.LocalDateTime;

public record InviteCodeResponse(
        Long id,
        String code,
        Long createdBy,
        int maxUses,
        int usedCount,
        InviteCodeStatus status,
        LocalDateTime expiresAt,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static InviteCodeResponse from(InviteCode inviteCode) {
        return new InviteCodeResponse(
                inviteCode.getId(),
                inviteCode.getCode(),
                inviteCode.getCreatedBy(),
                inviteCode.getMaxUses(),
                inviteCode.getUsedCount(),
                inviteCode.getStatus(),
                inviteCode.getExpiresAt(),
                inviteCode.getRemark(),
                inviteCode.getCreatedAt(),
                inviteCode.getUpdatedAt()
        );
    }
}
