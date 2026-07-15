package com.jinloes.prpilot.ui;

final class HostThemeClassifier {
    private HostThemeClassifier() {}

    static String classify(boolean dark, boolean highContrast) {
        if (highContrast) {
            return dark ? "highContrastDark" : "highContrastLight";
        }
        return dark ? "dark" : "light";
    }
}
