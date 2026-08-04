package com.campustrade.storage.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.storage.StorageProperties;
import com.campustrade.storage.dto.UploadImageResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class ImageStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/jpg",
            "image/png",
            "image/webp"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");

    private final StorageProperties storageProperties;
    private final ImageStorageClient imageStorageClient;

    public ImageStorageService(StorageProperties storageProperties, ImageStorageClient imageStorageClient) {
        this.storageProperties = storageProperties;
        this.imageStorageClient = imageStorageClient;
    }

    public UploadImageResponse store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("Image file is required");
        }
        if (file.getSize() > storageProperties.getMaxImageSizeBytes()) {
            throw BizException.badRequest("Image file exceeds the configured size limit");
        }

        String contentType = normalizeContentType(file.getContentType());
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw BizException.badRequest("Only jpg, png, and webp images are supported");
        }

        String extension = resolveExtension(file.getOriginalFilename(), contentType);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw BizException.badRequest("Only jpg, png, and webp images are supported");
        }

        String dateFolder = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String storedFilename = UUID.randomUUID().toString().replace("-", "") + extension;
        String objectKey = dateFolder + "/" + storedFilename;

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read uploaded image", exception);
        }
        StoredImage storedImage = imageStorageClient.store(objectKey, content, contentType == null ? "application/octet-stream" : contentType);

        return new UploadImageResponse(
                storedImage.url(),
                file.getOriginalFilename() == null ? storedFilename : file.getOriginalFilename(),
                file.getSize(),
                contentType == null ? "application/octet-stream" : contentType
        );
    }

    private String resolveExtension(String originalFilename, String contentType) {
        String extension = extractExtension(originalFilename);
        if (extension != null) {
            return extension;
        }
        return switch (contentType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return null;
        }
        String normalized = originalFilename.trim();
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return null;
        }
        return normalized.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? null : contentType.toLowerCase(Locale.ROOT);
    }
}
