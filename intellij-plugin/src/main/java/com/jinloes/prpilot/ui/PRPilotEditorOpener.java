package com.jinloes.prpilot.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolder;
import com.jinloes.prpilot.model.PullRequest;

/** Opens (or reveals) the singleton PR Pilot editor tab for a project. */
public final class PRPilotEditorOpener {

    private static final Key<PRPilotVirtualFile> EDITOR_FILE_KEY =
            Key.create("prpilot.editor.virtualFile");
    private static final Key<WebviewPanel> ACTIVE_PANEL_KEY =
            Key.create("prpilot.editor.activePanel");
    private static final Key<PendingActivation> PENDING_ACTIVATION_KEY =
            Key.create("prpilot.editor.pendingActivation");

    public record PendingActivation(PullRequest pr, String source) {}

    private PRPilotEditorOpener() {}

    public static void openInEditor(Project project) {
        openInEditor(project, () -> {});
    }

    static void openInEditor(Project project, Runnable onOpened) {
        ApplicationManager.getApplication()
                .invokeLater(
                        () -> {
                            if (project.isDisposed()) {
                                return;
                            }
                            if (FileEditorManager.getInstance(project)
                                            .openFile(getOrCreateVirtualFile(project), true, true)
                                            .length
                                    > 0) {
                                onOpened.run();
                            }
                        });
    }

    public static void openInEditorAndActivate(Project project, PullRequest pr, String source) {
        queuePendingActivation(project, pr, source);
        WebviewPanel activePanel = project.getUserData(ACTIVE_PANEL_KEY);
        if (activePanel != null) {
            activePanel.activatePr(pr, source);
        }
        openInEditor(project);
    }

    static void queuePendingActivation(UserDataHolder holder, PullRequest pr, String source) {
        holder.putUserData(PENDING_ACTIVATION_KEY, new PendingActivation(pr, source));
    }

    static void registerWebviewPanel(UserDataHolder holder, WebviewPanel panel) {
        holder.putUserData(ACTIVE_PANEL_KEY, panel);
    }

    static void unregisterWebviewPanel(UserDataHolder holder, WebviewPanel panel) {
        if (holder.getUserData(ACTIVE_PANEL_KEY) == panel) {
            holder.putUserData(ACTIVE_PANEL_KEY, null);
        }
    }

    static PendingActivation consumePendingActivation(UserDataHolder holder) {
        PendingActivation pending = holder.getUserData(PENDING_ACTIVATION_KEY);
        holder.putUserData(PENDING_ACTIVATION_KEY, null);
        return pending;
    }

    static PRPilotVirtualFile getOrCreateVirtualFile(UserDataHolder holder) {
        PRPilotVirtualFile existing = holder.getUserData(EDITOR_FILE_KEY);
        if (existing != null && existing.isValid()) {
            return existing;
        }
        PRPilotVirtualFile created = new PRPilotVirtualFile();
        holder.putUserData(EDITOR_FILE_KEY, created);
        return created;
    }
}
