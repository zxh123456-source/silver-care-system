package com.shanzhu.beadhouse.service.common;

/**
 * A user-facing document extraction failure. The controller converts this to
 * the existing Result envelope instead of importing incomplete text.
 */
public class PolicyDocumentExtractionException extends RuntimeException {
    private final int code;

    public PolicyDocumentExtractionException(int code, String message) {
        super(message);
        this.code = code;
    }

    public PolicyDocumentExtractionException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
