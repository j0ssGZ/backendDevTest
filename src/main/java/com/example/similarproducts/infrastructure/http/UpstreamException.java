package com.example.similarproducts.infrastructure.http;

/** Infrastructure failure with its original cause retained for diagnostics, never sent to clients. */
public final class UpstreamException extends RuntimeException {

    public enum Kind { HTTP_STATUS, TRANSPORT, INVALID_PAYLOAD }

    private final Kind kind;
    private final int upstreamStatus;

    public UpstreamException(Kind kind, int upstreamStatus, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
    }

    public Kind kind() {
        return kind;
    }

    public int upstreamStatus() {
        return upstreamStatus;
    }

    static UpstreamException invalid(String message) {
        return new UpstreamException(Kind.INVALID_PAYLOAD, 0, message, null);
    }
}
