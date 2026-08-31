package com.jinloes.prpilot.review;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record EvidenceRef(int findingIndex, Set<String> targetIds, List<String> relatedFiles) {
    EvidenceRef {
        targetIds =
                targetIds == null
                        ? Set.of()
                        : Collections.unmodifiableSet(new LinkedHashSet<>(targetIds));
        relatedFiles = relatedFiles == null ? List.of() : List.copyOf(relatedFiles);
    }
}
