package com.campustrade.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 3, max = 32) String username,
        @NotBlank @Size(min = 6, max = 64) String password,
        @NotBlank @Size(max = 32) String nickname,
        @Size(max = 20) String phone,
        @Size(max = 64) String campus,
        @Size(max = 32) String inviteCode
) {
}
