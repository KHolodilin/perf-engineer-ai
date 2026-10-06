package com.kholodilin.perfengineer.runprofile.application;

import com.kholodilin.perfengineer.runprofile.domain.GatlingAssertions;
import com.kholodilin.perfengineer.runprofile.domain.GatlingTotals;

public record GatlingReport(GatlingTotals totals, GatlingAssertions assertions) {
}
