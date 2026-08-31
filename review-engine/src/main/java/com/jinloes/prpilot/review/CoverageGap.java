package com.jinloes.prpilot.review;

record CoverageGap(
        String id, String targetId, String path, int newStart, String reason, int priority) {}
