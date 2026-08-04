package com.campustrade.storage.controller;

import com.campustrade.common.web.ApiResponse;
import com.campustrade.risk.ratelimit.RateLimit;
import com.campustrade.risk.ratelimit.RateLimitScope;
import com.campustrade.storage.dto.UploadImageResponse;
import com.campustrade.storage.service.ImageStorageService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final ImageStorageService imageStorageService;

    public FileController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(key = "file:image:upload", permits = 30, windowSeconds = 60, scope = RateLimitScope.USER)
    public ApiResponse<UploadImageResponse> uploadImage(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(imageStorageService.store(file));
    }
}
