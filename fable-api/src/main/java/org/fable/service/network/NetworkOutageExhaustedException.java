package org.fable.service.network;

/**
 * Thrown when a running task paused for a network outage exceeds its configured
 * wait budget without connectivity recovering. Callers should stop the loop cleanly
 * without advancing past the current item so the job remains resumable.
 */
public class NetworkOutageExhaustedException extends RuntimeException {

    public NetworkOutageExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}

