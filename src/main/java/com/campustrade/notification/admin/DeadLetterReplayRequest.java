package com.campustrade.notification.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Explicit event selection prevents an operator from accidentally replaying the wrong queue head. */
public record DeadLetterReplayRequest(@NotBlank @Size(max = 64) String eventId,
                                     @NotBlank @Size(min = 3, max = 300) String reason) { }
