package com.jinloes.prpilot.services;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.jinloes.prpilot.model.LineComment;
import com.jinloes.prpilot.model.PullRequest;
import com.jinloes.prpilot.model.ReviewResult;
import com.jinloes.prpilot.settings.PluginSettings;
import com.jinloes.prpilot.sidecar.pr.DraftReviewCodec;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewMutationService;
import com.jinloes.prpilot.sidecar.pr.DraftReviewResult;
import com.jinloes.prpilot.sidecar.pr.DraftReviewService;
import com.jinloes.prpilot.sidecar.pr.ExistingReviewsResult;
import com.jinloes.prpilot.sidecar.pr.PrDetail;
import com.jinloes.prpilot.sidecar.pr.PrDetailResult;
import com.jinloes.prpilot.sidecar.pr.PrDetailService;
import com.jinloes.prpilot.sidecar.pr.PrDiffResult;
import com.jinloes.prpilot.sidecar.pr.PrDiffService;
import com.jinloes.prpilot.sidecar.pr.PrListResult;
import com.jinloes.prpilot.sidecar.pr.PrListService;
import com.jinloes.prpilot.sidecar.pr.PrSearchResult;
import com.jinloes.prpilot.sidecar.pr.PrSupplementalService;
import com.jinloes.prpilot.sidecar.pr.PullRequestSummary;
import com.jinloes.prpilot.sidecar.pr.StarredReposResult;
import com.jinloes.prpilot.sidecar.repo.DetectResult;
import com.jinloes.prpilot.sidecar.repo.DetectStatus;
import com.jinloes.prpilot.sidecar.repo.RepoDetector;
import java.io.IOException;
import java.util.List;

/** IntelliJ adapter over the shared Java GitHub engine. All calls execute in the IDE JVM. */
@Service
public final class IntellijGitHubService {
    private final PrSupplementalService supplementalService = new PrSupplementalService();
    private final PrListService listService = new PrListService();
    private final RepoDetector repoDetector = new RepoDetector();
    private final PrDiffService diffService = new PrDiffService();
    private final PrDetailService detailService = new PrDetailService();
    private final DraftReviewService draftReviewService = new DraftReviewService();
    private final DraftReviewMutationService mutationService = new DraftReviewMutationService();

    public static IntellijGitHubService getInstance() {
        return ApplicationManager.getApplication().getService(IntellijGitHubService.class);
    }

    private String baseUrl() {
        return PluginSettings.getInstance().getGithubBaseUrl();
    }

    public List<PullRequest> searchPRs(String query) throws IOException {
        return searchPRs(query, 50);
    }

    public List<PullRequest> searchPRs(String query, int perPage) throws IOException {
        PrSearchResult result =
                supplementalService.search(
                        new PrSupplementalService.SearchParams(baseUrl(), query, perPage));
        requireOk(result.status(), result.message());
        return toPullRequests(result.prs());
    }

    public PullRequestList listPullRequests(String projectPath, String state, String searchScope)
            throws IOException {
        String currentRepo = detectCurrentRepo(projectPath);
        PrListResult result =
                listService.list(
                        new PrListService.PrListParams(baseUrl(), state, searchScope, currentRepo));
        requireOk(result.status(), result.message());
        return new PullRequestList(
                toPullRequests(result.prs()), result.limited(), currentRepo, result.query());
    }

    public String detectCurrentRepo(String projectPath) {
        DetectResult result = repoDetector.detect(projectPath);
        return result.status() == DetectStatus.FOUND
                ? result.repository().owner() + "/" + result.repository().repo()
                : null;
    }

    public List<String> getStarredRepos() throws IOException {
        StarredReposResult result = supplementalService.starred(baseUrl());
        requireOk(result.status(), result.message());
        return result.repositories();
    }

    public String getPRDiff(String owner, String repo, int prNumber) throws IOException {
        return getDiff(owner, repo, prNumber, "review");
    }

    public String getPRDiffFull(String owner, String repo, int prNumber) throws IOException {
        return getDiff(owner, repo, prNumber, "validation");
    }

    public SaveDraftResult saveDraftReview(
            String owner, String repo, int number, ReviewResult review, List<LineComment> orphans)
            throws IOException {
        DraftReviewMutationResult result =
                mutationService.save(
                        new DraftReviewMutationService.SaveParams(
                                baseUrl(),
                                owner,
                                repo,
                                number,
                                review.getSummary(),
                                review.getVerdict(),
                                review.getLineComments().stream()
                                        .map(IntellijGitHubService::toInput)
                                        .toList(),
                                orphans.stream().map(IntellijGitHubService::toInput).toList()));
        requireOk(result.status(), result.message());
        return new SaveDraftResult(result.reviewId(), result.commentsDropped());
    }

    public PendingReview loadDraftReview(String owner, String repo, int number) throws IOException {
        DraftReviewResult result = draftReviewService.load(baseUrl(), owner, repo, number);
        if ("none".equals(result.status())) return null;
        requireOk(result.status(), result.message());
        DraftReviewCodec.DecodedReview decoded = result.review();
        List<LineComment> comments =
                decoded.lineComments().stream().map(IntellijGitHubService::toCore).toList();
        return new PendingReview(
                result.id(),
                new ReviewResult(decoded.summary(), decoded.verdict(), comments),
                decoded.importedFromGitHub(),
                result.commitId());
    }

    public void submitDraftReview(
            String owner, String repo, int number, String reviewId, String event, String body)
            throws IOException {
        DraftReviewMutationResult result =
                mutationService.submit(
                        new DraftReviewMutationService.SubmitParams(
                                baseUrl(), owner, repo, number, reviewId, event, body));
        requireOk(result.status(), result.message());
    }

    public void deleteDraftReview(String owner, String repo, int number, String reviewId)
            throws IOException {
        DraftReviewMutationResult result =
                mutationService.delete(
                        new DraftReviewMutationService.DeleteParams(
                                baseUrl(), owner, repo, number, reviewId));
        requireOk(result.status(), result.message());
    }

    public String getExistingReviewsSummary(String owner, String repo, int number)
            throws IOException {
        ExistingReviewsResult result =
                supplementalService.existingReviews(
                        new PrSupplementalService.IdentityParams(baseUrl(), owner, repo, number));
        requireOk(result.status(), result.message());
        return result.summary();
    }

    public boolean isPRMerged(String owner, String repo, int number) throws IOException {
        return detail(owner, repo, number).merged();
    }

    public String getPRHeadSha(String owner, String repo, int number) throws IOException {
        PrDetail.Head head = detail(owner, repo, number).head();
        return head == null ? "" : head.sha();
    }

    public PRHeadInfo getPRHeadInfo(String owner, String repo, int number) throws IOException {
        PrDetail detail = detail(owner, repo, number);
        PrDetail.Head head = detail.head();
        if (head == null) return new PRHeadInfo("", false, "");
        boolean fork =
                head.repoFullName() != null
                        && !head.repoFullName().isBlank()
                        && !head.repoFullName().equals(detail.baseRepoFullName());
        return new PRHeadInfo(
                head.ref(), fork, fork && head.cloneUrl() != null ? head.cloneUrl() : "");
    }

    private String getDiff(String owner, String repo, int number, String mode) throws IOException {
        PrDiffResult result =
                diffService.get(new PrDiffService.Params(baseUrl(), owner, repo, number, mode));
        requireOk(result.status(), result.message());
        return result.diff();
    }

    private PrDetail detail(String owner, String repo, int number) throws IOException {
        PrDetailResult result =
                detailService.get(
                        new PrDetailService.PrDetailParams(baseUrl(), owner, repo, number));
        requireOk(result.status(), result.message());
        return result.detail();
    }

    private static DraftReviewMutationService.CommentInput toInput(LineComment comment) {
        return new DraftReviewMutationService.CommentInput(
                comment.getFile(),
                comment.getLine(),
                comment.getType(),
                comment.getBody(),
                comment.getSeverity(),
                comment.getCategory(),
                comment.getConfidence(),
                comment.getRationale());
    }

    private static LineComment toCore(DraftReviewCodec.LineComment comment) {
        LineComment mapped =
                new LineComment(comment.file(), comment.line(), comment.type(), comment.body());
        mapped.setSeverity(comment.severity());
        mapped.setCategory(comment.category());
        mapped.setConfidence(comment.confidence());
        mapped.setRationale(comment.rationale());
        return mapped;
    }

    static List<PullRequest> toPullRequests(List<PullRequestSummary> prs) {
        return prs.stream()
                .map(
                        pr ->
                                new PullRequest(
                                        pr.title(),
                                        pr.htmlUrl(),
                                        pr.owner(),
                                        pr.repo(),
                                        pr.number(),
                                        "",
                                        pr.author(),
                                        pr.createdAt(),
                                        pr.isDraft()))
                .toList();
    }

    private static void requireOk(String status, String message) throws IOException {
        if (!"ok".equals(status)) throw new IOException(message);
    }

    public record SaveDraftResult(String reviewId, boolean commentsDropped) {}

    public record PendingReview(
            String id, ReviewResult result, boolean importedFromGitHub, String commitId) {}

    public record PRHeadInfo(String ref, boolean isFork, String forkCloneUrl) {}

    public record PullRequestList(
            List<PullRequest> pullRequests, boolean limited, String currentRepo, String query) {}
}
