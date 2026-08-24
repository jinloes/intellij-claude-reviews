package com.jinloes.prpilot.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.awt.Container;
import java.awt.ContainerOrderFocusTraversalPolicy;
import java.awt.FocusTraversalPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PluginSettingsComponentTest {

    @Nested
    class AuthStatus {

        @Test
        void coordinatorConstructionDoesNotCheckTheDefaultHost() {
            List<String> checkedUrls = new ArrayList<>();

            new PluginSettingsComponent.AuthCheckCoordinator(
                    url -> {
                        checkedUrls.add(url);
                        return authenticated("octocat");
                    },
                    Runnable::run,
                    Runnable::run);

            assertThat(checkedUrls).isEmpty();
        }

        @Test
        void resetLoadsThePersistedEnterpriseHostBeforeRefreshingExactlyOnce() {
            List<String> events = new ArrayList<>();

            PluginSettingsConfigurable.loadGithubBaseUrlAndRefresh(
                    "https://github.example.test",
                    url -> events.add("set:" + url),
                    () -> events.add("refresh"));

            assertThat(events).containsExactly("set:https://github.example.test", "refresh");
        }

        @Test
        void anOlderCheckCannotOverwriteANewerResult() {
            List<Runnable> checks = new ArrayList<>();
            AtomicReference<String> result = new AtomicReference<>();
            PluginSettingsComponent.AuthCheckCoordinator coordinator =
                    new PluginSettingsComponent.AuthCheckCoordinator(
                            url ->
                                    authenticated(
                                            url.contains("example")
                                                    ? "enterprise-user"
                                                    : "default-user"),
                            checks::add,
                            Runnable::run);

            long first = coordinator.begin();
            coordinator.execute(first, "https://github.com", value -> result.set(value.username()));
            long second = coordinator.begin();
            coordinator.execute(
                    second, "https://github.example.test", value -> result.set(value.username()));
            checks.get(1).run();
            checks.get(0).run();

            assertThat(result).hasValue("enterprise-user");
        }
    }

    @Nested
    class GetPanel {

        @Test
        void givesPrimaryControlsAReadableSharedWidthForBothProviders() throws Exception {
            runUiProbe("shared-width");
        }

        @Test
        void boundsTheCustomInstructionsViewportAndKeepsVerticalScrolling() throws Exception {
            runUiProbe("custom-instructions");
        }

        @Test
        void stacksProfileSelectionAboveActionsAndPreservesProfileState() throws Exception {
            runUiProbe("profile-layout");
        }

        @Test
        void usesTaskBasedSectionsLabelsAndAttachedHints() throws Exception {
            runUiProbe("sections-and-hints");
        }
    }

    public static final class UiProbe {
        public static void main(String[] args) {
            try {
                switch (args[0]) {
                    case "shared-width" -> verifySharedWidth();
                    case "custom-instructions" -> verifyCustomInstructions();
                    case "profile-layout" -> verifyProfileLayout();
                    case "sections-and-hints" -> verifySectionsAndHints();
                    default -> throw new IllegalArgumentException("Unknown probe: " + args[0]);
                }
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.exit(1);
            }
            System.exit(0);
        }
    }

    private static void runUiProbe(String probe) throws Exception {
        for (String uiScale : List.of("1.0", "2.0")) {
            runUiProbe(probe, uiScale);
        }
    }

    private static void runUiProbe(String probe, String uiScale) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process =
                new ProcessBuilder(
                                java,
                                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                                "-Djava.awt.headless=true",
                                "-Dide.ui.scale=" + uiScale,
                                "-Dos.name=Linux",
                                "-cp",
                                System.getProperty("java.class.path"),
                                UiProbe.class.getName(),
                                probe)
                        .redirectErrorStream(true)
                        .start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(finished)
                .withFailMessage("UI probe timed out at %s scale:%n%s", uiScale, output)
                .isTrue();
        assertThat(process.exitValue())
                .withFailMessage("UI probe failed at %s scale:%n%s", uiScale, output)
                .isZero();
    }

    private static void verifySharedWidth() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();

        JComboBox<ReviewProvider> providerCombo = comboLabeled(panel, "Provider:");
        assertThat(providerCombo.getPrototypeDisplayValue()).isEqualTo(ReviewProvider.COPILOT);
        assertContentWidth(providerCombo);
        assertThat(providerCombo.getParent().getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(providerCombo.getParent().isFocusable()).isFalse();

        Component providerRenderer =
                providerCombo
                        .getRenderer()
                        .getListCellRendererComponent(
                                new JList<>(), ReviewProvider.COPILOT, -1, false, false);
        assertThat(providerCombo.getPreferredSize().width)
                .isGreaterThanOrEqualTo(providerRenderer.getPreferredSize().width);

        for (ReviewProvider provider : ReviewProvider.values()) {
            component.setReviewProvider(provider);
            assertContentWidth((JComponent) label(panel, "Model:").getLabelFor());
        }

        assertContentWidth((JComponent) label(panel, "Profile:").getLabelFor());
        assertContentWidth((JComponent) label(panel, "Focus areas:").getLabelFor());
    }

    private static void verifyCustomInstructions() {
        JPanel panel = component().getPanel();
        JBTextArea textArea = (JBTextArea) label(panel, "Custom instructions:").getLabelFor();
        JBScrollPane scrollPane =
                descendants(panel).stream()
                        .filter(JBScrollPane.class::isInstance)
                        .map(JBScrollPane.class::cast)
                        .filter(candidate -> candidate.getViewport().getView() == textArea)
                        .findFirst()
                        .orElseThrow();

        assertThat(textArea.getRows()).isBetween(4, 5);
        assertThat(scrollPane.getVerticalScrollBarPolicy())
                .isEqualTo(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        assertThat(scrollPane.getPreferredSize().width).isEqualTo(JBUI.scale(320));
        assertThat(scrollPane.getPreferredSize().height)
                .isGreaterThanOrEqualTo(textArea.getPreferredScrollableViewportSize().height);
        assertThat(scrollPane.getMaximumSize()).isEqualTo(scrollPane.getPreferredSize());
        assertThat(scrollPane.getMinimumSize()).isEqualTo(scrollPane.getPreferredSize());
        assertFieldWithHint(scrollPane, hintContaining(panel, "Extra instructions appended"));
    }

    private static void verifyProfileLayout() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        JComboBox<PluginSettings.ReviewGuidanceProfile> profileCombo =
                comboLabeled(panel, "Profile:");
        Container profilePanel = profileCombo.getParent();

        assertThat(profilePanel.getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(profilePanel.getComponents()).hasSize(2);
        assertThat(profilePanel.getComponent(0)).isSameAs(profileCombo);

        JPanel actionRow = (JPanel) profilePanel.getComponent(1);
        assertThat(
                        Arrays.stream(actionRow.getComponents())
                                .map(AbstractButton.class::cast)
                                .map(AbstractButton::getText))
                .containsExactly("Save as…", "Rename", "Delete");

        AbstractButton save = button(panel, "Save as…");
        AbstractButton rename = button(panel, "Rename");
        AbstractButton delete = button(panel, "Delete");
        assertThat(save.isEnabled()).isTrue();
        assertThat(rename.isEnabled()).isFalse();
        assertThat(delete.isEnabled()).isFalse();

        PluginSettings.ReviewGuidanceProfile profile =
                new PluginSettings.ReviewGuidanceProfile(
                        "quality", "Security, performance, and test coverage", "", "", "");
        component.setReviewGuidanceProfiles(List.of(profile));
        component.setActiveReviewGuidanceProfileId(profile.id);

        assertThat(rename.isEnabled()).isTrue();
        assertThat(delete.isEnabled()).isTrue();
        panel.setFocusCycleRoot(true);
        panel.setFocusTraversalPolicy(new ContainerOrderFocusTraversalPolicy());
        panel.addNotify();
        FocusTraversalPolicy focusTraversal = panel.getFocusTraversalPolicy();
        assertThat(focusTraversal.getComponentAfter(panel, profileCombo)).isSameAs(save);
        assertThat(focusTraversal.getComponentAfter(panel, save)).isSameAs(rename);
        assertThat(focusTraversal.getComponentAfter(panel, rename)).isSameAs(delete);

        Component renderedProfile =
                profileCombo
                        .getRenderer()
                        .getListCellRendererComponent(new JList<>(), profile, -1, false, false);
        assertThat(renderedProfile.getPreferredSize().width)
                .isLessThanOrEqualTo(profileCombo.getPreferredSize().width);
    }

    private static void verifySectionsAndHints() {
        PluginSettingsComponent component = component();
        JPanel panel = component.getPanel();
        List<String> texts =
                descendants(panel).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .map(JLabel::getText)
                        .toList();

        assertThat(texts)
                .contains(
                        "Base URL:",
                        "Provider:",
                        "Model:",
                        "Profile:",
                        "Focus areas:",
                        "Custom instructions:")
                .doesNotContain(
                        "Review provider:",
                        "Review model:",
                        "Guidance profile:",
                        "Review focus areas:",
                        "Custom review instructions:");
        assertThat(texts.stream().anyMatch(text -> text.contains("Review settings"))).isFalse();
        assertThat(texts.stream().anyMatch(text -> text.contains("Review defaults"))).isFalse();

        List<Integer> sectionOrder =
                List.of(
                                "GitHub connection",
                                "Review provider",
                                "Review guidance",
                                "Review validation",
                                "Notifications")
                        .stream()
                        .map(section -> indexContaining(texts, section))
                        .toList();
        assertThat(sectionOrder).isSorted();

        AbstractButton validation = button(panel, "Validate findings with a second pass");
        JLabel validationHint = hintContaining(panel, "roughly doubles review time");
        assertThat(validationHint.getText()).contains("improve precision");

        JComponent baseUrl = (JComponent) label(panel, "Base URL:").getLabelFor();
        assertFieldWithHint(baseUrl, hintContaining(panel, "Authentication uses"));

        JComboBox<PluginSettings.ReviewGuidanceProfile> profileCombo =
                comboLabeled(panel, "Profile:");
        assertFieldWithHint(
                (JComponent) profileCombo.getParent(),
                hintContaining(panel, "Save and reuse focus areas"));

        JComponent focusAreas = (JComponent) label(panel, "Focus areas:").getLabelFor();
        assertFieldWithHint(focusAreas, hintContaining(panel, "Comma-separated areas"));

        AbstractButton advanced = button(panel, "Show advanced Copilot options");
        assertFieldWithHint(advanced, hintContaining(panel, "Optional controls"));
        assertFieldWithHint(validation, validationHint);

        component.setReviewProvider(ReviewProvider.COPILOT);
        JComponent copilotModel = (JComponent) label(panel, "Model:").getLabelFor();
        assertFieldWithHint(copilotModel, hintContaining(panel, "Auto-populated from"));

        JComponent effort = (JComponent) label(panel, "Reasoning effort:").getLabelFor();
        assertFieldWithHint(effort, hintContaining(panel, "Higher effort"));
        AbstractButton inheritMcp = button(panel, "Allow MCP tools for untrusted PR content");
        assertFieldWithHint(inheritMcp, hintContaining(panel, "Copilot inherits MCP servers"));
    }

    private static PluginSettingsComponent component() {
        return new PluginSettingsComponent(
                ignored -> authenticated("octocat"), ignored -> {}, Runnable::run, () -> null);
    }

    @SuppressWarnings("unchecked")
    private static <T> JComboBox<T> comboLabeled(Component root, String labelText) {
        return (JComboBox<T>) label(root, labelText).getLabelFor();
    }

    private static JLabel label(Component root, String text) {
        List<JLabel> matches =
                descendants(root).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(candidate -> text.equals(candidate.getText()))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static JLabel hintContaining(Component root, String text) {
        List<JLabel> matches =
                descendants(root).stream()
                        .filter(JLabel.class::isInstance)
                        .map(JLabel.class::cast)
                        .filter(candidate -> candidate.getText().contains(text))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static AbstractButton button(Component root, String text) {
        List<AbstractButton> matches =
                descendants(root).stream()
                        .filter(AbstractButton.class::isInstance)
                        .map(AbstractButton.class::cast)
                        .filter(candidate -> text.equals(candidate.getText()))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private static void assertContentWidth(JComponent component) {
        assertThat(component.getPreferredSize().width).isEqualTo(JBUI.scale(320));
        assertThat(component.getMinimumSize().width).isEqualTo(JBUI.scale(320));
        assertThat(component.getMaximumSize().width).isEqualTo(JBUI.scale(320));
    }

    private static void assertFieldWithHint(Component control, JLabel hint) {
        assertThat(control.getParent()).isSameAs(hint.getParent());
        assertThat(control.getParent()).isInstanceOf(JPanel.class);
        JPanel field = (JPanel) control.getParent();
        assertThat(field.getLayout()).isInstanceOf(BoxLayout.class);
        assertThat(field.getComponents()).containsSubsequence(control, hint);
        assertThat(field.isFocusable()).isFalse();
        assertThat(hint.isFocusable()).isFalse();
    }

    private static int indexContaining(List<String> texts, String expected) {
        int index = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).contains(expected)) {
                assertThat(index).isEqualTo(-1);
                index = i;
            }
        }
        assertThat(index).isGreaterThanOrEqualTo(0);
        return index;
    }

    private static List<Component> descendants(Component root) {
        List<Component> components = new ArrayList<>();
        collect(root, components);
        return components;
    }

    private static void collect(Component component, List<Component> components) {
        components.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collect(child, components);
            }
        }
    }

    private static CheckAuthResult authenticated(String username) {
        return new CheckAuthResult(
                "authenticated", username, "GitHub authentication is available.");
    }
}
