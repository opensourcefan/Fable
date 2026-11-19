package org.fable.service.network;

import lombok.extern.slf4j.Slf4j;
import org.fable.model.enums.MetadataProvider;
import org.fable.task.TaskCancellationManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Central network resilience coordinator providing:
 * 1. Fast jittered micro-retries for brief (1–5s) network blips.
 * 2. Per-provider lightweight circuit breaking for isolated upstream outages.
 * 3. In-place Network Pause & Auto-Resume for true connectivity drops, with 1s cancellation polling.
 */
@Slf4j
@Service
public class NetworkResilienceService {

    public static final Duration DEFAULT_MAX_OUTAGE_WAIT = Duration.ofMinutes(20);
    public static final Duration SIDECAR_MAX_OUTAGE_WAIT = Duration.ofMinutes(5);

    private static final int MICRO_RETRY_ATTEMPTS = 2;
    private static final long[] MICRO_RETRY_DELAYS_MS = {1_500L, 3_500L};
    private static final long[] PAUSE_BACKOFF_DELAYS_MS = {5_000L, 10_000L, 20_000L, 30_000L, 60_000L};
    private static final int PROBE_TIMEOUT_MS = 3_000;
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_COOLDOWN_MS = 60_000L;

    private static final Map<MetadataProvider, String> PROVIDER_PROBE_HOSTS = Map.of(
            MetadataProvider.Google, "www.googleapis.com",
            MetadataProvider.Hardcover, "api.hardcover.app",
            MetadataProvider.Amazon, "www.amazon.com",
            MetadataProvider.GoodReads, "www.goodreads.com",
            MetadataProvider.Comicvine, "comicvine.gamespot.com",
            MetadataProvider.Douban, "book.douban.com",
            MetadataProvider.Lubimyczytac, "lubimyczytac.pl",
            MetadataProvider.Ranobedb, "ranobedb.org",
            MetadataProvider.Audible, "www.audible.com"
    );

    @FunctionalInterface
    public interface ConnectivityProbe {
        boolean isReachable(String host, int port, int timeoutMs);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final class ProviderCircuitState {
        int consecutiveFailures;
        long openUntilEpochMs;
    }

    private final TaskCancellationManager cancellationManager;
    private final ConnectivityProbe connectivityProbe;
    private final Sleeper sleeper;
    private final LongSupplier clockMs;
    private final long[] microRetryDelaysMs;
    private final long[] pauseBackoffDelaysMs;
    private final ConcurrentHashMap<MetadataProvider, ProviderCircuitState> circuitStates = new ConcurrentHashMap<>();

    @Autowired
    public NetworkResilienceService(TaskCancellationManager cancellationManager) {
        this(cancellationManager, NetworkResilienceService::defaultTcpProbe, Thread::sleep,
                System::currentTimeMillis, MICRO_RETRY_DELAYS_MS, PAUSE_BACKOFF_DELAYS_MS);
    }

    NetworkResilienceService(
            TaskCancellationManager cancellationManager,
            ConnectivityProbe connectivityProbe,
            Sleeper sleeper,
            LongSupplier clockMs,
            long[] microRetryDelaysMs,
            long[] pauseBackoffDelaysMs) {
        this.cancellationManager = cancellationManager;
        this.connectivityProbe = connectivityProbe;
        this.sleeper = sleeper;
        this.clockMs = clockMs;
        this.microRetryDelaysMs = microRetryDelaysMs;
        this.pauseBackoffDelaysMs = pauseBackoffDelaysMs;
    }

    public boolean isCircuitOpen(MetadataProvider provider) {
        if (provider == null) {
            return false;
        }
        ProviderCircuitState state = circuitStates.get(provider);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.openUntilEpochMs > clockMs.getAsLong();
        }
    }

    public void recordProviderSuccess(MetadataProvider provider) {
        if (provider == null) {
            return;
        }
        ProviderCircuitState state = circuitStates.get(provider);
        if (state != null) {
            synchronized (state) {
                state.consecutiveFailures = 0;
                state.openUntilEpochMs = 0L;
            }
        }
    }

    public void recordProviderFailure(MetadataProvider provider) {
        if (provider == null) {
            return;
        }
        ProviderCircuitState state = circuitStates.computeIfAbsent(provider, ignored -> new ProviderCircuitState());
        synchronized (state) {
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= CIRCUIT_FAILURE_THRESHOLD) {
                state.openUntilEpochMs = clockMs.getAsLong() + CIRCUIT_OPEN_COOLDOWN_MS;
                log.warn("Provider {} circuit opened for {}s after {} consecutive upstream failures",
                        provider, CIRCUIT_OPEN_COOLDOWN_MS / 1000, state.consecutiveFailures);
            }
        }
    }

    public void resetAllCircuits() {
        circuitStates.clear();
    }

    /**
     * Executes a per-book provider operation with fast jittered micro-retries followed by
     * an in-place Network Pause & Auto-Resume loop if connectivity drops.
     */
    public <T> T executeWithOutageRecovery(
            String taskId,
            Collection<MetadataProvider> targetProviders,
            Duration maxWaitBudget,
            Consumer<String> pauseStatusCallback,
            Supplier<T> operation) {
        long deadlineMs = clockMs.getAsLong() + maxWaitBudget.toMillis();

        while (true) {
            throwIfCancelled(taskId);

            // Phase 1: Initial attempt + fast micro-retries for brief blips
            TransientProviderException lastTransient = null;
            for (int attempt = 0; attempt <= MICRO_RETRY_ATTEMPTS; attempt++) {
                throwIfCancelled(taskId);
                try {
                    return runInterruptibly(taskId, operation);
                } catch (CancellationException e) {
                    throw e;
                } catch (TransientProviderException e) {
                    lastTransient = e;
                    if (attempt < MICRO_RETRY_ATTEMPTS) {
                        long baseDelay = microRetryDelaysMs[Math.min(attempt, microRetryDelaysMs.length - 1)];
                        long jitteredDelay = applyJitter(baseDelay);
                        log.warn("Transient failure for provider {} (attempt {}/{}): {}. Micro-retrying in {}ms…",
                                e.getProvider(), attempt + 1, MICRO_RETRY_ATTEMPTS + 1, e.getMessage(), jitteredDelay);
                        sleepWithCancellationChecks(taskId, jitteredDelay);
                    }
                }
            }

            // Phase 2: Distinguish isolated provider HTTP 5xx vs real connectivity drop
            boolean connectivityOutage = lastTransient != null
                    && lastTransient.isConnectivityError()
                    && !areAnyProvidersReachable(targetProviders);

            if (!connectivityOutage) {
                if (lastTransient != null && lastTransient.getProvider() != null) {
                    recordProviderFailure(lastTransient.getProvider());
                }
                throw lastTransient;
            }

            // Phase 3: In-place Network Pause loop (does NOT advance caller's book loop!)
            int pauseAttempt = 0;
            while (true) {
                throwIfCancelled(taskId);
                long remainingMs = deadlineMs - clockMs.getAsLong();
                if (remainingMs <= 0) {
                    throw new NetworkOutageExhaustedException(
                            "Network connection remained unavailable past wait budget (" + maxWaitBudget.toMinutes() + " min).",
                            lastTransient);
                }

                long rawDelayMs = pauseBackoffDelaysMs[Math.min(pauseAttempt, pauseBackoffDelaysMs.length - 1)];
                long waitMs = Math.min(applyJitter(rawDelayMs), remainingMs);
                pauseAttempt++;

                long waitSec = Math.max(1, Math.round(waitMs / 1000.0));
                if (pauseStatusCallback != null) {
                    pauseStatusCallback.accept("Network connection lost — paused. Retrying in " + waitSec + "s…");
                }
                log.warn("Task {} paused due to connectivity loss. Probing again in {}s…", taskId, waitSec);

                sleepWithCancellationChecks(taskId, waitMs);

                if (areAnyProvidersReachable(targetProviders)) {
                    log.info("Task {} connectivity restored — resuming operation.", taskId);
                    resetAllCircuits();
                    if (pauseStatusCallback != null) {
                        pauseStatusCallback.accept("Connection restored — resuming…");
                    }
                    break;
                }
            }
        }
    }

    /**
     * Waits for a local HTTP sidecar (e.g. AI Search) to become reachable again during a brief restart/blip.
     * Returns {@code true} if the endpoint recovered before {@code maxWaitBudget}, or {@code false} if exhausted.
     */
    public boolean waitForEndpointRecovery(
            String taskId,
            String baseUrl,
            Duration maxWaitBudget,
            Consumer<String> pauseStatusCallback) {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (Exception e) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);

        long deadlineMs = clockMs.getAsLong() + maxWaitBudget.toMillis();
        int pauseAttempt = 0;

        while (clockMs.getAsLong() < deadlineMs) {
            throwIfCancelled(taskId);
            if (connectivityProbe.isReachable(host, port, PROBE_TIMEOUT_MS)) {
                if (pauseStatusCallback != null) {
                    pauseStatusCallback.accept("AI service connection restored — resuming…");
                }
                return true;
            }
            long remainingMs = deadlineMs - clockMs.getAsLong();
            if (remainingMs <= 0) {
                break;
            }
            long rawDelayMs = pauseBackoffDelaysMs[Math.min(pauseAttempt, pauseBackoffDelaysMs.length - 1)];
            long waitMs = Math.min(applyJitter(rawDelayMs), remainingMs);
            pauseAttempt++;

            long waitSec = Math.max(1, Math.round(waitMs / 1000.0));
            if (pauseStatusCallback != null) {
                pauseStatusCallback.accept("AI service unreachable — paused. Retrying in " + waitSec + "s…");
            }
            sleepWithCancellationChecks(taskId, waitMs);
        }
        return connectivityProbe.isReachable(host, port, PROBE_TIMEOUT_MS);
    }

    public boolean areAnyProvidersReachable(Collection<MetadataProvider> providers) {
        Set<String> hosts = new LinkedHashSet<>();
        if (providers != null) {
            for (MetadataProvider provider : providers) {
                String host = PROVIDER_PROBE_HOSTS.get(provider);
                if (host != null) {
                    hosts.add(host);
                }
            }
        }
        if (hosts.isEmpty()) {
            hosts.add("www.googleapis.com");
            hosts.add("api.hardcover.app");
        }
        for (String host : hosts) {
            if (connectivityProbe.isReachable(host, 443, PROBE_TIMEOUT_MS)) {
                return true;
            }
        }
        return false;
    }

    private <T> T runInterruptibly(String taskId, Supplier<T> operation) {
        if (taskId != null && cancellationManager != null) {
            cancellationManager.setInterruptible(taskId, true);
        }
        try {
            return operation.get();
        } finally {
            if (taskId != null && cancellationManager != null) {
                cancellationManager.setInterruptible(taskId, false);
            }
            if (Thread.currentThread().isInterrupted() && taskId != null && cancellationManager != null && cancellationManager.isTaskCancelled(taskId)) {
                Thread.interrupted(); // clear interrupt flag cleanly before throwing CancellationException
                throw new CancellationException("Task " + taskId + " cancelled");
            }
        }
    }

    private void sleepWithCancellationChecks(String taskId, long totalMs) {
        long remaining = Math.max(0L, totalMs);
        if (taskId != null && cancellationManager != null) {
            cancellationManager.setInterruptible(taskId, true);
        }
        try {
            while (remaining > 0) {
                throwIfCancelled(taskId);
                long chunk = Math.min(remaining, 1_000L);
                try {
                    sleeper.sleep(chunk);
                } catch (InterruptedException e) {
                    if (taskId != null && cancellationManager != null && cancellationManager.isTaskCancelled(taskId)) {
                        throw new CancellationException("Task " + taskId + " cancelled during backoff wait");
                    }
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Task interrupted during backoff wait");
                }
                remaining -= chunk;
            }
        } finally {
            if (taskId != null && cancellationManager != null) {
                cancellationManager.setInterruptible(taskId, false);
            }
        }
    }

    private void throwIfCancelled(String taskId) {
        if (taskId != null && cancellationManager != null && cancellationManager.isTaskCancelled(taskId)) {
            throw new CancellationException("Task " + taskId + " cancelled");
        }
    }

    private static long applyJitter(long baseMs) {
        if (baseMs <= 0) {
            return 0L;
        }
        long jitterRange = Math.max(1L, Math.round(baseMs * 0.15));
        return Math.max(1L, baseMs + ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1));
    }

    private static boolean defaultTcpProbe(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
