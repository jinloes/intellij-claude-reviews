package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HostThemeClassifierTest {
    @Test
    void classifiesEveryContrastAndBrightnessCombination() {
        assertThat(HostThemeClassifier.classify(false, false)).isEqualTo("light");
        assertThat(HostThemeClassifier.classify(true, false)).isEqualTo("dark");
        assertThat(HostThemeClassifier.classify(false, true)).isEqualTo("highContrastLight");
        assertThat(HostThemeClassifier.classify(true, true)).isEqualTo("highContrastDark");
    }
}
