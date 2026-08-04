package com.campustrade.storage.service;

import com.campustrade.storage.StorageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3")
public class S3ImageStorageClient implements ImageStorageClient {

    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final StorageProperties storageProperties;
    private final HttpClient httpClient;
    private final Clock clock;

    public S3ImageStorageClient(StorageProperties storageProperties) {
        this(storageProperties, HttpClient.newHttpClient(), Clock.systemUTC());
    }

    S3ImageStorageClient(StorageProperties storageProperties, HttpClient httpClient, Clock clock) {
        this.storageProperties = storageProperties;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public StoredImage store(String objectKey, byte[] content, String contentType) {
        StorageProperties.S3 s3 = storageProperties.getS3();
        validateS3Config(s3);

        Instant now = clock.instant();
        String date = DATE_FORMATTER.format(now);
        String amzDate = AMZ_DATE_FORMATTER.format(now);
        String payloadHash = sha256Hex(content);
        String bucket = s3.getBucket().trim();
        String endpoint = s3.normalizedEndpoint();
        String canonicalUri = "/" + encodePath(bucket) + "/" + encodePath(objectKey);
        URI endpointUri = URI.create(endpoint);
        URI objectUri = URI.create(endpoint + canonicalUri);
        String host = hostHeader(endpointUri);

        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "content-type:" + contentType + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String canonicalRequest = "PUT\n"
                + canonicalUri + "\n"
                + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;
        String credentialScope = date + "/" + s3.getRegion() + "/" + SERVICE + "/" + TERMINATOR;
        String stringToSign = ALGORITHM + "\n"
                + amzDate + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String signature = hmacHex(signingKey(s3.getSecretKey(), date, s3.getRegion()), stringToSign);
        String authorization = ALGORITHM
                + " Credential=" + s3.getAccessKey() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(objectUri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .header("Content-Type", contentType)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("S3 object upload failed with status "
                        + response.statusCode() + ": " + response.body());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to upload image to S3-compatible storage", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("S3 image upload was interrupted", exception);
        }

        return new StoredImage(publicUrl(endpoint, bucket, objectKey), objectKey);
    }

    private void validateS3Config(StorageProperties.S3 s3) {
        if (s3.getBucket() == null || s3.getBucket().isBlank()) {
            throw new IllegalStateException("app.storage.s3.bucket must not be blank");
        }
        if (s3.getAccessKey() == null || s3.getAccessKey().isBlank()) {
            throw new IllegalStateException("app.storage.s3.access-key must not be blank");
        }
        if (s3.getSecretKey() == null || s3.getSecretKey().isBlank()) {
            throw new IllegalStateException("app.storage.s3.secret-key must not be blank");
        }
    }

    private String publicUrl(String endpoint, String bucket, String objectKey) {
        String publicBaseUrl = storageProperties.normalizedPublicBaseUrl();
        if (!publicBaseUrl.isBlank()) {
            return publicBaseUrl + "/" + objectKey;
        }
        return endpoint + "/" + bucket + "/" + objectKey;
    }

    private String hostHeader(URI endpointUri) {
        int port = endpointUri.getPort();
        if (port < 0) {
            return endpointUri.getHost();
        }
        return endpointUri.getHost() + ":" + port;
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/")
                .replace("%7E", "~");
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private byte[] signingKey(String secretKey, String date, String region) {
        byte[] kDate = hmacBytes(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmacBytes(kDate, region);
        byte[] kService = hmacBytes(kRegion, SERVICE);
        return hmacBytes(kService, TERMINATOR);
    }

    private String hmacHex(byte[] key, String data) {
        return HexFormat.of().formatHex(hmacBytes(key, data));
    }

    private byte[] hmacBytes(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign S3 request", exception);
        }
    }
}
