package com.jinloes.prpilot.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.intellij.openapi.util.UserDataHolderBase;
import com.jinloes.prpilot.model.PullRequest;
import org.junit.jupiter.api.Test;

class PRPilotEditorOpenerTest {

    @Test
    void getOrCreateVirtualFile_reusesSameInstanceForHolder() {
        UserDataHolderBase holder = new UserDataHolderBase();

        PRPilotVirtualFile first = PRPilotEditorOpener.getOrCreateVirtualFile(holder);
        PRPilotVirtualFile second = PRPilotEditorOpener.getOrCreateVirtualFile(holder);

        assertThat(second).isSameAs(first);
    }

    @Test
    void getOrCreateVirtualFile_createsReadonlyPrPilotTabFile() {
        UserDataHolderBase holder = new UserDataHolderBase();

        PRPilotVirtualFile file = PRPilotEditorOpener.getOrCreateVirtualFile(holder);

        assertThat(file.getName()).isEqualTo(PRPilotVirtualFile.TAB_TITLE);
        assertThat(file.isWritable()).isFalse();
    }

    @Test
    void consumePendingActivation_returnsQueuedActivationAndClearsIt() {
        UserDataHolderBase holder = new UserDataHolderBase();
        PullRequest pr =
                new PullRequest(
                        "Title",
                        "https://github.test/pr/7",
                        "acme",
                        "platform",
                        7,
                        "",
                        "octocat",
                        "2026-07-01",
                        true);

        PRPilotEditorOpener.queuePendingActivation(holder, pr, "notification");
        PRPilotEditorOpener.PendingActivation consumed =
                PRPilotEditorOpener.consumePendingActivation(holder);

        assertThat(consumed)
                .isEqualTo(new PRPilotEditorOpener.PendingActivation(pr, "notification"));
        assertThat(PRPilotEditorOpener.consumePendingActivation(holder)).isNull();
    }
}
