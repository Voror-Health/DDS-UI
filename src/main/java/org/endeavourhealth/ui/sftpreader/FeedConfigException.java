package org.endeavourhealth.ui.sftpreader;

/**
 * VHP: a refused Add Feed / Add or Delete Interface Type request. {@code SftpReaderEndpoint} answers it with {@link #getStatus()}
 * and {@code {"message": ...}} (the page shows the message). It is not a {@code MappedException}, because
 * {@code BaseExceptionMapper} replaces messages with "Server error &lt;id&gt;".
 */
public class FeedConfigException extends Exception {
    public static final int BAD_REQUEST = 400;
    public static final int NOT_FOUND = 404;
    public static final int CONFLICT = 409;

    private final int status;

    public FeedConfigException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static FeedConfigException badRequest(String message) {
        return new FeedConfigException(BAD_REQUEST, message);
    }

    public static FeedConfigException notFound(String message) {
        return new FeedConfigException(NOT_FOUND, message);
    }

    public static FeedConfigException conflict(String message) {
        return new FeedConfigException(CONFLICT, message);
    }

    public int getStatus() {
        return status;
    }
}
