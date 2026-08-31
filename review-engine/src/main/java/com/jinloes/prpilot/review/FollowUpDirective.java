package com.jinloes.prpilot.review;

record FollowUpDirective(
        String gapId, String targetId, String path, int newStart, String objective) {}
