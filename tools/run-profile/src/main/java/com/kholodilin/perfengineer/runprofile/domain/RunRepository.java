package com.kholodilin.perfengineer.runprofile.domain;

import java.util.Optional;

public interface RunRepository {

    Run save(Run run);

    Optional<Run> findById(RunId runId);

    Run update(Run run);

    void delete(RunId runId);
}
