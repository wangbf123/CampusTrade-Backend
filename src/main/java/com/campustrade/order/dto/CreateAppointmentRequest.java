package com.campustrade.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record CreateAppointmentRequest(
        @NotNull LocalDateTime expectedTime,
        @Size(max = 300) String note
) {
}
