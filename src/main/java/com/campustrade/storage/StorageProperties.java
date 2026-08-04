package com.campustrade.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String type = "local";
    private String imageDir = "uploads/images";
    private String publicBasePath = "/uploads/images";
    private String publicBaseUrl = "";
    private long maxImageSizeBytes = 5 * 1024 * 1024;
    private final S3 s3 = new S3();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type == null || type.isBlank() ? "local" : type.trim();
    }

    public String getImageDir() {
        return imageDir;
    }

    public void setImageDir(String imageDir) {
        this.imageDir = imageDir;
    }

    public String getPublicBasePath() {
        return publicBasePath;
    }

    public void setPublicBasePath(String publicBasePath) {
        this.publicBasePath = publicBasePath;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
    }

    public long getMaxImageSizeBytes() {
        return maxImageSizeBytes;
    }

    public void setMaxImageSizeBytes(long maxImageSizeBytes) {
        this.maxImageSizeBytes = maxImageSizeBytes;
    }

    public Path imageRootPath() {
        return Paths.get(imageDir).toAbsolutePath().normalize();
    }

    public boolean isLocal() {
        return "local".equalsIgnoreCase(type);
    }

    public String normalizedPublicBasePath() {
        String normalized = publicBasePath == null || publicBasePath.isBlank() ? "/uploads/images" : publicBasePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public String resourceHandlerPattern() {
        return normalizedPublicBasePath() + "/**";
    }

    public String normalizedPublicBaseUrl() {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return "";
        }
        String normalized = publicBaseUrl.trim();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    public S3 getS3() {
        return s3;
    }

    public static class S3 {
        private String endpoint = "http://127.0.0.1:9000";
        private String region = "us-east-1";
        private String bucket = "campustrade";
        private String accessKey = "";
        private String secretKey = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String normalizedEndpoint() {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("app.storage.s3.endpoint must not be blank");
            }
            String normalized = endpoint.trim();
            return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        }
    }
}
