package com.jinloes.prpilot.sidecar.review;

import java.util.List;

public record ReviewResult(String summary, String verdict, List<ReviewLineComment> lineComments) {}
