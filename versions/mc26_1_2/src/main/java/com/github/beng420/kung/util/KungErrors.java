package com.github.beng420.kung.util;

/** Error text for logs and chat, and the interrupt rule for swallowed exceptions. */
public final class KungErrors {
    private KungErrors() { }

    /** The message on one line, or the class name when there is none. */
    public static String brief(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
            ? throwable.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }

    /** Call where a catch block swallows the exception: a swallowed {@link InterruptedException} must keep the thread's interrupt flag. */
    public static void restoreInterrupt(Throwable throwable) {
        if (throwable instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
