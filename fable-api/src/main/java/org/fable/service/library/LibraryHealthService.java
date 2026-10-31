package org.fable.service.library;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.websocket.LibraryHealthPayload;
import org.fable.model.websocket.Topic;
import org.fable.repository.LibraryPathRepository;
import org.fable.service.FailureNotificationService;
import org.fable.service.appsettings.AppSettingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
@DependsOnDatabaseInitialization
@Slf4j
public class LibraryHealthService {

    private final LibraryPathRepository libraryPathRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final AppSettingService appSettingService;
    private final FailureNotificationService failureNotificationService;
    private final ConcurrentHashMap<Long, Boolean> previousHealth = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<String>> previousInaccessiblePaths = new ConcurrentHashMap<>();
    private final Set<String> reportedInaccessiblePathKeys = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastCheckEpochMs = new AtomicLong(0);

    public LibraryHealthService(LibraryPathRepository libraryPathRepository,
                                SimpMessagingTemplate messagingTemplate,
                                AppSettingService appSettingService) {
        this(libraryPathRepository, messagingTemplate, appSettingService, null);
    }

    @Autowired
    public LibraryHealthService(LibraryPathRepository libraryPathRepository,
                                SimpMessagingTemplate messagingTemplate,
                                AppSettingService appSettingService,
                                FailureNotificationService failureNotificationService) {
        this.libraryPathRepository = libraryPathRepository;
        this.messagingTemplate = messagingTemplate;
        this.appSettingService = appSettingService;
        this.failureNotificationService = failureNotificationService;
    }

    @PostConstruct
    public void init() {
        var result = evaluateHealth();
        previousHealth.putAll(result.health());
        previousInaccessiblePaths.putAll(result.inaccessiblePaths());
        lastCheckEpochMs.set(System.currentTimeMillis());
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void checkAndBroadcast() {
        long intervalMs = (long) appSettingService.getAppSettings().getLibraryHealthCheckIntervalSeconds() * 1000;
        if (System.currentTimeMillis() - lastCheckEpochMs.get() < intervalMs) {
            return;
        }
        refreshAndBroadcast();
    }

    public void refreshAndBroadcast() {
        lastCheckEpochMs.set(System.currentTimeMillis());
        var result = evaluateHealth();
        if (!result.health().equals(previousHealth) || !result.inaccessiblePaths().equals(previousInaccessiblePaths)) {
            previousHealth.clear();
            previousHealth.putAll(result.health());
            previousInaccessiblePaths.clear();
            previousInaccessiblePaths.putAll(result.inaccessiblePaths());
            messagingTemplate.convertAndSend(
                    Topic.LIBRARY_HEALTH.getPath(),
                    new LibraryHealthPayload(result.health(), result.inaccessiblePaths())
            );
        }
    }

    public Map<Long, Boolean> getCurrentHealth() {
        return Map.copyOf(previousHealth);
    }

    public Map<Long, List<String>> getInaccessiblePaths() {
        return Map.copyOf(previousInaccessiblePaths);
    }

    public static boolean isPathAccessibleOnDisk(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        try {
            var path = Path.of(rawPath);
            return Files.exists(path) && Files.isDirectory(path) && Files.isReadable(path);
        } catch (Exception e) {
            return false;
        }
    }

    private record HealthEvaluation(Map<Long, Boolean> health, Map<Long, List<String>> inaccessiblePaths) {}

    private HealthEvaluation evaluateHealth() {
        List<LibraryPathEntity> allPaths = libraryPathRepository.findAllWithLibrary();
        Map<Long, Boolean> health = new HashMap<>();
        Map<Long, List<String>> inaccessible = new HashMap<>();
        Set<String> activeKeys = new HashSet<>();

        for (LibraryPathEntity lp : allPaths) {
            if (lp == null || lp.getLibrary() == null || lp.getLibrary().getId() == null) {
                continue;
            }
            Long libraryId = lp.getLibrary().getId();
            String libraryName = lp.getLibrary().getName() != null ? lp.getLibrary().getName() : String.valueOf(libraryId);
            String pathStr = lp.getPath();
            String key = libraryId + ":" + pathStr;
            activeKeys.add(key);

            boolean accessible = isPathAccessible(lp);
            health.merge(libraryId, accessible, Boolean::logicalAnd);

            if (!accessible) {
                inaccessible.computeIfAbsent(libraryId, k -> new ArrayList<>()).add(pathStr);
                if (reportedInaccessiblePathKeys.add(key)) {
                    String msg = "Library path is inaccessible in library '" + libraryName + "': " + pathStr
                            + ". Open Manage Directories on this library to review or remove the path.";
                    log.warn(msg);
                    if (failureNotificationService != null) {
                        try {
                            failureNotificationService.reportError(msg);
                        } catch (Exception e) {
                            log.debug("Could not persist library health failure notification: {}", e.getMessage());
                        }
                    }
                }
            } else {
                reportedInaccessiblePathKeys.remove(key);
            }
        }

        reportedInaccessiblePathKeys.retainAll(activeKeys);

        Map<Long, List<String>> immutableInaccessible = new HashMap<>();
        inaccessible.forEach((k, v) -> immutableInaccessible.put(k, List.copyOf(v)));
        return new HealthEvaluation(health, immutableInaccessible);
    }

    private boolean isPathAccessible(LibraryPathEntity libraryPath) {
        return isPathAccessibleOnDisk(libraryPath.getPath());
    }
}
