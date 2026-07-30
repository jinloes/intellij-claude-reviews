package com.jinloes.prpilot.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.review.CopilotModelDiscovery;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PRNotificationService;
import com.jinloes.prpilot.sidecar.github.CheckAuthResult;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javax.swing.*;
import javax.swing.event.ChangeEvent;

public class PluginSettingsComponent {

    private record ModelOption(String label, String id) {}

    private static final List<ModelOption> CLAUDE_MODELS =
            List.of(
                    new ModelOption("CLI default (unset)", ""),
                    new ModelOption("Haiku — fastest", "claude-haiku-4-5-20251001"),
                    new ModelOption("Sonnet — balanced", "claude-sonnet-4-6"),
                    new ModelOption("Opus — most thorough", "claude-opus-4-7"));

    /**
     * Recent Copilot CLI model IDs offered as autocomplete suggestions. This list is intentionally
     * a small subset rather than the full catalog — Copilot's available models change frequently,
     * and the field is editable so users can type any ID. Run {@code copilot help config} to see
     * what the installed CLI actually supports.
     */
    private static final String[] COPILOT_MODEL_SUGGESTIONS = {
        "", "claude-sonnet-4.6", "claude-opus-4.7", "claude-opus-4.8", "gpt-5.5", "gpt-5.4",
    };

    /**
     * Reasoning-effort levels accepted by {@code copilot --reasoning-effort}, per the CLI help.
     * "high" is the default — favors catching real issues over latency.
     */
    private static final String[] COPILOT_EFFORTS = {
        "none", "low", "medium", "high", "xhigh", "max"
    };

    /** Caps the settings form width (logical px, before HiDPI scaling) so fields don't sprawl. */
    private static final int MAX_FORM_WIDTH = 560;

    /** Caps the model dropdown width so it doesn't stretch to match the hint below it. */
    private static final int MODEL_COMBO_WIDTH = 320;

    private final JPanel mainPanel;
    private JPanel rootPanel;
    private final JBTextField baseUrlField = new JBTextField("https://github.com");
    private final JComboBox<String> claudeModelCombo =
            new JComboBox<>(CLAUDE_MODELS.stream().map(ModelOption::label).toArray(String[]::new));
    private final JComboBox<String> copilotModelCombo = new JComboBox<>(COPILOT_MODEL_SUGGESTIONS);
    private final JComboBox<String> copilotEffortCombo = new JComboBox<>(COPILOT_EFFORTS);
    private final JCheckBox copilotInheritMcpBox =
            new JCheckBox("Allow MCP tools for untrusted PR content");
    private final JCheckBox copilotAutoEnableMcpOnReviewBox =
            new JCheckBox("Always enable MCP for Copilot reviews");
    private final JBTextField copilotConfigDirField = new JBTextField();
    private final JBTextField reviewFocusAreasField = new JBTextField();
    private final JBTextArea reviewCustomInstructionsArea = new JBTextArea(3, 0);
    private final JBTextArea reviewGuidanceGlobsArea = new JBTextArea(3, 0);
    private final JCheckBox reviewSelfCritiqueBox =
            new JCheckBox("Run a self-critique validation pass (slower, higher precision)");
    private final JComboBox<ReviewProvider> providerCombo =
            new JComboBox<>(ReviewProvider.values());

    private final JPanel modelComboPanel = new JPanel(new java.awt.CardLayout());
    private final JPanel copilotModelCard = new JPanel();
    private final JCheckBox showAdvancedCopilotBox = new JCheckBox("Show advanced Copilot options");
    private final JPanel advancedCopilotSection = new JPanel();
    private final JPanel advancedCopilotPanel = new JPanel();
    private JPanel effortRowPanel;

    private final JLabel statusLabel = new JBLabel("Checking…");
    private final JButton checkButton = new JButton("Check Status");

    // Notification settings
    private final JCheckBox notificationsEnabledBox =
            new JCheckBox("Enable background PR notifications (experimental)");
    private final JCheckBox notifyReviewRequestedBox =
            new JCheckBox("Notify when a review is requested from me");
    private final JCheckBox notifyStarredReposBox =
            new JCheckBox("Notify when a new PR is opened on a starred repo");
    private final JSpinner pollIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
    private final JLabel pollStatusLabel = new JBLabel(" ");
    private JPanel notifSubPanel;

    public PluginSettingsComponent() {
        checkButton.addActionListener(e -> checkStatus());

        providerCombo.setRenderer(
                new DefaultListCellRenderer() {
                    @Override
                    public java.awt.Component getListCellRendererComponent(
                            JList<?> list,
                            Object value,
                            int index,
                            boolean isSelected,
                            boolean cellHasFocus) {
                        super.getListCellRendererComponent(
                                list, value, index, isSelected, cellHasFocus);
                        if (value instanceof ReviewProvider p) setText(p.getDisplayName());
                        return this;
                    }
                });

        copilotModelCombo.setEditable(true);
        copilotModelCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        boundComboWidth(copilotModelCombo);
        boundComboWidth(claudeModelCombo);
        JLabel copilotHint =
                hintLabel(
                        "<html><small>Auto-populated from <code>copilot help config</code>;"
                                + " type any model ID to override.</small></html>");
        // BoxLayout centers children unless told otherwise. Force LEFT_ALIGNMENT on every child of
        // copilotModelCard or the hint floats to the middle/right of the row.
        copilotHint.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Probe the CLI off the EDT — its first call can take up to 10 seconds. The dropdown
        // starts with the hardcoded suggestions so users see something immediately; results from
        // the probe (cached for the session) augment the list when they arrive.
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            List<String> discovered = CopilotModelDiscovery.listModels();
                            if (discovered.isEmpty()) return;
                            SwingUtilities.invokeLater(() -> mergeCopilotModelOptions(discovered));
                        });

        copilotModelCard.setLayout(new BoxLayout(copilotModelCard, BoxLayout.Y_AXIS));
        copilotModelCard.add(copilotModelCombo);
        copilotModelCard.add(copilotHint);

        // Wrap the Claude combo in a left-aligned BoxLayout card too: CardLayout ignores
        // maximumSize and would otherwise stretch the bare combo to the full panel width.
        JPanel claudeModelCard = new JPanel();
        claudeModelCard.setLayout(new BoxLayout(claudeModelCard, BoxLayout.Y_AXIS));
        claudeModelCard.add(claudeModelCombo);

        modelComboPanel.add(claudeModelCard, ReviewProvider.CLAUDE.getId());
        modelComboPanel.add(copilotModelCard, ReviewProvider.COPILOT.getId());
        providerCombo.addActionListener(e -> updateActiveModelCombo());

        // Effort lives on its own FormBuilder row so the "Reasoning effort:" label aligns with
        // "Review provider:" / "Review model:" in the left column. Combo is disabled when the
        // active provider is Claude (since `claude` doesn't support --reasoning-effort) rather
        // than hidden, so the form doesn't reflow as the user toggles providers.
        effortRowPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        effortRowPanel.add(copilotEffortCombo);
        JLabel effortHint =
                hintLabel(
                        "<html><small>Higher effort = deeper review, slower."
                                + " Applies only to GitHub Copilot.</small></html>");
        effortRowPanel.add(effortHint);

        JLabel mcpHint =
                hintLabel(
                        "<html><small>When enabled, Copilot inherits MCP servers from your trusted"
                                + " <code>~/.copilot/mcp-config.json</code>. A pull request's"
                                + " repo-local <code>.mcp.json</code> is never loaded.</small></html>");
        // BoxLayout centers children by default (alignmentX 0.5) unless each child explicitly opts
        // into LEFT_ALIGNMENT — every direct child added below needs it or the row floats to the
        // middle of the form, which is what was happening to the "Show advanced" checkbox.
        JLabel advancedHint =
                hintLabel(
                        "<html><small>Optional controls for reasoning depth, MCP access, and"
                                + " Copilot config discovery.</small></html>");
        advancedHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAdvancedCopilotBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        showAdvancedCopilotBox.addActionListener(e -> updateAdvancedCopilotOptionsVisibility());

        JPanel advancedFormPanel =
                FormBuilder.createFormBuilder()
                        .addLabeledComponent(
                                new JBLabel("Reasoning effort:"), effortRowPanel, 1, false)
                        .addComponent(copilotInheritMcpBox, 1)
                        .addComponent(copilotAutoEnableMcpOnReviewBox, 1)
                        .addLabeledComponent(
                                new JBLabel("Copilot config dir:"), copilotConfigDirField, 1, false)
                        .addComponent(mcpHint, 1)
                        .getPanel();
        advancedFormPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        advancedCopilotPanel.setLayout(new BoxLayout(advancedCopilotPanel, BoxLayout.Y_AXIS));
        advancedCopilotPanel.setBorder(JBUI.Borders.emptyTop(6));
        advancedCopilotPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        advancedCopilotPanel.add(advancedFormPanel);

        advancedCopilotSection.setLayout(new BoxLayout(advancedCopilotSection, BoxLayout.Y_AXIS));
        advancedCopilotSection.add(showAdvancedCopilotBox);
        advancedCopilotSection.add(advancedHint);
        advancedCopilotSection.add(advancedCopilotPanel);

        JLabel note =
                hintLabel(
                        "<html><small>Authentication uses the <b>gh</b> CLI. Run <code>gh auth login</code>"
                                + " if needed. Change the base URL for GitHub Enterprise.</small></html>");

        JPanel statusPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        statusPanel.add(checkButton);
        statusPanel.add(statusLabel);

        JPanel pollPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        pollPanel.add(new JBLabel("Poll every"));
        pollPanel.add(pollIntervalSpinner);
        pollPanel.add(new JBLabel("minutes"));

        notifSubPanel = new JPanel();
        notifSubPanel.setLayout(new BoxLayout(notifSubPanel, BoxLayout.Y_AXIS));
        notifSubPanel.add(notifyReviewRequestedBox);
        notifSubPanel.add(notifyStarredReposBox);
        notifSubPanel.add(pollPanel);
        notifSubPanel.add(pollStatusLabel);

        // Show/hide sub-options when master checkbox changes
        notificationsEnabledBox.addChangeListener(
                (ChangeEvent e) -> updateNotificationSubOptions());
        updateNotificationSubOptions();

        mainPanel =
                FormBuilder.createFormBuilder()
                        .addComponent(sectionTitle("GitHub connection"), 1)
                        .addLabeledComponent(new JBLabel("Base URL:"), baseUrlField, 1, false)
                        .addComponent(note, 1)
                        .addComponent(statusPanel, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review settings"), 1)
                        .addLabeledComponent(
                                new JBLabel("Review provider:"), providerCombo, 1, false)
                        .addLabeledComponent(
                                new JBLabel("Review model:"), modelComboPanel, 1, false)
                        .addComponent(advancedCopilotSection, 1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Review defaults"), 1)
                        .addLabeledComponent(
                                new JBLabel("Review focus areas:"), reviewFocusAreasField, 1, false)
                        .addComponent(
                                hintLabel(
                                        "<html><small>Comma-separated areas to prioritize (for example"
                                                + " security, performance, or test coverage).</small></html>"),
                                1)
                        .addLabeledComponent(
                                new JBLabel("Custom review instructions:"),
                                new JBScrollPane(reviewCustomInstructionsArea),
                                1,
                                false)
                        .addComponent(
                                hintLabel(
                                        "<html><small>Extra instructions appended to every review"
                                                + " prompt, such as team conventions.</small></html>"),
                                1)
                        .addLabeledComponent(
                                new JBLabel("Review guidance files:"),
                                new JBScrollPane(reviewGuidanceGlobsArea),
                                1,
                                false)
                        .addComponent(
                                hintLabel(
                                        "<html><small>One path or glob per line, read from the review"
                                                + " working directory into repo guidelines (for example"
                                                + " <code>**/style.md</code> or"
                                                + " <code>.linkedin/ai-agent/*.md</code>). Blank uses the"
                                                + " defaults (AGENTS.md, CONTRIBUTING.md, …).</small></html>"),
                                1)
                        .addComponent(reviewSelfCritiqueBox, 1)
                        .addComponent(
                                hintLabel(
                                        "<html><small>Runs a second pass that re-checks each finding"
                                                + " against the diff and drops misattributed ones."
                                                + " Higher precision, but roughly doubles review"
                                                + " time.</small></html>"),
                                1)
                        .addSeparator(8)
                        .addComponent(sectionTitle("Notifications"), 1)
                        .addComponent(notificationsEnabledBox, 1)
                        .addComponent(notifSubPanel, 1)
                        .addComponentFillVertically(new JPanel(), 0)
                        .getPanel();

        refreshPollStatus();
        refreshAuthStatus();
        updateAdvancedCopilotOptionsVisibility();
    }

    private void updateNotificationSubOptions() {
        boolean on = notificationsEnabledBox.isSelected();
        if (notifSubPanel != null) notifSubPanel.setVisible(on);
    }

    public JPanel getPanel() {
        if (rootPanel == null) {
            // Hard-cap the form width so input fields don't stretch across a wide Settings dialog.
            // BoxLayout is the one standard layout that honors maximumSize, so it clamps the form
            // to
            // MAX_FORM_WIDTH even when its preferred width is larger; the trailing glue eats the
            // remaining horizontal space. Height is left unbounded so toggling advanced options can
            // still grow the panel.
            mainPanel.setMaximumSize(
                    new java.awt.Dimension(JBUI.scale(MAX_FORM_WIDTH), Integer.MAX_VALUE));
            mainPanel.setAlignmentY(Component.TOP_ALIGNMENT);
            rootPanel = new JPanel();
            rootPanel.setLayout(new BoxLayout(rootPanel, BoxLayout.X_AXIS));
            rootPanel.add(mainPanel);
            rootPanel.add(Box.createHorizontalGlue());
        }
        return rootPanel;
    }

    public JComponent getPreferredFocusedComponent() {
        return baseUrlField;
    }

    public String getGithubBaseUrl() {
        return baseUrlField.getText().trim();
    }

    public void setGithubBaseUrl(String url) {
        baseUrlField.setText(url);
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabledBox.isSelected();
    }

    public void setNotificationsEnabled(boolean v) {
        notificationsEnabledBox.setSelected(v);
        updateNotificationSubOptions();
    }

    public boolean isNotifyReviewRequested() {
        return notifyReviewRequestedBox.isSelected();
    }

    public void setNotifyReviewRequested(boolean v) {
        notifyReviewRequestedBox.setSelected(v);
    }

    public boolean isNotifyStarredRepos() {
        return notifyStarredReposBox.isSelected();
    }

    public void setNotifyStarredRepos(boolean v) {
        notifyStarredReposBox.setSelected(v);
    }

    public int getNotificationPollMinutes() {
        return (Integer) pollIntervalSpinner.getValue();
    }

    public void setNotificationPollMinutes(int v) {
        pollIntervalSpinner.setValue(v);
    }

    public String getReviewModel() {
        return selectedId(claudeModelCombo, CLAUDE_MODELS);
    }

    public void setReviewModel(String modelId) {
        selectId(claudeModelCombo, CLAUDE_MODELS, modelId);
    }

    public String getReviewModelCopilot() {
        Object editorValue = copilotModelCombo.getEditor().getItem();
        return editorValue != null ? editorValue.toString().trim() : "";
    }

    public void setReviewModelCopilot(String modelId) {
        String id = modelId != null ? modelId.trim() : "";
        copilotModelCombo.setSelectedItem(id);
        copilotModelCombo.getEditor().setItem(id);
    }

    public ReviewProvider getReviewProvider() {
        Object selected = providerCombo.getSelectedItem();
        return selected instanceof ReviewProvider p ? p : ReviewProvider.CLAUDE;
    }

    public void setReviewProvider(ReviewProvider provider) {
        providerCombo.setSelectedItem(provider != null ? provider : ReviewProvider.CLAUDE);
        updateActiveModelCombo();
    }

    public String getReviewEffort() {
        Object selected = copilotEffortCombo.getSelectedItem();
        return selected instanceof String s && !s.isBlank() ? s : "medium";
    }

    public void setReviewEffort(String effort) {
        String value = effort != null && !effort.isBlank() ? effort : "medium";
        copilotEffortCombo.setSelectedItem(value);
    }

    public boolean isCopilotInheritMcp() {
        return copilotInheritMcpBox.isSelected();
    }

    public void setCopilotInheritMcp(boolean v) {
        copilotInheritMcpBox.setSelected(v);
    }

    public String getCopilotConfigDir() {
        return copilotConfigDirField.getText().trim();
    }

    public boolean isCopilotAutoEnableMcpOnReview() {
        return copilotAutoEnableMcpOnReviewBox.isSelected();
    }

    public void setCopilotAutoEnableMcpOnReview(boolean v) {
        copilotAutoEnableMcpOnReviewBox.setSelected(v);
    }

    public void setCopilotConfigDir(String dir) {
        copilotConfigDirField.setText(dir != null ? dir : "");
    }

    public String getReviewFocusAreas() {
        return reviewFocusAreasField.getText().trim();
    }

    public void setReviewFocusAreas(String value) {
        reviewFocusAreasField.setText(value != null ? value : "");
    }

    public String getReviewCustomInstructions() {
        return reviewCustomInstructionsArea.getText().trim();
    }

    public void setReviewCustomInstructions(String value) {
        reviewCustomInstructionsArea.setText(value != null ? value : "");
    }

    public String getReviewGuidanceGlobs() {
        return reviewGuidanceGlobsArea.getText().trim();
    }

    public void setReviewGuidanceGlobs(String value) {
        reviewGuidanceGlobsArea.setText(value != null ? value : "");
    }

    public boolean isReviewSelfCritique() {
        return reviewSelfCritiqueBox.isSelected();
    }

    public void setReviewSelfCritique(boolean v) {
        reviewSelfCritiqueBox.setSelected(v);
    }

    private static JBLabel sectionTitle(String text) {
        JBLabel label = new JBLabel("<html><b>" + text + "</b></html>");
        label.setBorder(JBUI.Borders.emptyTop(8));
        return label;
    }

    /** Left-aligns a model combo and caps its width so it doesn't stretch to the row/hint width. */
    private static void boundComboWidth(JComboBox<?> combo) {
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo.setMaximumSize(
                new java.awt.Dimension(
                        JBUI.scale(MODEL_COMBO_WIDTH), combo.getPreferredSize().height));
    }

    private static JBLabel hintLabel(String html) {
        // Constrain hint width so long hints wrap instead of widening the whole settings panel
        // (an unbounded single-line HTML label reports a huge preferred width, which forces a
        // horizontal scrollbar and pushes full-width fields like the model dropdown off-screen).
        String inner = html;
        if (inner.startsWith("<html>")) {
            inner = inner.substring("<html>".length());
        }
        if (inner.endsWith("</html>")) {
            inner = inner.substring(0, inner.length() - "</html>".length());
        }
        JBLabel label =
                new JBLabel(
                        "<html><div style='width:"
                                + JBUI.scale(480)
                                + "px'>"
                                + inner
                                + "</div></html>");
        label.setBorder(JBUI.Borders.emptyTop(2));
        return label;
    }

    private void updateActiveModelCombo() {
        ReviewProvider active = getReviewProvider();
        ((java.awt.CardLayout) modelComboPanel.getLayout()).show(modelComboPanel, active.getId());
        updateAdvancedCopilotOptionsVisibility();
    }

    private void updateAdvancedCopilotOptionsVisibility() {
        boolean copilotProvider = getReviewProvider() == ReviewProvider.COPILOT;
        boolean showAdvanced = showAdvancedCopilotBox.isSelected();
        advancedCopilotSection.setVisible(copilotProvider);
        advancedCopilotPanel.setVisible(copilotProvider && showAdvanced);
        copilotEffortCombo.setEnabled(copilotProvider);
        copilotInheritMcpBox.setEnabled(copilotProvider);
        copilotAutoEnableMcpOnReviewBox.setEnabled(copilotProvider);
        copilotConfigDirField.setEnabled(copilotProvider);
    }

    /**
     * Replaces the Copilot model dropdown entries with `[""] + discovered`, preserving the user's
     * current editor text. Called on the EDT from the discovery probe callback. Always keeps the
     * empty-string entry first (it represents "CLI default routing").
     */
    private void mergeCopilotModelOptions(List<String> discovered) {
        Object currentEditorValue = copilotModelCombo.getEditor().getItem();
        String currentText = currentEditorValue != null ? currentEditorValue.toString() : "";

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add("");
        merged.addAll(discovered);
        // Preserve any user-typed model that isn't in the discovered list so we don't lose it.
        if (!currentText.isBlank() && !merged.contains(currentText)) merged.add(currentText);

        List<String> ordered = new ArrayList<>(merged);
        copilotModelCombo.setModel(new DefaultComboBoxModel<>(ordered.toArray(new String[0])));
        copilotModelCombo.setSelectedItem(currentText);
        copilotModelCombo.getEditor().setItem(currentText);
    }

    private static String selectedId(JComboBox<String> combo, List<ModelOption> options) {
        int idx = combo.getSelectedIndex();
        return idx >= 0 && idx < options.size() ? options.get(idx).id() : "";
    }

    private static void selectId(
            JComboBox<String> combo, List<ModelOption> options, String modelId) {
        String id = modelId != null ? modelId : "";
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).id().equals(id)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    private void checkStatus() {
        checkButton.setEnabled(false);
        statusLabel.setText("Checking…");

        String baseUrl;
        try {
            baseUrl = GithubBaseUrlValidator.normalize(baseUrlField.getText());
        } catch (IllegalArgumentException e) {
            statusLabel.setText("<html><font color='red'>" + e.getMessage() + "</font></html>");
            checkButton.setEnabled(true);
            return;
        }
        ApplicationManager.getApplication()
                .executeOnPooledThread(
                        () -> {
                            CheckAuthResult result =
                                    IntellijGitHubService.getInstance().checkAuth(baseUrl);
                            if ("authenticated".equals(result.status())
                                    && result.username() != null) {
                                String username = result.username();
                                SwingUtilities.invokeLater(
                                        () -> {
                                            statusLabel.setText("Signed in as @" + username);
                                            checkButton.setEnabled(true);
                                        });
                            } else {
                                SwingUtilities.invokeLater(
                                        () -> {
                                            statusLabel.setText(
                                                    "<html><font color='red'>"
                                                            + result.message()
                                                            + "</font></html>");
                                            checkButton.setEnabled(true);
                                        });
                            }
                        });
    }

    private void refreshPollStatus() {
        PRNotificationService svc = PRNotificationService.getInstance();
        String status = svc.getLastPollStatus();
        if (status == null) {
            pollStatusLabel.setText(" ");
            return;
        }
        boolean isError = status.contains("Error:");
        String color = isError ? "red" : "gray";
        pollStatusLabel.setText(
                "<html><font color='" + color + "'><small>" + status + "</small></font></html>");
    }

    private void refreshAuthStatus() {
        checkStatus();
    }
}
