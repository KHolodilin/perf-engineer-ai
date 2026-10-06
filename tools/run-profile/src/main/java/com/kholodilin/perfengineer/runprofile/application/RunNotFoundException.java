package com.kholodilin.perfengineer.runprofile.application;

public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException() {
        super("Run was not found");
    }
}
