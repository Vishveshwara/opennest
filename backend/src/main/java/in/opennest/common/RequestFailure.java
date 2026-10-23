package in.opennest.common;

import java.util.Objects;

public final class RequestFailure extends RuntimeException {

    private final Kind kind;

    public RequestFailure(Kind kind) {
        super("OpenNest request failed", null, false, false);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        INVALID_INPUT, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMITED, UNAVAILABLE
    }
}
