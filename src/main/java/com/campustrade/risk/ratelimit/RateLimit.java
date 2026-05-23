package com.campustrade.risk.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    String key();

    int permits();

    int windowSeconds();

    RateLimitScope scope() default RateLimitScope.USER;
}
