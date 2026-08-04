package com.campustrade.storage.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.storage.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalImageStorageClient implements ImageStorageClient {

    private final StorageProperties storageProperties;

    public LocalImageStorageClient(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    @Override
    public StoredImage store(String objectKey, byte[] content, String contentType) {
        Path rootPath = storageProperties.imageRootPath();
        Path targetFile = rootPath.resolve(objectKey).normalize();
        if (!targetFile.startsWith(rootPath)) {
            throw BizException.badRequest("Invalid file target path");
        }

        try {
            Files.createDirectories(targetFile.getParent());
            Files.write(targetFile, content);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to store uploaded image", exception);
        }

        return new StoredImage(storageProperties.normalizedPublicBasePath() + "/" + objectKey, objectKey);
    }
}
