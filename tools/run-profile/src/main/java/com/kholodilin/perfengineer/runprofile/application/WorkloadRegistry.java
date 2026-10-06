package com.kholodilin.perfengineer.runprofile.application;

public interface WorkloadRegistry {

    WorkloadDefinition require(String id);
}
