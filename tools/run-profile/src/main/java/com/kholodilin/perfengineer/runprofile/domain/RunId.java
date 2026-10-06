package com.kholodilin.perfengineer.runprofile.domain;

public record RunId(String value) {

    public RunId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }
}
