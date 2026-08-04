package com.campustrade.storage.service;

import com.campustrade.common.exception.BizException;
import com.campustrade.storage.StorageProperties;
import com.campustrade.storage.dto.UploadImageResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImageStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldStoreImageAndReturnPublicUrl() throws Exception {
        StorageProperties properties = new StorageProperties();
        properties.setImageDir(tempDir.resolve("uploads/images").toString());
        properties.setPublicBasePath("/uploads/images");
        ImageStorageService imageStorageService = new ImageStorageService(properties, new LocalImageStorageClient(properties));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "ipad.png",
                "image/png",
                "fake-image".getBytes()
        );

        UploadImageResponse response = imageStorageService.store(file);

        assertTrue(response.url().startsWith("/uploads/images/"));
        assertTrue(Files.exists(properties.imageRootPath()));
        assertTrue(Files.walk(properties.imageRootPath()).anyMatch(Files::isRegularFile));
    }

    @Test
    void shouldRejectUnsupportedImageType() {
        StorageProperties properties = new StorageProperties();
        properties.setImageDir(tempDir.resolve("uploads/images").toString());
        ImageStorageService imageStorageService = new ImageStorageService(properties, new LocalImageStorageClient(properties));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "script.gif",
                "image/gif",
                "gif".getBytes()
        );

        assertThrows(BizException.class, () -> imageStorageService.store(file));
    }
}
