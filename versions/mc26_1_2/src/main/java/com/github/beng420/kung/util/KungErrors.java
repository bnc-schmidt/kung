package com.github.beng420.kung.util;

/** Error text for logs and chat. */
public final class KungErrors {
    private KungErrors() { }

    /** The message on one line, or the class name when there is none. */
    public static String brief(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
