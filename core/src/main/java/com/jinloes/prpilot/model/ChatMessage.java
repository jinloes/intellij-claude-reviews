package com.jinloes.prpilot.model;

/** A single chat turn (user question or assistant reply) in a review chat session. */
public final class ChatMessage {

    /** Who authored the chat turn. */
    public enum Role {
        USER,
        ASSISTANT
    }

    private final Role role;
    private final String content;

    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
