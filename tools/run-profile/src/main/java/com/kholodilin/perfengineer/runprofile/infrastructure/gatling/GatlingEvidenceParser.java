package com.kholodilin.perfengineer.runprofile.infrastructure.gatling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kholodilin.perfengineer.runprofile.application.EvidenceParser;
import com.kholodilin.perfengineer.runprofile.application.GatlingReport;
import com.kholodilin.perfengineer.runprofile.application.WorkloadDefinition;
import com.kholodilin.perfengineer.runprofile.domain.AssertionStatus;
import com.kholodilin.perfengineer.runprofile.domain.GatlingAssertions;
import com.kholodilin.perfengineer.runprofile.domain.GatlingTotals;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public final class GatlingEvidenceParser implements EvidenceParser {

    private static final Pattern COUNT = Pattern.compile("col-(\\d+)\">\\s*([0-9]+)");
    private static final Pattern ASSERTION = Pattern.compile(">(OK|KO)<");

    private final ObservationRegistry observationRegistry;

    public GatlingEvidenceParser() {
        this(ObservationRegistry.create());
    }

    public GatlingEvidenceParser(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public Optional<GatlingReport> parse(Path reportDirectory, WorkloadDefinition workload) {
        return Observation.createNotStarted("evidence.parse", observationRegistry)
                .observe(() -> read(reportDirectory, workload));
    }

    private Optional<GatlingReport> read(Path reportDirectory, WorkloadDefinition workload) {
        Path index = reportDirectory.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            return Optional.empty();
        }
        String html;
        try {
            html = Files.readString(index);
        } catch (IOException ex) {
            return Optional.empty();
        }
        int root = html.indexOf("<tr id=\"ROOT\"");
        if (root < 0) {
            return Optional.empty();
        }
        int rowEnd = html.indexOf("</tr>", root);
        if (rowEnd < 0) {
            return Optional.empty();
        }
        String row = html.substring(root, rowEnd);
        Long requests = count(row, 2);
        Long ok = count(row, 3);
        Long ko = count(row, 4);
        if (requests == null || ok == null || ko == null) {
            return Optional.empty();
        }
        AssertionStatus status = assertionStatus(html);
        return Optional.of(new GatlingReport(
                new GatlingTotals(requests, ok, ko),
                new GatlingAssertions(status, workload.maxFailedPercent(), workload.p95Ms())));
    }

    private static Long count(String row, int column) {
        Matcher matcher = COUNT.matcher(row);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == column) {
                return Long.parseLong(matcher.group(2));
            }
        }
        return null;
    }

    private static AssertionStatus assertionStatus(String html) {
        int start = html.indexOf("id=\"container_assertions\"");
        if (start < 0) {
            return AssertionStatus.OK;
        }
        int end = html.indexOf("</table>", start);
        String table = end < 0 ? html.substring(start) : html.substring(start, end);
        Matcher matcher = ASSERTION.matcher(table);
        while (matcher.find()) {
            if ("KO".equals(matcher.group(1))) {
                return AssertionStatus.FAILED;
            }
        }
        return AssertionStatus.OK;
    }
}
