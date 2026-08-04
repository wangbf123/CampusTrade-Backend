package com.campustrade.storage.service;

public interface ImageStorageClient {

    StoredImage store(String objectKey, byte[] content, String contentType);
}
