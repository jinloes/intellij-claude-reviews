package com.jinloes.prpilot.review;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record InspectionLedger(
        boolean reported, Set<String> inspectedTargetIds, List<EvidenceRef> evidence) {
    InspectionLedger {
        inspectedTargetIds =
                inspectedTargetIds == null
                        ? Set.of()
                        : Collections.unmodifiableSet(new LinkedHashSet<>(inspectedTargetIds));
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    static InspectionLedger missing() {
        return new InspectionLedger(false, Set.of(), List.of());
    }

    static InspectionLedger merge(List<InspectionLedger> ledgers) {
        boolean reported = false;
        Set<String> targets = new LinkedHashSet<>();
        List<EvidenceRef> evidence = new java.util.ArrayList<>();
        for (InspectionLedger ledger : ledgers) {
            if (ledger == null) {
                continue;
            }
            reported |= ledger.reported();
            targets.addAll(ledger.inspectedTargetIds());
            evidence.addAll(ledger.evidence());
        }
        return new InspectionLedger(reported, targets, evidence);
    }
}
