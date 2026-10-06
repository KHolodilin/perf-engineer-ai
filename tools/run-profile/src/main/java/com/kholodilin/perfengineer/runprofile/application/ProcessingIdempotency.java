package com.kholodilin.perfengineer.runprofile.application;

public interface ProcessingIdempotency {

    void discardProcessing(String operation, String key);
}
