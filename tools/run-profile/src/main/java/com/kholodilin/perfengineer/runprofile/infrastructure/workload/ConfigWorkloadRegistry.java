package com.kholodilin.perfengineer.runprofile.infrastructure.workload;

import java.util.Map;

import com.kholodilin.perfengineer.runprofile.application.RequestRejectedException;
import com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition;
import com.kholodilin.perfengineer.runprofile.application.WorkloadRegistry;

public final class ConfigWorkloadRegistry implements WorkloadRegistry {

    private final Map<String, WorkloadDefinition> definitions;

    public ConfigWorkloadRegistry(Map<String, WorkloadDefinition> definitions) {
        this.definitions = Map.copyOf(definitions);
    }

    @Override
    public WorkloadDefinition require(String id) {
        WorkloadDefinition definition = definitions.get(id);
        if (definition == null) {
            throw new RequestRejectedException("WORKLOAD_NOT_FOUND", "Workload was not found", 400);
        }
        if (!definition.enabled()) {
            throw new RequestRejectedException("WORKLOAD_NOT_ALLOWED", "Workload is not allowed", 400);
        }
        return definition;
    }
}
