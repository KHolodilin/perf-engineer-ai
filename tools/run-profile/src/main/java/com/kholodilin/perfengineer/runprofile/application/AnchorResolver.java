package com.kholodilin.perfengineer.runprofile.application;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public interface AnchorResolver {

    Optional<Instant> resolve(Path reportDirectory);
}
