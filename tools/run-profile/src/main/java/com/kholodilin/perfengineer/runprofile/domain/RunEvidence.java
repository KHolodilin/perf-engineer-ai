package com.kholodilin.perfengineer.runprofile.domain;

import java.util.List;

public record RunEvidence(
        EvidenceStatus evidenceStatus,
        GatlingAssertions gatlingAssertions,
        GatlingTotals gatling,
        List<StageEvidence> stages,
        AvailabilityReason partialReason) {

    public RunEvidence {
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public static RunEvidence full(GatlingAssertions assertions, GatlingTotals gatling, List<StageEvidence> stages) {
        return new RunEvidence(EvidenceStatus.FULL, assertions, gatling, stages, null);
    }

    public static RunEvidence partial(
            GatlingAssertions assertions,
            GatlingTotals gatling,
            List<StageEvidence> stages,
            AvailabilityReason reason) {
        return new RunEvidence(EvidenceStatus.PARTIAL, assertions, gatling, stages, reason);
    }
}
