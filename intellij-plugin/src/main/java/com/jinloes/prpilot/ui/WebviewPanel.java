package com.jinloes.prpilot.ui;

import static com.intellij.openapi.application.ApplicationManager.getApplication;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.ui.jcef.JBCefJSQuery;
import com.intellij.util.Alarm;
import com.intellij.util.ui.UIUtil;
import com.jinloes.prpilot.model.ChatMessage;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PRReviewRequest;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewProvider;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.review.ClaudeService;
import com.jinloes.prpilot.review.CopilotService;
import com.jinloes.prpilot.review.GitWorktreeService;
import com.jinloes.prpilot.services.IntellijClaudeService;
import com.jinloes.prpilot.services.IntellijGitHubService;
import com.jinloes.prpilot.services.PendingReviewIndex;
import com.jinloes.prpilot.services.UserFacingErrors;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.settings.PluginSettingsConfigurable;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import org.apache.commons.lang3.StringUtils;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JCEF browser panel that loads the React webview and wires the Java↔JS bridge.
 *
 * <p>Resources are served from an embedded localhost HTTP server so that Chromium treats every
 * request as same-origin — avoiding the null-origin CORS failures that occur when loading ES
 * modules from {@code file://} URLs.
 *
 * <p>Bridge protocol (matches webview/src/bridge/types.ts):
 *
 * <ul>
 *   <li>Java→JS: {@code window.__handleMessage(json)} — pushed after page ready
 *   <li>JS→Java: {@code window.cefQuery({request: json})} — injected via JBCefJSQuery
 * </ul>
 */
public class WebviewPanel implements Disposable {

    private static final Logger log = LoggerFactory.getLogger(WebviewPanel.class);

    /** Maximum PRs shown in the list. The search over-fetches by one to detect truncation. */
    static final int PR_SEARCH_LIMIT = 50;

    private static final int LAYOUT_REPAINT_DELAY_MS = 50;

    // --- Outbound DTO records (Java → JS) ---
    // ReviewResultDto and LineCommentDto live in WebviewDtos.java (package-private);
    // see ReviewMapper for compile-time-verified model→DTO mapping.

    private record WebviewPr(
            int number,
            String title,
            String owner,
            String repo,
            String author,
            @JsonProperty("createdAt") String createdAt,
            @JsonProperty("htmlUrl") String htmlUrl,
            @JsonProperty("isDraft") boolean isDraft,
            @JsonProperty("hasReviewDraft") boolean hasReviewDraft) {}

    private record PrListStatus(
            String searchScope, String currentRepo, int resultLimit, boolean limited) {}

    private record PrListMessage(
            String type,
            List<WebviewPr> prs,
            @JsonProperty("defaultRepo") String defaultRepo,
            @JsonProperty("listStatus") PrListStatus listStatus) {}

    private record DraftLoadingMsg(String type, @JsonProperty("prKey") String prKey) {}

    private record ThemeChangedMsg(String type, String theme) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DraftLoadedMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            String prState,
            @JsonProperty("reviewId") String reviewId,
            @JsonProperty("result") ReviewResultDto result,
            String diff,
            @JsonProperty("validationDiff") String validationDiff,
            boolean staleCommits,
            boolean importedFromGitHub,
            String status,
            @JsonProperty("providerReadiness") ProviderReadinessDto providerReadiness) {}

    private record ReviewGeneratingMsg(
            String type, @JsonProperty("prKey") String prKey, String message) {}

    private record ReviewChunkMsg(
            String type, @JsonProperty("prKey") String prKey, String kind, String chunk) {}

    private record ReviewResultMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            ReviewResultDto result,
            String diff,
            @JsonProperty("validationDiff") String validationDiff) {}

    private record ErrorMsg(String type, @JsonProperty("prKey") String prKey, String message) {}

    private record DraftSavedMsg(
            String type,
            @JsonProperty("prKey") String prKey,
            String reviewId,
            boolean commentsDropped) {}

    private record SimpleMsg(String type, @JsonProperty("prKey") String prKey) {}

    private record ChatChunkMsg(String type, @JsonProperty("prKey") String prKey, String chunk) {}

    private record ChatResponseMsg(
            String type, @JsonProperty("prKey") String prKey, String response) {}

    private record PrDraftStatusMsg(
            String type,
            int number,
            String owner,
            String repo,
            @JsonProperty("hasReviewDraft") boolean hasReviewDraft) {}

    record ProviderReadinessDto(String provider, boolean available, String detail) {}

    private record ActivatePrMsg(String type, @JsonProperty("pr") WebviewPr pr, String source) {}

    private record SetupRequiredMsg(String type, String reason, String detail) {}

    // --- Infrastructure ---

    private volatile HttpServer httpServer;
    private volatile boolean disposed;
    private final JBCefBrowser browser;
    private final JPanel browserPanel;
    private final JBCefJSQuery bridgeQuery;
    private final Alarm layoutRepaintAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this);
    private final ObjectMapper mapper =
            new ObjectMapper()
                    .registerModule(new KotlinModule.Builder().build())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final PendingReviewIndex pendingIndex = new PendingReviewIndex();
    private final IntellijClaudeService claudeService;
    private final GitWorktreeService worktreeService = new GitWorktreeService();
    private final IntellijGitHubService ghSvc = IntellijGitHubService.getInstance();
    private final Project project;

    /**
     * Points to the service that owns the currently running review process (may be a per-worktree
     * instance). Reset to {@code claudeService} after every review.
     */
    private volatile IntellijClaudeService activeReviewService;

    private volatile List<PullRequest> cachedPRs = List.of();
    private volatile PullRequest activePR = null;
    private volatile ReviewResult lastResult = null;
    private volatile String pendingReviewId = null;
    private volatile String pendingReviewKey = null;
    private volatile long selectionRevision = 0;
    private final Object draftMutationLock = new Object();
    private volatile String prefetchedDiff = null;
    private volatile String prefetchedValidationDiff = null;
    private volatile String prefetchedExistingReviews = null;
    private volatile List<ChatMessage> chatHistory = List.of();
    private volatile IntellijClaudeService activePrClaudeService = null;
    private volatile java.io.File activePrWorktreeDir = null;
    private volatile java.io.File activePrGitRoot = null;
    private volatile String activePrWorktreeKey = null;

    private volatile String prStateFilter = "open";
    private volatile String searchScope = "currentRepo";

    private Consumer<PullRequest> onPRSelected = pr -> {};
    private Runnable onPageReady = () -> {};

    public WebviewPanel(Project project) {
        this.project = project;
        this.claudeService = new IntellijClaudeService(project.getBasePath());
        this.activeReviewService = this.claudeService;
        browser = JBCefBrowser.createBuilder().setOffScreenRendering(true).build();
        browserPanel = createBrowserHostPanel(browser.getComponent());
        bridgeQuery = JBCefJSQuery.create((JBCefBrowserBase) browser);

        bridgeQuery.addHandler(
                request -> {
                    handleIncoming(request);
                    return new JBCefJSQuery.Response(null);
                });

        browser.getJBCefClient()
                .addLoadHandler(
                        new CefLoadHandlerAdapter() {
                            @Override
                            public void onLoadEnd(
                                    CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                                if (!frame.isMain() || disposed) {
                                    return;
                                }
                                injectBridge(cefBrowser);
                                getApplication()
                                        .invokeLater(
                                                () -> {
                                                    if (disposed) {
                                                        return;
                                                    }
                                                    pushCurrentTheme();
                                                    onPageReady.run();
                                                });
                            }
                        },
                        browser.getCefBrowser());

        browser.loadHTML(
                "<html><body style='color:#888;background:#0a0805;"
                        + "font-family:monospace;padding:1em'>"
                        + "<p>Starting webview…</p>"
                        + "</body></html>");

        getApplication().executeOnPooledThread(this::startServerAndLoad);

        getApplication()
                .getMessageBus()
                .connect(this)
                .subscribe(LafManagerListener.TOPIC, source -> pushCurrentTheme());
    }

    private void startServerAndLoad() {
        HttpServer server = tryStartServer();
        if (disposed) {
            if (server != null) {
                server.stop(0);
            }
            return;
        }
        httpServer = server;
        if (server == null) {
            getApplication()
                    .invokeLater(
                            () -> {
                                if (!disposed) {
                                    browser.loadHTML(
                                            "<html><body style='color:#e8a030;"
                                                    + "background:#0a0805;"
                                                    + "font-family:monospace'>"
                                                    + "<p>Could not start webview server</p>"
                                                    + "</body></html>");
                                }
                            });
            return;
        }
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        log.info("Loading webview from {}", url);
        getApplication()
                .invokeLater(
                        () -> {
                            if (!disposed) {
                                browser.loadURL(url);
                            }
                        });
    }

    static JPanel createBrowserHostPanel(JComponent browserComponent) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(browserComponent, BorderLayout.CENTER);
        return panel;
    }

    private HttpServer tryStartServer() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", this::serveResource);
            s.start();
            log.debug("Webview HTTP server listening on port {}", s.getAddress().getPort());
            return s;
        } catch (IOException e) {
            log.error("Failed to start webview HTTP server", e);
            return null;
        }
    }

    private void serveResource(HttpExchange exchange) throws IOException {
        String resource = resolveResourcePath(exchange.getRequestURI().getPath());
        if (resource == null) {
            exchange.sendResponseHeaders(404, 0);
            exchange.close();
            return;
        }
        try (InputStream in = WebviewPanel.class.getResourceAsStream(resource)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            byte[] bytes = in.readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", mimeFor(resource));
            exchange.getResponseHeaders().add("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /**
     * Maps a request path to a classpath resource path under {@code /webview/}, or returns null if
     * the request would escape that root.
     */
    static String resolveResourcePath(String requestPath) {
        if (StringUtils.isBlank(requestPath) || !requestPath.startsWith("/")) {
            return null;
        }
        String path = "/".equals(requestPath) ? "/index.html" : requestPath;
        String candidate = "/webview" + path;
        String normalized = URI.create(candidate).normalize().getPath();
        if (!normalized.startsWith("/webview/")) {
            return null;
        }
        return normalized;
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private void injectBridge(CefBrowser cefBrowser) {
        String js =
                "window.cefQuery = function(opts) { " + bridgeQuery.inject("opts.request") + " };";
        cefBrowser.executeJavaScript(js, cefBrowser.getURL(), 0);
    }

    private void scheduleWebviewLayoutRepaint() {
        getApplication()
                .invokeLater(
                        () -> {
                            if (disposed) {
                                return;
                            }
                            layoutRepaintAlarm.cancelAllRequests();
                            layoutRepaintAlarm.addRequest(
                                    () -> {
                                        if (disposed) {
                                            return;
                                        }
                                        CefBrowser cefBrowser = browser.getCefBrowser();
                                        if (cefBrowser != null) {
                                            cefBrowser.invalidate();
                                        }
                                        browser.getComponent().revalidate();
                                        browser.getComponent().repaint();
                                        browserPanel.revalidate();
                                        browserPanel.repaint();
                                    },
                                    LAYOUT_REPAINT_DELAY_MS);
                        });
    }

    private void handleIncoming(String json) {
        try {
            var node = mapper.readTree(json);
            if (!isValidIncomingMessage(node)) {
                log.warn("Invalid bridge message payload: {}", json);
                return;
            }
            String type = node.path("type").asText();
            int number = node.path("number").asInt();
            String owner = node.path("owner").asText();
            String repo = node.path("repo").asText();

            switch (type) {
                case "selectPR" -> handleSelectPR(number, owner, repo);
                case "refreshPRs" -> {
                    String state = node.path("state").asText("open");
                    prStateFilter = StringUtils.defaultIfBlank(state, "open");
                    String scope = node.path("searchScope").asText("");
                    if (StringUtils.isNotBlank(scope)) {
                        searchScope = normalizeSearchScope(scope);
                    } else if (node.path("assignedToMe").asBoolean(false)) {
                        searchScope = "assigned";
                    } else if (node.path("reviewRequested").asBoolean(false)) {
                        searchScope = "reviewRequested";
                    }
                    getApplication().invokeLater(onPageReady);
                }
                case "openUrl" -> {
                    String url = node.path("url").asText();
                    if (StringUtils.isNotBlank(url) && url.startsWith("https://")) {
                        getApplication().invokeLater(() -> BrowserUtil.browse(url));
                    }
                }
                case "openSettings" ->
                        getApplication()
                                .invokeLater(
                                        () ->
                                                ShowSettingsUtil.getInstance()
                                                        .showSettingsDialog(
                                                                project,
                                                                PluginSettingsConfigurable.class));
                case "runAuthLogin" ->
                        getApplication()
                                .invokeLater(
                                        () ->
                                                BrowserUtil.browse(
                                                        "https://cli.github.com/manual/gh_auth_login"));
                case "webviewLayoutChanged" -> scheduleWebviewLayoutRepaint();
                case "generateReview" ->
                        handleGenerateReview(
                                number,
                                owner,
                                repo,
                                node.path("diff").asText(""),
                                node.path("focusAreas").asText(""),
                                node.path("customInstructions").asText(""));
                case "cancelReview" -> activeReviewService.cancelCurrentRequest();
                case "saveDraft" -> {
                    ReviewResult bridgeResult = null;
                    List<LineComment> bridgeOrphans = List.of();
                    try {
                        var resultNode = node.path("result");
                        if (!resultNode.isMissingNode()) {
                            bridgeResult = mapper.treeToValue(resultNode, ReviewResult.class);
                        }
                        var orphansNode = node.path("orphans");
                        if (orphansNode.isArray()) {
                            List<LineComment> parsed = new ArrayList<>();
                            for (var el : orphansNode) {
                                parsed.add(mapper.treeToValue(el, LineComment.class));
                            }
                            bridgeOrphans = parsed;
                        }
                    } catch (Exception e) {
                        log.warn(
                                "saveDraft: failed to parse result/orphans from bridge: {}",
                                e.getMessage());
                    }
                    final ReviewResult finalResult = bridgeResult;
                    final List<LineComment> finalOrphans = bridgeOrphans;
                    getApplication()
                            .executeOnPooledThread(
                                    () -> {
                                        synchronized (draftMutationLock) {
                                            handleSaveDraft(
                                                    number, owner, repo, finalResult, finalOrphans);
                                        }
                                    });
                }
                case "submitReview" -> {
                    String verdict = node.path("verdict").asText();
                    String comment = node.path("comment").asText("");
                    getApplication()
                            .executeOnPooledThread(
                                    () -> {
                                        synchronized (draftMutationLock) {
                                            handleSubmitReview(
                                                    number, owner, repo, verdict, comment);
                                        }
                                    });
                }
                case "deleteDraft" ->
                        getApplication()
                                .executeOnPooledThread(
                                        () -> {
                                            synchronized (draftMutationLock) {
                                                handleDeleteDraft(number, owner, repo);
                                            }
                                        });
                case "clearChat" -> chatHistory = List.of();
                case "askClaude" -> {
                    String question = node.path("question").asText();
                    String context = node.path("context").asText("");
                    getApplication()
                            .executeOnPooledThread(() -> handleAskClaude(question, context));
                }
                default -> log.warn("Unknown bridge message type: {}", type);
            }
        } catch (Exception e) {
            log.warn("Bridge message error: {}", e.getMessage());
        }
    }

    static boolean isValidIncomingMessage(JsonNode node) {
        return BridgeMessageValidator.isValid(node);
    }

    // --- selectPR ---

    private void handleSelectPR(int number, String owner, String repo) {
        String key = bridgePrKey(number, owner, repo);
        PullRequest pr =
                cachedPRs.stream()
                        .filter(
                                p ->
                                        p.getNumber() == number
                                                && p.getOwner().equals(owner)
                                                && p.getRepo().equals(repo))
                        .findFirst()
                        .orElse(null);
        if (pr == null) {
            pushMessage(
                    new DraftLoadedMsg(
                            "draftLoaded",
                            key,
                            "NO_DRAFT",
                            null,
                            null,
                            null,
                            null,
                            false,
                            false,
                            "Pull request is no longer available. Refresh the pull request list and try again.",
                            currentProviderReadiness()));
            return;
        }

        clearActivePrWorktree();

        // Synchronize the field-clearing so a concurrent handleGenerateReview
        // on a pooled thread cannot read a stale activePR/prefetchedDiff pair.
        long revision;
        synchronized (this) {
            activePR = pr;
            lastResult = null;
            pendingReviewId = null;
            pendingReviewKey = null;
            prefetchedDiff = null;
            prefetchedValidationDiff = null;
            prefetchedExistingReviews = null;
            chatHistory = List.of();
            revision = ++selectionRevision;
        }

        getApplication().invokeLater(() -> onPRSelected.accept(pr));
        pushMessage(new DraftLoadingMsg("draftLoading", key));

        getApplication()
                .executeOnPooledThread(
                        () -> {
                            // Check local index upfront (no network) so we can prefetch
                            // the current HEAD SHA in parallel if a staleness check is
                            // likely to be needed.
                            PendingReviewIndex.Entry localEntry =
                                    pendingIndex.list().stream()
                                            .filter(
                                                    e ->
                                                            e.owner().equals(owner)
                                                                    && e.repo().equals(repo)
                                                                    && e.number() == number)
                                            .findFirst()
                                            .orElse(null);
                            String savedHeadSha = localEntry != null ? localEntry.headSha() : "";

                            // All calls are independent — run concurrently so total
                            // latency is max(each) instead of sum(each).
                            CompletableFuture<String> headShaFuture =
                                    StringUtils.isNotBlank(savedHeadSha)
                                            ? CompletableFuture.supplyAsync(
                                                    () -> {
                                                        try {
                                                            return ghSvc.getPRHeadSha(
                                                                    owner, repo, number);
                                                        } catch (Exception ex) {
                                                            log.warn(
                                                                    "getPRHeadSha prefetch"
                                                                            + " failed: {}",
                                                                    ex.getMessage());
                                                            return "";
                                                        }
                                                    })
                                            : CompletableFuture.completedFuture("");

                            CompletableFuture<Boolean> mergedFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.isPRMerged(owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "isPRMerged failed: {}",
                                                            e.getMessage());
                                                    return false;
                                                }
                                            });

                            CompletableFuture<IntellijGitHubService.PendingReview> pendingFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.loadDraftReview(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "loadDraftReview failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> diffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getPRDiff(owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiff prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> validationDiffFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getPRDiffFull(owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getPRDiffFull prefetch failed: {}",
                                                            e.getMessage());
                                                    return null;
                                                }
                                            });

                            CompletableFuture<String> reviewsFuture =
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                try {
                                                    return ghSvc.getExistingReviewsSummary(
                                                            owner, repo, number);
                                                } catch (Exception e) {
                                                    log.warn(
                                                            "getExistingReviewsSummary prefetch"
                                                                    + " failed: {}",
                                                            e.getMessage());
                                                    return "";
                                                }
                                            });

                            boolean merged = mergedFuture.join();
                            IntellijGitHubService.PendingReview pending = pendingFuture.join();
                            String fetchedDiff = diffFuture.join();
                            String fetchedValidationDiff = validationDiffFuture.join();
                            String fetchedReviews = reviewsFuture.join();
                            String currentHeadSha = headShaFuture.join();
                            String effectiveValidationDiff =
                                    StringUtils.isNotBlank(fetchedValidationDiff)
                                            ? fetchedValidationDiff
                                            : fetchedDiff;
                            ProviderReadinessDto providerReadiness = currentProviderReadiness();

                            // Write prefetched data only if the same PR is still active to
                            // avoid clobbering state for a PR the user already switched away from.
                            synchronized (WebviewPanel.this) {
                                if (activePR != pr || selectionRevision != revision) {
                                    return;
                                }
                                prefetchedDiff = fetchedDiff;
                                prefetchedValidationDiff = effectiveValidationDiff;
                                prefetchedExistingReviews = fetchedReviews;
                            }

                            // Delete stale draft on a merged PR, best-effort.
                            if (merged && pending != null) {
                                try {
                                    ghSvc.deleteDraftReview(owner, repo, number, pending.id());
                                } catch (Exception e) {
                                    log.warn("deleteDraftReview failed: {}", e.getMessage());
                                }
                                pending = null;
                            }

                            if (merged) {
                                pushMessage(
                                        new PrDraftStatusMsg(
                                                "prDraftStatusUpdated",
                                                number,
                                                owner,
                                                repo,
                                                false));
                                pushMessage(
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                key,
                                                "MERGED",
                                                null,
                                                null,
                                                fetchedDiff,
                                                effectiveValidationDiff,
                                                false,
                                                false,
                                                "PR is merged.",
                                                providerReadiness));
                                return;
                            }

                            if (pending != null) {
                                boolean stale =
                                        StringUtils.isNotBlank(savedHeadSha)
                                                && !savedHeadSha.equals(currentHeadSha);
                                ReviewResultDto dto = ReviewMapper.INSTANCE.toDto(pending.result());
                                pushMessage(
                                        new PrDraftStatusMsg(
                                                "prDraftStatusUpdated", number, owner, repo, true));
                                pushMessage(
                                        new DraftLoadedMsg(
                                                "draftLoaded",
                                                key,
                                                "DRAFT_PRESENT",
                                                pending.id(),
                                                dto,
                                                prefetchedDiff,
                                                effectiveValidationDiff,
                                                stale,
                                                pending.importedFromGitHub(),
                                                "Loaded pending draft review.",
                                                providerReadiness));
                                synchronized (WebviewPanel.this) {
                                    if (activePR == pr && selectionRevision == revision) {
                                        pendingReviewId = pending.id();
                                        pendingReviewKey = key;
                                        lastResult = pending.result();
                                    }
                                }
                                return;
                            }

                            pushMessage(
                                    new PrDraftStatusMsg(
                                            "prDraftStatusUpdated", number, owner, repo, false));
                            pushMessage(
                                    new DraftLoadedMsg(
                                            "draftLoaded",
                                            key,
                                            "NO_DRAFT",
                                            null,
                                            null,
                                            null,
                                            effectiveValidationDiff,
                                            false,
                                            false,
                                            "",
                                            providerReadiness));
                        });
    }

    // --- generateReview ---

    private void handleGenerateReview(
            int number,
            String owner,
            String repo,
            String overrideDiff,
            String overrideFocusAreas,
            String overrideCustomInstructions) {
        String key = bridgePrKey(number, owner, repo);
        final PullRequest pr;
        final long reviewRevision;
        synchronized (this) {
            pr = activePR;
            reviewRevision = selectionRevision;
        }
        if (!matchesPrRequest(pr, number, owner, repo)) {
            pushMessage(new ErrorMsg("reviewError", key, "PR not found."));
            return;
        }

        // Provider preflight: fail fast with actionable guidance instead of a raw CLI spawn error
        // when the configured review provider's binary isn't installed/resolvable.
        ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
        if (!isProviderBinaryAvailable(provider)) {
            pushMessage(
                    new ErrorMsg(
                            "reviewError",
                            key,
                            UserFacingErrors.forProviderNotInstalled(provider)));
            return;
        }

        // Dispatch all blocking work to a pooled thread so the JCEF bridge returns immediately
        // and status messages can flow during the network-fetch phase.
        final PullRequest finalPr = pr;
        getApplication()
                .executeOnPooledThread(
                        () -> {
                            if (!isCurrentSession(finalPr, reviewRevision)) {
                                return;
                            }
                            // Atomically snapshot prefetched data to prevent check-then-act
                            // races with a concurrent handleSelectPR on the JCEF bridge thread.
                            String snapshotDiff;
                            String snapshotValidationDiff;
                            String snapshotReviews;
                            synchronized (WebviewPanel.this) {
                                boolean samepr = activePR == finalPr;
                                snapshotDiff = samepr ? prefetchedDiff : null;
                                snapshotValidationDiff = samepr ? prefetchedValidationDiff : null;
                                snapshotReviews = samepr ? prefetchedExistingReviews : null;
                            }

                            // Reuse prefetched diff; fall back to live fetch only if stale.
                            String diff;
                            if (StringUtils.isNotBlank(overrideDiff)) {
                                diff = overrideDiff;
                            } else if (StringUtils.isNotBlank(snapshotDiff)) {
                                diff = snapshotDiff;
                            } else {
                                publishIfCurrent(
                                        finalPr,
                                        reviewRevision,
                                        new ReviewGeneratingMsg(
                                                "reviewGenerating", key, "Fetching diff…"));
                                try {
                                    diff = ghSvc.getPRDiff(owner, repo, number);
                                } catch (Exception e) {
                                    publishIfCurrent(
                                            finalPr,
                                            reviewRevision,
                                            new ErrorMsg(
                                                    "reviewError",
                                                    key,
                                                    UserFacingErrors.forGitHub(
                                                            e, "load the PR diff")));
                                    return;
                                }
                            }

                            String validationDiff;
                            if (StringUtils.isNotBlank(snapshotValidationDiff)) {
                                validationDiff = snapshotValidationDiff;
                            } else {
                                try {
                                    validationDiff = ghSvc.getPRDiffFull(owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getPRDiffFull failed; falling back to truncated diff: {}",
                                            e.getMessage());
                                    validationDiff = diff;
                                }
                            }

                            // Reuse prefetched existing reviews; fall back to live fetch only if
                            // stale.
                            String existingReviews;
                            if (snapshotReviews != null) {
                                existingReviews = snapshotReviews;
                            } else {
                                try {
                                    existingReviews =
                                            ghSvc.getExistingReviewsSummary(owner, repo, number);
                                } catch (Exception e) {
                                    log.warn(
                                            "getExistingReviewsSummary failed: {}", e.getMessage());
                                    existingReviews = "";
                                }
                            }

                            IntellijClaudeService reviewService = claudeService;
                            try {
                                reviewService = resolvePrClaudeService(finalPr, true);
                            } catch (Exception e) {
                                log.warn(
                                        "Worktree resolution for PR #{} failed; falling back to"
                                                + " project dir: {}",
                                        number,
                                        e.getMessage());
                                reviewService = claudeService;
                            }

                            final IntellijClaudeService finalReviewService = reviewService;

                            // Kick off the review — callbacks fired on EDT
                            final String finalDiff = diff;
                            final String finalValidationDiff = validationDiff;
                            final String finalExisting = existingReviews;
                            java.io.File guidelinesDir;
                            ReviewResult priorResult;
                            synchronized (this) {
                                if (!isCurrentSession(finalPr, reviewRevision)) {
                                    return;
                                }
                                activeReviewService = finalReviewService;
                                guidelinesDir =
                                        activePrWorktreeDir != null
                                                ? activePrWorktreeDir
                                                : (project.getBasePath() != null
                                                        ? new java.io.File(project.getBasePath())
                                                        : null);
                                priorResult = lastResult;
                            }
                            publishIfCurrent(
                                    finalPr,
                                    reviewRevision,
                                    new ReviewGeneratingMsg(
                                            "reviewGenerating", key, "Sending review request…"));
                            final String finalGuidelines = readRepoGuidelines(guidelinesDir);
                            final String finalPriorReview = formatPriorReview(priorResult);
                            final String finalFocusAreas =
                                    StringUtils.isNotBlank(overrideFocusAreas)
                                            ? overrideFocusAreas
                                            : PluginSettings.getInstance().getReviewFocusAreas();
                            final String finalCustomInstructions =
                                    StringUtils.isNotBlank(overrideCustomInstructions)
                                            ? overrideCustomInstructions
                                            : PluginSettings.getInstance()
                                                    .getReviewCustomInstructions();
                            finalReviewService.reviewPR(
                                    new PRReviewRequest(
                                            finalPr,
                                            finalDiff,
                                            "",
                                            finalPriorReview,
                                            finalExisting,
                                            finalGuidelines,
                                            finalFocusAreas,
                                            finalCustomInstructions),
                                    statusMsg ->
                                            publishIfCurrent(
                                                    finalPr,
                                                    reviewRevision,
                                                    new ReviewGeneratingMsg(
                                                            "reviewGenerating", key, statusMsg)),
                                    (kind, chunk) ->
                                            publishIfCurrent(
                                                    finalPr,
                                                    reviewRevision,
                                                    new ReviewChunkMsg(
                                                            "reviewChunk", key, kind, chunk)),
                                    result -> {
                                        if (!isCurrentSession(finalPr, reviewRevision)) {
                                            return;
                                        }
                                        activeReviewService = claudeService;
                                        if (result == null) {
                                            pushMessage(
                                                    new ErrorMsg(
                                                            "reviewError",
                                                            key,
                                                            UserFacingErrors.forProvider(
                                                                    provider,
                                                                    new Exception(
                                                                            "Provider produced no output"),
                                                                    "generate review")));
                                            return;
                                        }
                                        lastResult = result;
                                        pendingReviewId = null;
                                        pushMessage(
                                                new ReviewResultMsg(
                                                        "reviewResult",
                                                        key,
                                                        ReviewMapper.INSTANCE.toDto(result),
                                                        finalDiff,
                                                        finalValidationDiff));
                                    },
                                    err -> {
                                        if (!isCurrentSession(finalPr, reviewRevision)) {
                                            return;
                                        }
                                        activeReviewService = claudeService;
                                        // Cancellations are user-initiated — don't surface as
                                        // errors.
                                        String lower = err.toLowerCase(java.util.Locale.ROOT);
                                        if (!lower.contains("cancel")
                                                && !lower.contains("interrupt")) {
                                            pushMessage(new ErrorMsg("reviewError", key, err));
                                        }
                                    });
                        });
    }

    private static boolean isProviderBinaryAvailable(ReviewProvider provider) {
        return provider == ReviewProvider.COPILOT
                ? CopilotService.isBinaryAvailable()
                : ClaudeService.isBinaryAvailable();
    }

    private static ProviderReadinessDto currentProviderReadiness() {
        ReviewProvider provider = PluginSettings.getInstance().getReviewProvider();
        boolean available = isProviderBinaryAvailable(provider);
        return new ProviderReadinessDto(
                provider == ReviewProvider.COPILOT ? "copilot" : "claude",
                available,
                available
                        ? "Ready to generate reviews with the configured CLI."
                        : UserFacingErrors.forProviderNotInstalled(provider));
    }

    // --- saveDraft ---

    private void handleSaveDraft(
            int number,
            String owner,
            String repo,
            ReviewResult bridgeResult,
            List<LineComment> orphans) {
        String key = bridgePrKey(number, owner, repo);
        if (!isActivePrKey(key)) {
            pushMessage(
                    new ErrorMsg(
                            "draftSaveError",
                            key,
                            "The selected pull request changed before the draft could be saved."));
            return;
        }
        long revision = selectionRevision;
        ReviewResult result = bridgeResult != null ? bridgeResult : lastResult;
        if (result == null) {
            pushMessage(new ErrorMsg("draftSaveError", key, "No review result to save."));
            return;
        }
        lastResult = result;

        IntellijGitHubService.SaveDraftResult saved;
        try {
            saved = ghSvc.saveDraftReview(owner, repo, number, result, orphans);
        } catch (Exception e) {
            pushMessage(
                    new ErrorMsg(
                            "draftSaveError",
                            key,
                            UserFacingErrors.forGitHub(e, "save the draft review")));
            return;
        }

        String headSha = "";
        try {
            headSha = ghSvc.getPRHeadSha(owner, repo, number);
        } catch (Exception e) {
            log.warn("getPRHeadSha failed during saveDraft: {}", e.getMessage());
        }

        PullRequest pr =
                cachedPRs.stream()
                        .filter(
                                p ->
                                        p.getNumber() == number
                                                && p.getOwner().equals(owner)
                                                && p.getRepo().equals(repo))
                        .findFirst()
                        .orElse(null);
        String title = pr != null ? pr.getTitle() : "";
        pendingIndex.add(owner, repo, number, title, headSha);
        if (!isActivePrKey(key) || selectionRevision != revision) {
            return;
        }
        pendingReviewId = saved.reviewId();
        pendingReviewKey = key;

        pushMessage(
                new DraftSavedMsg("draftSaved", key, saved.reviewId(), saved.commentsDropped()));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, true));
    }

    // --- submitReview ---

    private void handleSubmitReview(
            int number, String owner, String repo, String verdict, String comment) {
        String key = bridgePrKey(number, owner, repo);
        String reviewId = pendingReviewId;
        if (StringUtils.isBlank(reviewId)
                || !isActivePrKey(key)
                || !StringUtils.equals(pendingReviewKey, key)) {
            pushMessage(
                    new ErrorMsg(
                            "reviewSubmitError",
                            key,
                            "No pending draft review belongs to the selected pull request."));
            return;
        }

        try {
            ghSvc.submitDraftReview(owner, repo, number, reviewId, verdict, comment);
        } catch (Exception e) {
            pushMessage(
                    new ErrorMsg(
                            "reviewSubmitError",
                            key,
                            UserFacingErrors.forGitHub(e, "submit the draft review")));
            return;
        }

        pendingIndex.remove(owner, repo, number);
        if (StringUtils.equals(pendingReviewId, reviewId)
                && StringUtils.equals(pendingReviewKey, key)) {
            lastResult = null;
            pendingReviewId = null;
            pendingReviewKey = null;
        }

        pushMessage(new SimpleMsg("reviewSubmitted", key));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    // --- deleteDraft ---

    private void handleDeleteDraft(int number, String owner, String repo) {
        String key = bridgePrKey(number, owner, repo);
        String reviewId = pendingReviewId;
        if (StringUtils.isBlank(reviewId)
                || !isActivePrKey(key)
                || !StringUtils.equals(pendingReviewKey, key)) {
            pushMessage(
                    new ErrorMsg(
                            "draftDeleteError",
                            key,
                            "No pending draft review belongs to the selected pull request."));
            return;
        }

        try {
            ghSvc.deleteDraftReview(owner, repo, number, reviewId);
        } catch (Exception e) {
            pushMessage(
                    new ErrorMsg(
                            "draftDeleteError",
                            key,
                            UserFacingErrors.forGitHub(e, "delete the draft review")));
            return;
        }

        pendingIndex.remove(owner, repo, number);
        if (StringUtils.equals(pendingReviewId, reviewId)
                && StringUtils.equals(pendingReviewKey, key)) {
            lastResult = null;
            pendingReviewId = null;
            pendingReviewKey = null;
        }

        pushMessage(new SimpleMsg("draftDeleted", key));
        pushMessage(new PrDraftStatusMsg("prDraftStatusUpdated", number, owner, repo, false));
    }

    // --- askClaude ---

    private void handleAskClaude(String question, String context) {
        if (StringUtils.isBlank(question)) {
            return;
        }

        final PullRequest pr;
        final long chatRevision;
        synchronized (this) {
            pr = activePR;
            chatRevision = selectionRevision;
        }
        if (pr == null) {
            pushMessage(new ErrorMsg("chatError", null, "No PR selected."));
            return;
        }
        String key = bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo());

        IntellijClaudeService chatService = resolvePrClaudeService(pr, false);

        // When the user has selected a code snippet, use a focused prompt (no history, no PR
        // context) — matching VS Code's buildFocusedChatPrompt path. Responses for focused
        // questions are not stored in chatHistory since they are context-specific.
        if (StringUtils.isNotBlank(context)) {
            chatService.chatFocused(
                    context,
                    question,
                    chunk ->
                            publishIfCurrent(
                                    pr, chatRevision, new ChatChunkMsg("chatChunk", key, chunk)),
                    response -> {
                        if (!isCurrentSession(pr, chatRevision)) {
                            return;
                        }
                        pushMessage(new ChatResponseMsg("chatResponse", key, response));
                    },
                    err -> {
                        if (!isCurrentSession(pr, chatRevision)) {
                            return;
                        }
                        pushMessage(new ErrorMsg("chatError", key, err));
                    });
            return;
        }

        String prContext = buildPrContext(pr);
        List<ChatMessage> history = chatHistory;

        chatService.chat(
                prContext,
                history,
                question,
                chunk ->
                        publishIfCurrent(
                                pr, chatRevision, new ChatChunkMsg("chatChunk", key, chunk)),
                response -> {
                    if (!isCurrentSession(pr, chatRevision)) {
                        return;
                    }
                    List<ChatMessage> updated = new ArrayList<>(history);
                    updated.add(new ChatMessage(ChatMessage.Role.USER, question));
                    updated.add(new ChatMessage(ChatMessage.Role.ASSISTANT, response));
                    chatHistory = updated;
                    pushMessage(new ChatResponseMsg("chatResponse", key, response));
                },
                err -> {
                    if (!isCurrentSession(pr, chatRevision)) {
                        return;
                    }
                    pushMessage(new ErrorMsg("chatError", key, err));
                });
    }

    static String worktreeKey(int number, String owner, String repo) {
        return owner.toLowerCase(java.util.Locale.ROOT)
                + "/"
                + repo.toLowerCase(java.util.Locale.ROOT)
                + "#"
                + number;
    }

    static String bridgePrKey(int number, String owner, String repo) {
        return owner + "/" + repo + "#" + number;
    }

    static String normalizeSearchScope(String value) {
        return switch (value) {
            case "authored", "assigned", "reviewRequested" -> value;
            default -> "currentRepo";
        };
    }

    static boolean isSamePr(PullRequest left, PullRequest right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getNumber() == right.getNumber()
                && StringUtils.equalsIgnoreCase(left.getOwner(), right.getOwner())
                && StringUtils.equalsIgnoreCase(left.getRepo(), right.getRepo());
    }

    static boolean matchesPrRequest(PullRequest pr, int number, String owner, String repo) {
        return pr != null
                && pr.getNumber() == number
                && StringUtils.equalsIgnoreCase(pr.getOwner(), owner)
                && StringUtils.equalsIgnoreCase(pr.getRepo(), repo);
    }

    static boolean isCurrentSession(
            PullRequest currentPr,
            long currentRevision,
            PullRequest expectedPr,
            long expectedRevision) {
        return currentPr == expectedPr && currentRevision == expectedRevision;
    }

    private boolean isCurrentSession(PullRequest expectedPr, long expectedRevision) {
        synchronized (this) {
            return isCurrentSession(activePR, selectionRevision, expectedPr, expectedRevision);
        }
    }

    private void publishIfCurrent(PullRequest expectedPr, long expectedRevision, Object message) {
        if (isCurrentSession(expectedPr, expectedRevision)) {
            pushMessage(message);
        }
    }

    private boolean isActivePrKey(String key) {
        PullRequest pr = activePR;
        return pr != null
                && StringUtils.equals(
                        key, bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo()));
    }

    /** Candidate contributor-doc files, in priority order, scanned for repo review guidelines. */
    private static final String[] GUIDELINE_FILES = {
        "AGENTS.md",
        "CONTRIBUTING.md",
        ".github/CONTRIBUTING.md",
        "docs/CONTRIBUTING.md",
        ".github/pull_request_template.md",
    };

    /** Cap on guideline bytes fed to the prompt so a large doc can't blow up the context. */
    private static final int MAX_GUIDELINES_BYTES = 6000;

    /**
     * Reads repo contributor docs from {@code dir} (the PR-branch worktree or project base dir),
     * concatenated and capped at {@link #MAX_GUIDELINES_BYTES}, so the model can weight findings
     * against the project's own review conventions. Returns an empty string when none are found.
     */
    private static String readRepoGuidelines(java.io.File dir) {
        if (dir == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (String rel : GUIDELINE_FILES) {
            if (total >= MAX_GUIDELINES_BYTES) {
                break;
            }
            java.io.File f = new java.io.File(dir, rel);
            if (!f.isFile()) {
                continue;
            }
            try {
                String content = Files.readString(f.toPath()).trim();
                if (content.isEmpty()) {
                    continue;
                }
                int remaining = MAX_GUIDELINES_BYTES - total;
                if (content.length() > remaining) {
                    content = content.substring(0, remaining) + "\n...(truncated)";
                }
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                sb.append("## ").append(rel).append("\n").append(content);
                total += content.length();
            } catch (IOException e) {
                // unreadable — skip
            }
        }
        return sb.toString();
    }

    /** Formats a prior generated review as compact context for a re-generation prompt. */
    private static String formatPriorReview(ReviewResult result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Verdict: ").append(result.getVerdict());
        if (StringUtils.isNotBlank(result.getSummary())) {
            sb.append("\nSummary: ").append(result.getSummary());
        }
        for (LineComment c : result.getLineComments()) {
            sb.append("\n- ")
                    .append(c.getFile())
                    .append(":")
                    .append(c.getLine())
                    .append(" [")
                    .append(c.getType())
                    .append("] ")
                    .append(c.getBody());
        }
        return sb.toString();
    }

    private IntellijClaudeService resolvePrClaudeService(PullRequest pr, boolean emitStatus) {
        // Phase 1: quick local checks — hold the lock briefly to read shared fields.
        final String key;
        final java.io.File detectedRoot;
        synchronized (this) {
            if (pr == null || !isSamePr(activePR, pr)) {
                return claudeService;
            }
            key = worktreeKey(pr.getNumber(), pr.getOwner(), pr.getRepo());
            if (activePrClaudeService != null && key.equals(activePrWorktreeKey)) {
                return activePrClaudeService;
            }
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                return claudeService;
            }
            java.io.File root = worktreeService.findGitRoot(new java.io.File(projectPath));
            String currentRepo = ghSvc.detectCurrentRepo(projectPath);
            boolean sameRepo =
                    currentRepo != null
                            && currentRepo.equalsIgnoreCase(pr.getOwner() + "/" + pr.getRepo());
            if (root == null || !sameRepo) {
                return claudeService;
            }
            detectedRoot = root;
        }

        // Phase 2: I/O outside the lock so other bridge operations are not blocked.
        if (emitStatus) {
            pushMessage(
                    new ReviewGeneratingMsg(
                            "reviewGenerating",
                            bridgePrKey(pr.getNumber(), pr.getOwner(), pr.getRepo()),
                            "Preparing PR branch…"));
        }

        try {
            IntellijGitHubService.PRHeadInfo headInfo =
                    ghSvc.getPRHeadInfo(pr.getOwner(), pr.getRepo(), pr.getNumber());
            if (headInfo.ref().isBlank()) {
                return claudeService;
            }

            // Include randomness to avoid path collisions on rapid consecutive calls.
            String unique =
                    pr.getNumber()
                            + "-"
                            + System.currentTimeMillis()
                            + "-"
                            + Long.toHexString(
                                    ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE);
            java.io.File wt =
                    new java.io.File(System.getProperty("java.io.tmpdir"), "pr-pilot-wt-" + unique);
            if (headInfo.isFork()) {
                worktreeService.createWorktreeFromFork(
                        detectedRoot, headInfo.forkCloneUrl(), headInfo.ref(), wt);
            } else {
                worktreeService.createWorktree(detectedRoot, headInfo.ref(), wt);
            }

            // Phase 3: write results under lock; double-check the PR hasn't changed.
            synchronized (this) {
                if (!isSamePr(activePR, pr)) {
                    getApplication()
                            .executeOnPooledThread(
                                    () -> worktreeService.removeWorktree(detectedRoot, wt));
                    return claudeService;
                }
                // A concurrent call may have already built the same worktree — discard ours.
                if (activePrClaudeService != null && key.equals(activePrWorktreeKey)) {
                    getApplication()
                            .executeOnPooledThread(
                                    () -> worktreeService.removeWorktree(detectedRoot, wt));
                    return activePrClaudeService;
                }
                activePrClaudeService = new IntellijClaudeService(wt.getAbsolutePath());
                activePrWorktreeDir = wt;
                activePrGitRoot = detectedRoot;
                activePrWorktreeKey = key;
                log.info("Using worktree {} for PR #{}", wt, pr.getNumber());
                return activePrClaudeService;
            }
        } catch (Exception e) {
            log.warn(
                    "Worktree creation for PR #{} failed; falling back to project dir: {}",
                    pr.getNumber(),
                    e.getMessage());
            return claudeService;
        }
    }

    private void clearActivePrWorktree() {
        final java.io.File worktreeToRemove;
        final java.io.File gitRoot;
        synchronized (this) {
            worktreeToRemove = activePrWorktreeDir;
            gitRoot = activePrGitRoot;
            activePrClaudeService = null;
            activePrWorktreeDir = null;
            activePrGitRoot = null;
            activePrWorktreeKey = null;
        }
        if (worktreeToRemove != null && gitRoot != null) {
            getApplication()
                    .executeOnPooledThread(
                            () -> worktreeService.removeWorktree(gitRoot, worktreeToRemove));
        }
    }

    private String buildPrContext(PullRequest pr) {
        StringBuilder sb = new StringBuilder();
        sb.append("PR #").append(pr.getNumber()).append(": ").append(pr.getTitle()).append("\n");
        sb.append("Author: @").append(pr.getAuthor()).append("\n");
        sb.append("Repo: ").append(pr.getOwner()).append("/").append(pr.getRepo()).append("\n");

        String body = pr.getBody();
        if (StringUtils.isNotBlank(body)) {
            sb.append("\nPR Description:\n").append(body).append("\n");
        }

        ReviewResult result = lastResult;
        if (result != null) {
            sb.append("\nReview verdict: ").append(result.getVerdict()).append("\n");
            sb.append("Review summary: ").append(result.getSummary()).append("\n");
        }

        String diff = prefetchedDiff;
        if (StringUtils.isNotBlank(diff)) {
            sb.append("\nDiff:\n").append(diff);
        }

        return sb.toString();
    }

    // --- Helpers ---

    /**
     * Serializes {@code payload} to JSON and pushes it into the webview via {@code
     * __handleMessage}. The JSON is embedded directly as a JS expression (JSON is a valid JS
     * literal) instead of as a quoted string, avoiding script-injection risk from untrusted PR
     * content. U+2028/U+2029 are escaped because they are line terminators in JS but appear as
     * literal characters inside JSON strings.
     */
    private void pushMessage(Object payload) {
        try {
            ObjectNode versioned = mapper.valueToTree(payload);
            versioned.put("protocolVersion", BridgeMessageValidator.PROTOCOL_VERSION);
            String json = mapper.writeValueAsString(versioned);
            String safe = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            browser.getCefBrowser()
                    .executeJavaScript(
                            "if(window.__handleMessage){window.__handleMessage(" + safe + ");}",
                            browser.getCefBrowser().getURL(),
                            0);
        } catch (JsonProcessingException e) {
            log.warn("pushMessage serialization failed: {}", e.getMessage());
        }
    }

    private void pushCurrentTheme() {
        getApplication()
                .invokeLater(
                        () -> {
                            if (disposed) {
                                return;
                            }
                            String lafName =
                                    StringUtils.defaultString(
                                                    UIManager.getLookAndFeel() == null
                                                            ? null
                                                            : UIManager.getLookAndFeel().getName())
                                            .toLowerCase(java.util.Locale.ROOT);
                            boolean highContrast = lafName.contains("contrast");
                            boolean dark = UIUtil.isUnderDarcula();
                            String theme = HostThemeClassifier.classify(dark, highContrast);
                            pushMessage(new ThemeChangedMsg("themeChanged", theme));
                        });
    }

    /** Pushes the PR list into the webview via the bridge. Call from the EDT. */
    public void loadPRs(
            List<PullRequest> prs,
            String defaultRepo,
            String searchScope,
            String currentRepo,
            boolean limited) {
        cachedPRs = prs;
        Set<String> draftKeys =
                pendingIndex.list().stream()
                        .map(e -> e.owner() + "/" + e.repo() + "#" + e.number())
                        .collect(java.util.stream.Collectors.toSet());
        List<WebviewPr> dtos =
                prs.stream()
                        .map(
                                pr ->
                                        toWebviewPr(
                                                pr,
                                                draftKeys.contains(
                                                        pr.getOwner()
                                                                + "/"
                                                                + pr.getRepo()
                                                                + "#"
                                                                + pr.getNumber())))
                        .toList();
        pushMessage(
                new PrListMessage(
                        "prListLoaded",
                        dtos,
                        defaultRepo,
                        new PrListStatus(searchScope, currentRepo, PR_SEARCH_LIMIT, limited)));
    }

    public void setOnPRSelected(Consumer<PullRequest> callback) {
        this.onPRSelected = callback;
    }

    public void setOnPageReady(Runnable callback) {
        this.onPageReady = callback;
    }

    /** Pushes a setup-required screen into the webview. Call from the EDT. */
    public void pushSetupRequired(String reason, String detail) {
        pushMessage(new SetupRequiredMsg("setupRequired", reason, detail));
    }

    public void activatePr(PullRequest pr, String source) {
        boolean hasReviewDraft = pendingIndex.hasDraft(pr.getOwner(), pr.getRepo(), pr.getNumber());
        if (cachedPRs.stream().anyMatch(existing -> isSamePr(existing, pr))) {
            cachedPRs =
                    cachedPRs.stream()
                            .map(existing -> isSamePr(existing, pr) ? pr : existing)
                            .toList();
        } else {
            List<PullRequest> next = new ArrayList<>();
            next.add(pr);
            next.addAll(cachedPRs);
            cachedPRs = next;
        }
        pushMessage(new ActivatePrMsg("activatePR", toWebviewPr(pr, hasReviewDraft), source));
    }

    private static WebviewPr toWebviewPr(PullRequest pr, boolean hasReviewDraft) {
        return new WebviewPr(
                pr.getNumber(),
                pr.getTitle(),
                pr.getOwner(),
                pr.getRepo(),
                pr.getAuthor(),
                pr.getCreatedAt(),
                pr.getHtmlUrl(),
                pr.isDraft(),
                hasReviewDraft);
    }

    public String getPrStateFilter() {
        return prStateFilter;
    }

    public String getSearchScope() {
        return searchScope;
    }

    public void reload() {
        if (!disposed) {
            browser.getCefBrowser().reloadIgnoreCache();
        }
    }

    public JComponent getComponent() {
        return browserPanel;
    }

    @Override
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        clearActivePrWorktree();
        HttpServer server = httpServer;
        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception e) {
                log.warn("HttpServer.stop failed: {}", e.getMessage());
            }
            httpServer = null;
        }
        Disposer.dispose(bridgeQuery);
        Disposer.dispose(browser);
    }
}
