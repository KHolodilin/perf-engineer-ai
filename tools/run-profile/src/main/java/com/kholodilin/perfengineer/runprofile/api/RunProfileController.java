package com.kholodilin.perfengineer.runprofile.api;

import com.kholodilin.perfengineer.runprofile.application.AcceptedRun;
import com.kholodilin.perfengineer.runprofile.application.RunNotFoundException;
import com.kholodilin.perfengineer.runprofile.application.StartRunUseCase;
import com.kholodilin.perfengineer.runprofile.domain.RunId;
import com.kholodilin.perfengineer.runprofile.domain.RunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/runs")
@RequiredArgsConstructor
public class RunProfileController {

    private final StartRunUseCase startRunUseCase;
    private final RunRepository repository;
    private final RunHttpMapper mapper;

    @PostMapping
    public ResponseEntity<AcceptedResponse> start(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody StartRunRequest request) {
        AcceptedRun accepted = startRunUseCase.start(idempotencyKey, mapper.command(request));
        return ResponseEntity.accepted().body(new AcceptedResponse(accepted.runId(), "QUEUED"));
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable String runId) {
        return repository.findById(new RunId(runId))
                .map(mapper::response)
                .orElseThrow(RunNotFoundException::new);
    }
}
