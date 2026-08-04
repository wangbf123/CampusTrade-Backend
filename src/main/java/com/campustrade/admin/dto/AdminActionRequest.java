package com.campustrade.admin.dto;

import jakarta.validation.constraints.Size;

public record AdminActionRequest(@Size(max = 500) String reason) {
}
