package com.campustrade.common.exception;

import org.springframework.http.HttpStatus;

public class BizException extends RuntimeException {

    private final int code;
    private final HttpStatus status;

    public BizException(int code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BizException badRequest(String message) {
        return new BizException(400, message, HttpStatus.BAD_REQUEST);
    }

    public static BizException unauthorized(String message) {
        return new BizException(401, message, HttpStatus.UNAUTHORIZED);
    }

    public static BizException forbidden(String message) {
        return new BizException(403, message, HttpStatus.FORBIDDEN);
    }

    public static BizException notFound(String message) {
        return new BizException(404, message, HttpStatus.NOT_FOUND);
    }

    public static BizException conflict(String message) {
        return new BizException(409, message, HttpStatus.CONFLICT);
    }

    public static BizException tooManyRequests(String message) {
        return new BizException(429, message, HttpStatus.TOO_MANY_REQUESTS);
    }
}
