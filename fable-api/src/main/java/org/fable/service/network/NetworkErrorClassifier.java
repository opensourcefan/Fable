package org.fable.service.network;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * Classifies exceptions across JDK HttpClient, Spring RestClient/RestTemplate,
 * Jsoup, and URLConnection into transport-level connectivity faults vs.
 * upstream HTTP status failures vs. non-transient errors.
 */
public final class NetworkErrorClassifier {

    private NetworkErrorClassifier() {
    }

    /**
     * Returns {@code true} when {@code throwable} (or any cause in its chain) represents
     * a transport/DNS/socket/TLS/timeout connectivity fault.
     * Always returns {@code false} for cancellation and thread interruption.
     */
    public static boolean isConnectivityError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (containsCancellationOrInterrupt(throwable)) {
            return false;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (current instanceof TransientProviderException tpe) {
                return tpe.isConnectivityError();
            }
            if (current instanceof UnknownHostException
                    || current instanceof UnresolvedAddressException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof SocketException
                    || current instanceof EOFException
                    || current instanceof SSLException
                    || current instanceof ClosedChannelException
                    || current instanceof ResourceAccessException) {
                return true;
            }
            if (current instanceof IOException io && isConnectivityIoMessage(io.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Returns {@code true} for transient upstream HTTP status codes that may succeed on retry
     * (408 Request Timeout, 502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout).
     */
    public static boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 408 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * Returns {@code true} when an exception represents either a connectivity fault
     * or a transient upstream HTTP status failure (excluding cancellation/interrupt).
     */
    public static boolean isTransientFailure(Throwable throwable) {
        if (throwable == null || containsCancellationOrInterrupt(throwable)) {
            return false;
        }
        if (isConnectivityError(throwable)) {
            return true;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (current instanceof TransientProviderException) {
                return true;
            }
            if (current instanceof RestClientResponseException rcre
                    && isTransientHttpStatus(rcre.getStatusCode().value())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static boolean containsCancellationOrInterrupt(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isConnectivityIoMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("goaway")
                || lower.contains("connection reset")
                || lower.contains("broken pipe")
                || lower.contains("network is unreachable")
                || lower.contains("host is unreachable")
                || lower.contains("connection refused")
                || lower.contains("timed out")
                || lower.contains("premature eof");
    }
}

