package com.campustrade.storage.service;

import com.campustrade.storage.StorageProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ImageStorageClientTest {

    @Test
    void shouldUploadObjectWithSignedS3PutRequest() throws Exception {
        List<String> authorizationHeaders = new CopyOnWriteArrayList<>();
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleUpload(exchange, authorizationHeaders, paths));
        server.start();
        try {
            StorageProperties properties = new StorageProperties();
            properties.setType("s3");
            properties.setPublicBaseUrl("https://cdn.example.com/images");
            properties.getS3().setEndpoint("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getS3().setRegion("us-east-1");
            properties.getS3().setBucket("campustrade");
            properties.getS3().setAccessKey("access-key");
            properties.getS3().setSecretKey("secret-key");

            S3ImageStorageClient client = new S3ImageStorageClient(
                    properties,
                    java.net.http.HttpClient.newHttpClient(),
                    Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC)
            );

            StoredImage image = client.store("20260618/demo.png", "png".getBytes(), "image/png");

            assertEquals("https://cdn.example.com/images/20260618/demo.png", image.url());
            assertEquals("/campustrade/20260618/demo.png", paths.getFirst());
            assertTrue(authorizationHeaders.getFirst().startsWith("AWS4-HMAC-SHA256 Credential=access-key/20260618/us-east-1/s3/aws4_request"));
        } finally {
            server.stop(0);
        }
    }

    private void handleUpload(HttpExchange exchange, List<String> authorizationHeaders, List<String> paths) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] ignored = exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }
}
