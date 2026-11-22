package com.blackbox.stream.collectionpilot;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

public final class FailureTest {
    public static void main(String[] args) {
        check("Sign in to confirm you're not a bot", "signin", false);
        check("ERROR: Video unavailable", "unavailable", false);
        check("HTTP Error 403: Forbidden", "refused", false);
        check("HTTP Error 429: Too Many Requests", "limited", true);
        check("Connection timed out", "network", true);
        check("No space left on device", "storage", true);
        check("Unable to extract audio", "helper", true);
        System.out.println("PASS provider failure categories and retry policy (simulated messages)");
    }

    private static void check(String message, String expected, boolean canRetry) {
        String actual = DownloadFailure.classify(new InvocationTargetException(new IOException(message)));
        if (!expected.equals(actual) || DownloadFailure.retryable(actual) != canRetry) {
            throw new AssertionError("Expected " + expected + "; got " + actual);
        }
    }
}
