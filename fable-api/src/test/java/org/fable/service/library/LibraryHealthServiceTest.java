package org.fable.service.library;

import org.fable.model.entity.LibraryEntity;
import org.fable.model.entity.LibraryPathEntity;
import org.fable.model.websocket.LibraryHealthPayload;
import org.fable.model.websocket.Topic;
import org.fable.repository.LibraryPathRepository;
import org.fable.model.dto.settings.AppSettings;
import org.fable.service.appsettings.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LibraryHealthServiceTest {

    @Mock
    private LibraryPathRepository libraryPathRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private AppSettingService appSettingService;

    @Mock
    private org.fable.service.FailureNotificationService failureNotificationService;

    private LibraryHealthService libraryHealthService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        AppSettings settings = new AppSettings();
        settings.setLibraryHealthCheckIntervalSeconds(0);
        when(appSettingService.getAppSettings()).thenReturn(settings);
        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of());
        libraryHealthService = new LibraryHealthService(libraryPathRepository, messagingTemplate, appSettingService, failureNotificationService);
        libraryHealthService.init();
    }

    @Test
    void shouldReportHealthyWhenAllPathsAccessible() {
        Path validPath = tempDir.resolve("books");
        validPath.toFile().mkdirs();

        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                createLibraryPath(1L, validPath.toString())
        ));

        libraryHealthService.checkAndBroadcast();

        Map<Long, Boolean> health = libraryHealthService.getCurrentHealth();
        assertThat(health).containsEntry(1L, true);
    }

    @Test
    void shouldReportUnhealthyWhenPathDoesNotExist() {
        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                createLibraryPath(1L, "/nonexistent/path/that/does/not/exist")
        ));

        libraryHealthService.checkAndBroadcast();

        Map<Long, Boolean> health = libraryHealthService.getCurrentHealth();
        assertThat(health).containsEntry(1L, false);
    }

    @Test
    void shouldReportUnhealthyWhenAnyPathIsDown() {
        Path validPath = tempDir.resolve("books");
        validPath.toFile().mkdirs();

        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                createLibraryPath(1L, validPath.toString()),
                createLibraryPath(1L, "/nonexistent/path")
        ));

        libraryHealthService.checkAndBroadcast();

        Map<Long, Boolean> health = libraryHealthService.getCurrentHealth();
        assertThat(health).containsEntry(1L, false);
    }

    @Test
    void shouldTrackMultipleLibrariesIndependently() {
        Path validPath = tempDir.resolve("lib1");
        validPath.toFile().mkdirs();

        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                createLibraryPath(1L, validPath.toString()),
                createLibraryPath(2L, "/nonexistent/path")
        ));

        libraryHealthService.checkAndBroadcast();

        Map<Long, Boolean> health = libraryHealthService.getCurrentHealth();
        assertThat(health).containsEntry(1L, true);
        assertThat(health).containsEntry(2L, false);
    }

    @Test
    void shouldBroadcastOnlyWhenStateChanges() {
        Path validPath = tempDir.resolve("books");
        validPath.toFile().mkdirs();

        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                createLibraryPath(1L, validPath.toString())
        ));

        libraryHealthService.checkAndBroadcast();
        verify(messagingTemplate, times(1)).convertAndSend(eq(Topic.LIBRARY_HEALTH.getPath()), any(LibraryHealthPayload.class));

        // Same state, should not broadcast again
        libraryHealthService.checkAndBroadcast();
        verify(messagingTemplate, times(1)).convertAndSend(eq(Topic.LIBRARY_HEALTH.getPath()), any(LibraryHealthPayload.class));
    }

    @Test
    void shouldBroadcastWhenStateChangesFromHealthyToUnhealthy() throws Exception {
        Path validPath = tempDir.resolve("books");
        Files.createDirectories(validPath);

        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(
                createLibraryPath(1L, validPath.toString())
        ));

        libraryHealthService.checkAndBroadcast();
        verify(messagingTemplate, times(1)).convertAndSend(eq(Topic.LIBRARY_HEALTH.getPath()), any(LibraryHealthPayload.class));

        // Path disappears
        Files.delete(validPath);
        libraryHealthService.checkAndBroadcast();

        ArgumentCaptor<LibraryHealthPayload> captor = ArgumentCaptor.forClass(LibraryHealthPayload.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq(Topic.LIBRARY_HEALTH.getPath()), captor.capture());
        assertThat(captor.getValue().libraryHealth()).containsEntry(1L, false);
    }

    @Test
    void shouldReturnEmptyMapWhenNoLibraries() {
        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of());
        libraryHealthService.checkAndBroadcast();

        assertThat(libraryHealthService.getCurrentHealth()).isEmpty();
    }

    @Test
    void shouldReportDismissibleFailureNotificationOncePerInaccessiblePathStreak() {
        LibraryPathEntity badPath = createLibraryPath(1L, "/nonexistent/comics/path");
        badPath.getLibrary().setName("Comics");

        when(libraryPathRepository.findAllWithLibrary()).thenReturn(List.of(badPath));

        libraryHealthService.checkAndBroadcast();
        libraryHealthService.checkAndBroadcast();

        assertThat(libraryHealthService.getInaccessiblePaths()).containsEntry(1L, List.of("/nonexistent/comics/path"));
        verify(failureNotificationService, times(1)).reportError(contains("/nonexistent/comics/path"));
    }

    private LibraryPathEntity createLibraryPath(Long libraryId, String path) {
        var library = new LibraryEntity();
        library.setId(libraryId);

        var libraryPath = new LibraryPathEntity();
        libraryPath.setLibrary(library);
        libraryPath.setPath(path);
        return libraryPath;
    }
}
