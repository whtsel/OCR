package com.blackbox.stream.collectionpilot;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/** No raw provider messages or stream URLs go to the UI. */
final class DownloadFailure {
    static boolean retryable(String reason) {
        return !reason.equals("signin") && !reason.equals("refused") && !reason.equals("unavailable");
    }

    static String classify(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        String message = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("http error 429") || message.contains("too many requests")) return "limited";
        if (message.contains("sign in") || message.contains("sign-in") || message.contains("not a bot") || message.contains("login_required")) return "signin";
        if (message.contains("video unavailable") || message.contains("private video") || message.contains("has been removed")) return "unavailable";
        if (message.contains("forbidden") || message.contains("http error 403")) return "refused";
        if (message.contains("connect") || message.contains("timed out") || message.contains("unreachable")
                || message.contains("no address") || message.contains("resolve host")) return "network";
        if (message.contains("storage") || message.contains("save to the collection") || message.contains("no space left")) return "storage";
        return "helper";
    }
}
