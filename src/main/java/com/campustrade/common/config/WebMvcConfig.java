package com.campustrade.common.config;

import com.campustrade.auth.security.AuthInterceptor;
import com.campustrade.risk.ratelimit.RateLimitInterceptor;
import com.campustrade.storage.StorageProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final StorageProperties storageProperties;
    private final CorsProperties corsProperties;

    public WebMvcConfig(
            AuthInterceptor authInterceptor,
            RateLimitInterceptor rateLimitInterceptor,
            StorageProperties storageProperties,
            CorsProperties corsProperties
    ) {
        this.authInterceptor = authInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.storageProperties = storageProperties;
        this.corsProperties = corsProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!storageProperties.isLocal()) {
            return;
        }
        registry.addResourceHandler(storageProperties.resourceHandlerPattern())
                .addResourceLocations(storageProperties.imageRootPath().toUri().toString());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> allowedOrigins = corsProperties.normalizedAllowedOrigins();
        if (allowedOrigins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Idempotency-Key", "X-Request-Id")
                .exposedHeaders("X-Request-Id")
                .maxAge(3600);
    }
}
