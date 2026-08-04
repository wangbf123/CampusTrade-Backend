package com.campustrade.storage.dto;

public record UploadImageResponse(
        String url,
        String originalFilename,
        long size,
        String contentType
) {
}
