package com.jinloes.prpilot.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministically identifies high-risk changed hunks omitted from a reported inspection ledger.
 */
final class ReviewCoverageAnalyzer {
    private static final int MAX_GAPS = 12;

    List<CoverageGap> findGaps(InspectionManifest manifest, InspectionLedger ledger) {
        if (manifest == null || ledger == null || !ledger.reported()) {
            return List.of();
        }
        List<CoverageGap> gaps = new ArrayList<>();
        int sequence = 1;
        for (InspectionManifest.FileTarget file : manifest.files()) {
            for (InspectionManifest.HunkTarget hunk : file.hunks()) {
                if (!hunk.highRisk()
                        || hunk.changedNewLines().isEmpty()
                        || ledger.inspectedTargetIds().contains(hunk.id())) {
                    continue;
                }
                gaps.add(
                        new CoverageGap(
                                "G%03d".formatted(sequence++),
                                hunk.id(),
                                hunk.path(),
                                hunk.newStart(),
                                "High-risk changed hunk was not recorded as inspected.",
                                100));
            }
        }
        return gaps.stream()
                .sorted(
                        Comparator.comparingInt(CoverageGap::priority)
                                .reversed()
                                .thenComparing(CoverageGap::path)
                                .thenComparingInt(CoverageGap::newStart))
                .limit(MAX_GAPS)
                .toList();
    }
}
