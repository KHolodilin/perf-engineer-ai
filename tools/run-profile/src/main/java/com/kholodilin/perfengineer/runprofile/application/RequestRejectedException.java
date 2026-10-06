package com.kholodilin.perfengineer.runprofile.application;

public class RequestRejectedException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public RequestRejectedException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
