package com.jinloes.prpilot.model;

/** Immutable value object describing a GitHub pull request. */
public final class PullRequest {

    private final String title;
    private final String htmlUrl;
    private final String owner;
    private final String repo;
    private final int number;
    private final String body;
    private final String author;
    private final String createdAt;
    private final boolean isDraft;

    public PullRequest(
            String title,
            String htmlUrl,
            String owner,
            String repo,
            int number,
            String body,
            String author,
            String createdAt) {
        this(title, htmlUrl, owner, repo, number, body, author, createdAt, false);
    }

    public PullRequest(
            String title,
            String htmlUrl,
            String owner,
            String repo,
            int number,
            String body,
            String author,
            String createdAt,
            boolean isDraft) {
        this.title = title;
        this.htmlUrl = htmlUrl;
        this.owner = owner;
        this.repo = repo;
        this.number = number;
        this.body = body;
        this.author = author;
        this.createdAt = createdAt;
        this.isDraft = isDraft;
    }

    public String getTitle() {
        return title;
    }

    public String getHtmlUrl() {
        return htmlUrl;
    }

    public String getOwner() {
        return owner;
    }

    public String getRepo() {
        return repo;
    }

    public int getNumber() {
        return number;
    }

    public String getBody() {
        return body;
    }

    public String getAuthor() {
        return author;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public boolean isDraft() {
        return isDraft;
    }

    @Override
    public String toString() {
        return "PullRequest(owner="
                + owner
                + ", repo="
                + repo
                + ", number="
                + number
                + ", title="
                + title
                + ")";
    }
}
