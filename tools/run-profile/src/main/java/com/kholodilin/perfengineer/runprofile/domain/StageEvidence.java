package com.kholodilin.perfengineer.runprofile.domain;

import java.util.List;

public record StageEvidence(int stage, double requestedRps, List<WindowEvidence> windows) {

    public StageEvidence {
        windows = windows == null ? List.of() : List.copyOf(windows);
    }
}
