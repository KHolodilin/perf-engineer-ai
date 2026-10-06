package com.kholodilin.perfengineer.runprofile.application;

import com.kholodilin.perfengineer.runprofile.domain.RunId;

public interface RunJobLauncher {

    void submit(RunId runId);
}
