package com.campustrade.notification.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NotificationReplayRequest(@NotBlank @Size(min = 3, max = 300) String reason) { }
