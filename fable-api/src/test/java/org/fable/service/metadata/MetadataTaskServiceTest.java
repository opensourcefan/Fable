package org.fable.service.metadata;

import org.fable.config.security.service.AuthenticationService;
import org.fable.mapper.FetchedProposalMapper;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.FetchedProposal;
import org.fable.model.dto.MetadataBatchProgressNotification;
import org.fable.model.dto.request.MetadataRefreshRequest;
import org.fable.model.dto.request.TaskCreateRequest;
import org.fable.model.dto.response.MetadataResumableTaskResponse;
import org.fable.model.dto.response.MetadataTaskLogResponse;
import org.fable.model.dto.response.TaskCancelResponse;
import org.fable.model.dto.response.TaskCreateResponse;
import org.fable.model.dto.response.MetadataTaskDetailsResponse;
import org.fable.model.dto.response.StagingTriageResponse;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.entity.MetadataFetchProposalEntity;
import org.fable.model.entity.TaskHistoryEntity;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.model.enums.TaskType;
import org.fable.repository.BookRepository;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.repository.MetadataFetchProposalRepository;
import org.fable.repository.TaskHistoryRepository;
import org.fable.service.library.LibraryVisibilityService;
import org.fable.service.task.TaskService;
import org.fable.task.TaskStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataTaskServiceTest {

    @Mock
    private MetadataFetchJobRepository metadataFetchTaskRepository;

    @Mock
    private MetadataFetchProposalRepository proposalRepository;

    @Mock
    private TaskHistoryRepository taskHistoryRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private FetchedProposalMapper fetchedProposalMapper;

    @Mock
    private AuthenticationService authenticationService;

    @Mock
    private TaskService taskService;

    @Mock
    private MetadataRefreshService metadataRefreshService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private LibraryVisibilityService libraryVisibilityService;

    @InjectMocks
    private MetadataTaskService service;

    private MetadataFetchJobEntity buildTask(String taskId, MetadataFetchTaskStatus status, List<MetadataFetchProposalEntity> proposals) {
        return MetadataFetchJobEntity.builder()
                .taskId(taskId)
                .status(status)
                .userId(1L)
                .startedAt(Instant.now())
                .totalBooksCount(10)
                .completedBooks(5)
                .requestedBookIds(List.of(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L))
                .proposals(proposals)
                .build();
    }

    private MetadataFetchProposalEntity buildProposal(Long id, MetadataFetchJobEntity job, FetchedMetadataProposalStatus status) {
        return MetadataFetchProposalEntity.builder()
                .proposalId(id)
                .job(job)
                .bookId(100L)
                .status(status)
                .build();
    }

        private BookEntity buildBook(Long id, String title, String fileName) {
        BookEntity book = BookEntity.builder()
            .id(id)
            .metadata(BookMetadataEntity.builder().bookId(id).title(title).build())
            .build();
        book.setBookFiles(List.of(BookFileEntity.builder()
            .id(id)
            .book(book)
            .fileName(fileName)
            .fileSubPath("")
            .isBookFormat(true)
            .build()));
        return book;
        }

    @Nested
    class GetTaskWithProposals {

        @Test
        void returnsEmptyWhenTaskNotFound() {
            when(metadataFetchTaskRepository.findById("missing")).thenReturn(Optional.empty());
            assertThat(service.getTaskWithProposals("missing")).isEmpty();
        }

        @Test
        void returnsResponseWithOnlyFetchedProposals() {
            MetadataFetchJobEntity task = buildTask("t1", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity fetched = buildProposal(1L, task, FetchedMetadataProposalStatus.FETCHED);
            MetadataFetchProposalEntity accepted = buildProposal(2L, task, FetchedMetadataProposalStatus.ACCEPTED);
            MetadataFetchProposalEntity rejected = buildProposal(3L, task, FetchedMetadataProposalStatus.REJECTED);
            task.setProposals(List.of(fetched, accepted, rejected));

            when(metadataFetchTaskRepository.findById("t1")).thenReturn(Optional.of(task));
            when(fetchedProposalMapper.toDto(fetched)).thenReturn(FetchedProposal.builder().proposalId(1L).build());

            Optional<MetadataTaskDetailsResponse> result = service.getTaskWithProposals("t1");

            assertThat(result).isPresent();
            assertThat(result.get().getTask().getProposals()).hasSize(1);
            assertThat(result.get().getTask().getProposals().getFirst().getProposalId()).isEqualTo(1L);
            verify(fetchedProposalMapper, times(1)).toDto(any());
        }

        @Test
        void mapsTaskFieldsCorrectly() {
            MetadataFetchJobEntity task = buildTask("t2", MetadataFetchTaskStatus.IN_PROGRESS, new ArrayList<>());
            task.setCompletedAt(Instant.now());

            when(metadataFetchTaskRepository.findById("t2")).thenReturn(Optional.of(task));

            var result = service.getTaskWithProposals("t2").orElseThrow();
            var dto = result.getTask();

            assertThat(dto.getId()).isEqualTo("t2");
            assertThat(dto.getStatus()).isEqualTo(MetadataFetchTaskStatus.IN_PROGRESS);
            assertThat(dto.getCompleted()).isEqualTo(5);
            assertThat(dto.getTotalBooks()).isEqualTo(10);
            assertThat(dto.getInitiatedBy()).isEqualTo(1L);
        }

        @Test
        void returnsFetchedProposalsOrderedByRequestedBookIds() {
            MetadataFetchJobEntity task = buildTask("ordered", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            task.setRequestedBookIds(List.of(200L, 100L));

            MetadataFetchProposalEntity firstFetched = MetadataFetchProposalEntity.builder()
                    .proposalId(1L)
                    .job(task)
                    .bookId(100L)
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .build();
            MetadataFetchProposalEntity secondFetched = MetadataFetchProposalEntity.builder()
                    .proposalId(2L)
                    .job(task)
                    .bookId(200L)
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .build();
            task.setProposals(List.of(firstFetched, secondFetched));

            when(metadataFetchTaskRepository.findById("ordered")).thenReturn(Optional.of(task));
            when(fetchedProposalMapper.toDto(firstFetched)).thenReturn(FetchedProposal.builder().proposalId(1L).bookId(100L).build());
            when(fetchedProposalMapper.toDto(secondFetched)).thenReturn(FetchedProposal.builder().proposalId(2L).bookId(200L).build());

            MetadataTaskDetailsResponse result = service.getTaskWithProposals("ordered").orElseThrow();

            assertThat(result.getTask().getProposals())
                    .extracting(FetchedProposal::getProposalId)
                    .containsExactly(2L, 1L);
        }
    }

    @Nested
    class GetStagingTriage {

        @Test
        void excludesReleasedReviewBooksAndFiltersTheirTaskEntries() {
            MetadataFetchJobEntity task = buildTask("review-task", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity stagedProposal = MetadataFetchProposalEntity.builder()
                    .proposalId(1L)
                    .job(task)
                    .bookId(100L)
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .build();
            MetadataFetchProposalEntity releasedProposal = MetadataFetchProposalEntity.builder()
                    .proposalId(2L)
                    .job(task)
                    .bookId(200L)
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .build();
            task.setProposals(List.of(stagedProposal, releasedProposal));

            BookEntity stagedReviewBook = buildBook(100L, "Review", "review.epub");
            stagedReviewBook.setStagingCompletedAt(Instant.now());
            BookEntity completedBook = buildBook(300L, "Completed", "completed.epub");
            completedBook.setStagingCompletedAt(Instant.now());
            BookEntity inboxBook = buildBook(400L, "Inbox", "inbox.epub");
            inboxBook.setLastMetadataFetchAt(Instant.now()); // Fetch attempt alone without stagingCompletedAt stays in inbox

            when(proposalRepository.findAllByStatusWithJob(FetchedMetadataProposalStatus.FETCHED))
                    .thenReturn(List.of(stagedProposal, releasedProposal));
            when(bookRepository.findAllStagedWithMetadata())
                    .thenReturn(List.of(stagedReviewBook, completedBook, inboxBook));

            StagingTriageResponse result = service.getStagingTriage();

            assertThat(result.getReviewBookIds()).containsExactly(100L);
            assertThat(result.getReviewCount()).isEqualTo(1);
            assertThat(result.getCompletedBookIds()).containsExactly(300L);
            assertThat(result.getStagingBookIds()).containsExactly(400L);
            assertThat(result.getReviewTasks()).singleElement().satisfies(reviewTask -> {
                assertThat(reviewTask.getTaskId()).isEqualTo("review-task");
                assertThat(reviewTask.getBookIds()).containsExactly(100L);
                assertThat(reviewTask.getProposalCount()).isEqualTo(1);
            });
        }

        @Test
        void clearsReviewCountWhenOnlyReleasedBooksHaveFetchedProposals() {
            MetadataFetchJobEntity task = buildTask("released-task", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity releasedProposal = MetadataFetchProposalEntity.builder()
                    .proposalId(1L)
                    .job(task)
                    .bookId(200L)
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .build();
            task.setProposals(List.of(releasedProposal));

            when(proposalRepository.findAllByStatusWithJob(FetchedMetadataProposalStatus.FETCHED))
                    .thenReturn(List.of(releasedProposal));
            when(bookRepository.findAllStagedWithMetadata()).thenReturn(List.of());

            StagingTriageResponse result = service.getStagingTriage();

            assertThat(result.getReviewCount()).isZero();
            assertThat(result.getReviewBookIds()).isEmpty();
            assertThat(result.getReviewTasks()).isEmpty();
        }

        @Test
        void scopesStagingTriageToAuthenticatedUsersAccessibleLibraries() {
            FableUser restrictedUser = FableUser.builder().id(7L).build();
            when(authenticationService.getAuthenticatedUser()).thenReturn(restrictedUser);
            when(libraryVisibilityService.getAccessibleLibraryIds(restrictedUser)).thenReturn(java.util.Set.of(10L));

            MetadataFetchJobEntity task = buildTask("scoped-task", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity allowedProposal = MetadataFetchProposalEntity.builder()
                    .proposalId(1L).job(task).bookId(100L).status(FetchedMetadataProposalStatus.FETCHED).build();
            MetadataFetchProposalEntity forbiddenProposal = MetadataFetchProposalEntity.builder()
                    .proposalId(2L).job(task).bookId(999L).status(FetchedMetadataProposalStatus.FETCHED).build();
            task.setProposals(List.of(allowedProposal, forbiddenProposal));

            BookEntity allowedBook = buildBook(100L, "Allowed", "allowed.epub");

            when(bookRepository.findAllStagedWithMetadataByLibraryIds(java.util.Set.of(10L)))
                    .thenReturn(List.of(allowedBook));
            when(proposalRepository.findAllByStatusWithJob(FetchedMetadataProposalStatus.FETCHED))
                    .thenReturn(List.of(allowedProposal, forbiddenProposal));
            when(bookRepository.findBookIdsByIdInAndLibraryIdIn(java.util.Set.of(100L, 999L), java.util.Set.of(10L)))
                    .thenReturn(java.util.Set.of(100L));

            StagingTriageResponse result = service.getStagingTriage();

            assertThat(result.getReviewBookIds()).containsExactly(100L);
            assertThat(result.getReviewCount()).isEqualTo(1);
            assertThat(result.getStagingCount()).isZero();
            assertThat(result.getCompletedCount()).isZero();
            verify(bookRepository, never()).findAllStagedWithMetadata();
        }
    }

    @Nested
    class DeleteTaskAndProposals {

        @Test
        void returnsTrueAndDeletesWhenFound() {
            MetadataFetchJobEntity task = buildTask("t1", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            when(metadataFetchTaskRepository.findByTaskIdWithProposals("t1")).thenReturn(Optional.of(task));

            assertThat(service.deleteTaskAndProposals("t1")).isTrue();
            verify(metadataFetchTaskRepository).delete(task);
            verify(metadataFetchTaskRepository).flush();
        }

        @Test
        void returnsFalseWhenNotFound() {
            when(metadataFetchTaskRepository.findByTaskIdWithProposals("missing")).thenReturn(Optional.empty());
            assertThat(service.deleteTaskAndProposals("missing")).isFalse();
            verify(metadataFetchTaskRepository, never()).delete(any());
            verify(metadataFetchTaskRepository, never()).flush();
        }
    }

    @Nested
    class CancelMetadataTask {

        @Test
        void cancelsManualMetadataTask() {
            TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                    .id("task-1")
                    .type(TaskType.REFRESH_METADATA_MANUAL)
                    .build();
            TaskCancelResponse response = TaskCancelResponse.builder()
                    .taskId("task-1")
                    .cancelled(true)
                    .message("Task cancellation requested. The task will stop at the next checkpoint.")
                    .build();

            when(taskHistoryRepository.findById("task-1")).thenReturn(Optional.of(taskHistory));
            when(taskService.cancelTask("task-1")).thenReturn(response);

            Optional<TaskCancelResponse> result = service.cancelMetadataTask("task-1");

            assertThat(result).contains(response);
            verify(taskService).cancelTask("task-1");
        }

        @Test
        void returnsEmptyWhenTaskIsNotMetadataRefresh() {
            TaskHistoryEntity taskHistory = TaskHistoryEntity.builder()
                    .id("task-1")
                    .type(TaskType.CLEANUP_TEMP_METADATA)
                    .build();

            when(taskHistoryRepository.findById("task-1")).thenReturn(Optional.of(taskHistory));

            assertThat(service.cancelMetadataTask("task-1")).isEmpty();
            verify(taskService, never()).cancelTask(any());
        }

        @Test
        void returnsEmptyWhenTaskHistoryMissing() {
            when(taskHistoryRepository.findById("missing")).thenReturn(Optional.empty());

            assertThat(service.cancelMetadataTask("missing")).isEmpty();
            verify(taskService, never()).cancelTask(any());
        }
    }

    @Nested
    class UpdateProposalStatus {

        @Test
        void updatesProposalStatusSuccessfully() {
            Long userId = 42L;
            when(authenticationService.getAuthenticatedUser())
                    .thenReturn(FableUser.builder().id(userId).build());

            MetadataFetchJobEntity job = buildTask("t1", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity proposal = buildProposal(10L, job, FetchedMetadataProposalStatus.FETCHED);
            BookEntity stagedBook = buildBook(100L, "Staged", "staged.epub");
            stagedBook.setStaged(true);

            when(proposalRepository.findById(10L)).thenReturn(Optional.of(proposal));
            when(bookRepository.findById(100L)).thenReturn(Optional.of(stagedBook));

            boolean result = service.updateProposalStatus("t1", 10L, "ACCEPTED");

            assertThat(result).isTrue();
            assertThat(proposal.getStatus()).isEqualTo(FetchedMetadataProposalStatus.ACCEPTED);
            assertThat(proposal.getReviewerUserId()).isEqualTo(userId);
            assertThat(proposal.getReviewedAt()).isNotNull();
            assertThat(stagedBook.getStagingCompletedAt()).isNotNull();
            assertThat(stagedBook.getLastMetadataFetchAt()).isNotNull();
            verify(proposalRepository).save(proposal);
            verify(bookRepository).save(stagedBook);
        }

        @Test
        void returnsFalseForInvalidStatus() {
            when(authenticationService.getAuthenticatedUser())
                    .thenReturn(FableUser.builder().id(1L).build());

            boolean result = service.updateProposalStatus("t1", 10L, "INVALID_STATUS");

            assertThat(result).isFalse();
            verify(proposalRepository, never()).save(any());
        }

        @Test
        void returnsFalseWhenProposalNotFound() {
            when(authenticationService.getAuthenticatedUser())
                    .thenReturn(FableUser.builder().id(1L).build());
            when(proposalRepository.findById(99L)).thenReturn(Optional.empty());

            boolean result = service.updateProposalStatus("t1", 99L, "ACCEPTED");

            assertThat(result).isFalse();
        }

        @Test
        void returnsFalseWhenProposalTaskIdMismatch() {
            when(authenticationService.getAuthenticatedUser())
                    .thenReturn(FableUser.builder().id(1L).build());

            MetadataFetchJobEntity differentJob = buildTask("other-task", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity proposal = buildProposal(10L, differentJob, FetchedMetadataProposalStatus.FETCHED);
            when(proposalRepository.findById(10L)).thenReturn(Optional.of(proposal));

            boolean result = service.updateProposalStatus("t1", 10L, "ACCEPTED");

            assertThat(result).isFalse();
            verify(proposalRepository, never()).save(any());
        }

        @Test
        void returnsFalseWhenProposalJobIsNull() {
            when(authenticationService.getAuthenticatedUser())
                    .thenReturn(FableUser.builder().id(1L).build());

            MetadataFetchProposalEntity proposal = MetadataFetchProposalEntity.builder()
                    .proposalId(10L)
                    .job(null)
                    .status(FetchedMetadataProposalStatus.FETCHED)
                    .build();
            when(proposalRepository.findById(10L)).thenReturn(Optional.of(proposal));

            boolean result = service.updateProposalStatus("t1", 10L, "ACCEPTED");

            assertThat(result).isFalse();
        }

        @Test
        void handlesLowercaseStatusString() {
            when(authenticationService.getAuthenticatedUser())
                    .thenReturn(FableUser.builder().id(1L).build());

            MetadataFetchJobEntity job = buildTask("t1", MetadataFetchTaskStatus.COMPLETED, new ArrayList<>());
            MetadataFetchProposalEntity proposal = buildProposal(10L, job, FetchedMetadataProposalStatus.FETCHED);
            when(proposalRepository.findById(10L)).thenReturn(Optional.of(proposal));

            boolean result = service.updateProposalStatus("t1", 10L, "rejected");

            assertThat(result).isTrue();
            assertThat(proposal.getStatus()).isEqualTo(FetchedMetadataProposalStatus.REJECTED);
        }
    }

    @Nested
    class GetActiveTasks {

        @Test
        void includesCancelledTasksForResume() {
            MetadataFetchJobEntity inProgress = buildTask("ip", MetadataFetchTaskStatus.IN_PROGRESS, new ArrayList<>());
            MetadataFetchJobEntity cancelled = buildTask("ca", MetadataFetchTaskStatus.CANCELLED, new ArrayList<>());
            MetadataFetchJobEntity completed = buildTask("co", MetadataFetchTaskStatus.COMPLETED, List.of(
                    MetadataFetchProposalEntity.builder().proposalId(1L).status(FetchedMetadataProposalStatus.FETCHED).build()
            ));

            when(metadataFetchTaskRepository.findAllWithProposals())
                    .thenReturn(List.of(inProgress, cancelled, completed));
            when(taskService.isTaskRunning("ip")).thenReturn(true);

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(3);
            assertThat(result).extracting(MetadataBatchProgressNotification::getTaskId)
                .containsExactlyInAnyOrder("ip", "ca", "co");
        }

        @Test
        void inProgressTaskUsesPersistedStatusMessageAndIsNotReview() {
            MetadataFetchJobEntity task = MetadataFetchJobEntity.builder()
                    .taskId("ip")
                    .status(MetadataFetchTaskStatus.IN_PROGRESS)
                    .statusMessage("Waiting for ComicVine rate limit reset. Time left: 12m 10s. Resets at 6:45:00 PM. Processed 66 of 100 books.")
                    .totalBooksCount(100)
                    .completedBooks(66)
                    .startedAt(Instant.now())
                    .proposals(new ArrayList<>())
                    .build();

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));
            when(taskService.isTaskRunning("ip")).thenReturn(true);

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            var notification = result.getFirst();
            assertThat(notification.getCompleted()).isEqualTo(66);
            assertThat(notification.getTotal()).isEqualTo(100);
            assertThat(notification.getStatus()).isEqualTo("IN_PROGRESS");
            assertThat(notification.getMessage()).contains("Waiting for ComicVine rate limit reset");
            assertThat(notification.isReview()).isFalse();
        }

        @Test
        void completedTaskCountsAcceptedAsCompleted() {
            MetadataFetchProposalEntity accepted1 = MetadataFetchProposalEntity.builder()
                    .proposalId(1L).status(FetchedMetadataProposalStatus.ACCEPTED).build();
            MetadataFetchProposalEntity accepted2 = MetadataFetchProposalEntity.builder()
                    .proposalId(2L).status(FetchedMetadataProposalStatus.ACCEPTED).build();
            MetadataFetchProposalEntity fetched = MetadataFetchProposalEntity.builder()
                    .proposalId(3L).status(FetchedMetadataProposalStatus.FETCHED).build();
            MetadataFetchProposalEntity rejected = MetadataFetchProposalEntity.builder()
                    .proposalId(4L).status(FetchedMetadataProposalStatus.REJECTED).build();

            MetadataFetchJobEntity task = buildTask("co", MetadataFetchTaskStatus.COMPLETED,
                    List.of(accepted1, accepted2, fetched, rejected));

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            var notification = result.getFirst();
            assertThat(notification.getCompleted()).isEqualTo(2);
            assertThat(notification.getTotal()).isEqualTo(3);
            assertThat(notification.getStatus()).isEqualTo("COMPLETED");
            assertThat(notification.getMessage()).contains("1 books need review");
        }

        @Test
        void errorTaskUsesTotalBooksCountAndCompletedBooks() {
            MetadataFetchProposalEntity fetched = MetadataFetchProposalEntity.builder()
                    .proposalId(1L).status(FetchedMetadataProposalStatus.FETCHED).build();

            MetadataFetchJobEntity task = MetadataFetchJobEntity.builder()
                    .taskId("err")
                    .status(MetadataFetchTaskStatus.ERROR)
                    .totalBooksCount(20)
                    .completedBooks(15)
                    .startedAt(Instant.now())
                    .proposals(List.of(fetched))
                    .build();

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            var notification = result.getFirst();
            assertThat(notification.getCompleted()).isEqualTo(15);
            assertThat(notification.getTotal()).isEqualTo(20);
            assertThat(notification.getStatus()).isEqualTo("ERROR");
            assertThat(notification.getMessage()).contains("failed");
        }

        @Test
        void errorTaskFallsBackToRemainingSizeWhenTotalBooksCountNull() {
            MetadataFetchProposalEntity fetched1 = MetadataFetchProposalEntity.builder()
                    .proposalId(1L).status(FetchedMetadataProposalStatus.FETCHED).build();
            MetadataFetchProposalEntity fetched2 = MetadataFetchProposalEntity.builder()
                    .proposalId(2L).status(FetchedMetadataProposalStatus.FETCHED).build();

            MetadataFetchJobEntity task = MetadataFetchJobEntity.builder()
                    .taskId("err")
                    .status(MetadataFetchTaskStatus.ERROR)
                    .totalBooksCount(null)
                    .completedBooks(null)
                    .startedAt(Instant.now())
                    .proposals(List.of(fetched1, fetched2))
                    .build();

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getTotal()).isEqualTo(2);
            assertThat(result.getFirst().getCompleted()).isEqualTo(0);
        }

        @Test
        void keepsTasksWithZeroTotalVisible() {
            MetadataFetchJobEntity task = buildTask("empty", MetadataFetchTaskStatus.COMPLETED, List.of(
                    MetadataFetchProposalEntity.builder().proposalId(1L).status(FetchedMetadataProposalStatus.REJECTED).build()
            ));

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getTaskId()).isEqualTo("empty");
            assertThat(result.getFirst().getTotal()).isEqualTo(10);
            assertThat(result.getFirst().getCompleted()).isEqualTo(5);
            assertThat(result.getFirst().getStatus()).isEqualTo("COMPLETED");
        }

        @Test
        void completedNotificationsHaveIsReviewTrue() {
            MetadataFetchProposalEntity fetched = MetadataFetchProposalEntity.builder()
                    .proposalId(1L).status(FetchedMetadataProposalStatus.FETCHED).build();

            MetadataFetchJobEntity task = buildTask("t1", MetadataFetchTaskStatus.COMPLETED, List.of(fetched));

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).allMatch(MetadataBatchProgressNotification::isReview);
        }

        @Test
        void errorTaskIsMarkedResumableWhenPendingBooksRemain() {
            MetadataFetchJobEntity task = buildTask("err", MetadataFetchTaskStatus.ERROR, new ArrayList<>());
            task.setCompletedBookIds(List.of(100L, 101L, 102L));

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().isResumable()).isTrue();
            assertThat(result.getFirst().getPendingCount()).isEqualTo(7);
        }

        @Test
        void staleInProgressTaskBecomesResumableError() {
            MetadataFetchJobEntity task = buildTask("stale", MetadataFetchTaskStatus.IN_PROGRESS, new ArrayList<>());
            task.setCompletedBookIds(List.of(100L, 101L));

            TaskHistoryEntity history = TaskHistoryEntity.builder()
                    .id("stale")
                    .type(TaskType.REFRESH_METADATA_MANUAL)
                    .status(TaskStatus.IN_PROGRESS)
                    .createdAt(LocalDateTime.now().minusMinutes(5))
                    .build();

            when(metadataFetchTaskRepository.findAllWithProposals()).thenReturn(List.of(task));
            when(taskService.isTaskRunning("stale")).thenReturn(false);
            when(taskHistoryRepository.findById("stale")).thenReturn(Optional.of(history));

            List<MetadataBatchProgressNotification> result = service.getActiveTasks();

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getStatus()).isEqualTo("ERROR");
            assertThat(result.getFirst().isResumable()).isTrue();
            verify(metadataFetchTaskRepository).save(task);
            verify(taskHistoryRepository).save(history);
        }
    }

    @Nested
    class ResumeMetadataTask {

        @Test
        void returnsLatestResumableTaskForCurrentUser() {
            FableUser user = FableUser.builder().id(1L).build();
            MetadataFetchJobEntity task = buildTask("resume-me", MetadataFetchTaskStatus.ERROR, new ArrayList<>());
            task.setCompletedBookIds(List.of(100L, 101L, 102L, 103L));

            when(authenticationService.getAuthenticatedUser()).thenReturn(user);
            when(metadataFetchTaskRepository.findAllWithProposalsByUserIdOrderByStartedAtDesc(1L)).thenReturn(List.of(task));

            Optional<MetadataResumableTaskResponse> result = service.getLatestResumableTask();

            assertThat(result).isPresent();
            assertThat(result.get().getTaskId()).isEqualTo("resume-me");
            assertThat(result.get().getPendingBooksCount()).isEqualTo(6);
        }

        @Test
        void resumesPendingBooksFromFailedTask() {
            MetadataFetchJobEntity task = buildTask("resume-me", MetadataFetchTaskStatus.ERROR, new ArrayList<>());
            task.setCompletedBookIds(List.of(100L, 101L, 102L));

            TaskHistoryEntity history = TaskHistoryEntity.builder()
                    .id("resume-me")
                    .type(TaskType.REFRESH_METADATA_MANUAL)
                    .taskOptions(Map.of())
                    .build();

            MetadataRefreshRequest originalRequest = MetadataRefreshRequest.builder()
                    .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                    .bookIds(new java.util.LinkedHashSet<>(List.of(100L, 101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L, 109L)))
                    .build();

            TaskCreateResponse resumedResponse = TaskCreateResponse.builder()
                    .taskId("new-task")
                    .taskType(TaskType.REFRESH_METADATA_MANUAL)
                    .status(TaskStatus.ACCEPTED)
                    .build();

            when(metadataFetchTaskRepository.findByTaskIdWithProposals("resume-me")).thenReturn(Optional.of(task));
            when(taskHistoryRepository.findById("resume-me")).thenReturn(Optional.of(history));
            when(objectMapper.convertValue(history.getTaskOptions(), MetadataRefreshRequest.class)).thenReturn(originalRequest);
            when(taskService.runAsUser(any(TaskCreateRequest.class))).thenReturn(resumedResponse);

            Optional<TaskCreateResponse> result = service.resumeMetadataTask("resume-me");

            assertThat(result).contains(resumedResponse);
            verify(taskService).runAsUser(argThat(request -> {
                MetadataRefreshRequest options = request.getOptionsAs(MetadataRefreshRequest.class);
                return options.getRefreshType() == MetadataRefreshRequest.RefreshType.BOOKS
                        && options.getBookIds().containsAll(List.of(103L, 104L, 105L, 106L, 107L, 108L, 109L))
                        && options.getBookIds().size() == 7;
            }));
            assertThat(task.getStatusMessage()).contains("new-task");
        }

        @Test
        void shiftsComicVineIssueRangeWhenResumingContiguousSuffix() {
            MetadataFetchJobEntity task = buildTask("resume-comics", MetadataFetchTaskStatus.ERROR, new ArrayList<>());
            task.setRequestedBookIds(List.of(10L, 20L, 30L, 40L, 50L));
            task.setCompletedBookIds(List.of(10L, 20L));

            TaskHistoryEntity history = TaskHistoryEntity.builder()
                    .id("resume-comics")
                    .type(TaskType.REFRESH_METADATA_MANUAL)
                    .taskOptions(Map.of())
                    .build();

            org.fable.model.dto.request.MetadataRefreshOptions originalOptions =
                    org.fable.model.dto.request.MetadataRefreshOptions.builder()
                            .issueRange("43-47")
                            .build();
            MetadataRefreshRequest originalRequest = MetadataRefreshRequest.builder()
                    .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                    .bookIds(new java.util.LinkedHashSet<>(List.of(10L, 20L, 30L, 40L, 50L)))
                    .refreshOptions(originalOptions)
                    .build();

            TaskCreateResponse resumedResponse = TaskCreateResponse.builder()
                    .taskId("resumed-comics")
                    .taskType(TaskType.REFRESH_METADATA_MANUAL)
                    .status(TaskStatus.ACCEPTED)
                    .build();

            when(metadataFetchTaskRepository.findByTaskIdWithProposals("resume-comics")).thenReturn(Optional.of(task));
            when(taskHistoryRepository.findById("resume-comics")).thenReturn(Optional.of(history));
            when(objectMapper.convertValue(history.getTaskOptions(), MetadataRefreshRequest.class)).thenReturn(originalRequest);
            when(taskService.runAsUser(any(TaskCreateRequest.class))).thenReturn(resumedResponse);

            Optional<TaskCreateResponse> result = service.resumeMetadataTask("resume-comics");

            assertThat(result).contains(resumedResponse);
            verify(taskService).runAsUser(argThat(request -> {
                MetadataRefreshRequest options = request.getOptionsAs(MetadataRefreshRequest.class);
                return "45-47".equals(options.getRefreshOptions().getIssueRange())
                        && options.getBookIds().equals(new java.util.LinkedHashSet<>(List.of(30L, 40L, 50L)));
            }));
            // Original options object must remain unmutated
            assertThat(originalOptions.getIssueRange()).isEqualTo("43-47");
        }

        @Test
        void resumesIsbnDiscoveryTaskWithPendingBooks() {
            MetadataFetchJobEntity task = buildTask("resume-isbn", MetadataFetchTaskStatus.ERROR, new ArrayList<>());
            task.setRequestedBookIds(List.of(1L, 2L, 3L));
            task.setCompletedBookIds(List.of(1L));

            TaskHistoryEntity history = TaskHistoryEntity.builder()
                    .id("resume-isbn")
                    .type(TaskType.ISBN_DISCOVERY)
                    .taskOptions(Map.of())
                    .build();

            org.fable.model.dto.request.IsbnDiscoveryRequest originalIsbnRequest =
                    org.fable.model.dto.request.IsbnDiscoveryRequest.builder()
                            .bookIds(new java.util.LinkedHashSet<>(List.of(1L, 2L, 3L)))
                            .providers(List.of(org.fable.model.enums.MetadataProvider.Google))
                            .build();

            TaskCreateResponse resumedResponse = TaskCreateResponse.builder()
                    .taskId("resumed-isbn")
                    .taskType(TaskType.ISBN_DISCOVERY)
                    .status(TaskStatus.ACCEPTED)
                    .build();

            when(metadataFetchTaskRepository.findByTaskIdWithProposals("resume-isbn")).thenReturn(Optional.of(task));
            when(taskHistoryRepository.findById("resume-isbn")).thenReturn(Optional.of(history));
            when(objectMapper.convertValue(history.getTaskOptions(), org.fable.model.dto.request.IsbnDiscoveryRequest.class))
                    .thenReturn(originalIsbnRequest);
            when(taskService.runAsUser(any(TaskCreateRequest.class))).thenReturn(resumedResponse);

            Optional<TaskCreateResponse> result = service.resumeMetadataTask("resume-isbn");

            assertThat(result).contains(resumedResponse);
            verify(taskService).runAsUser(argThat(request -> {
                org.fable.model.dto.request.IsbnDiscoveryRequest options =
                        request.getOptionsAs(org.fable.model.dto.request.IsbnDiscoveryRequest.class);
                return request.getTaskType() == TaskType.ISBN_DISCOVERY
                        && options.getBookIds().equals(new java.util.LinkedHashSet<>(List.of(2L, 3L)))
                        && options.getProviders().equals(List.of(org.fable.model.enums.MetadataProvider.Google));
            }));
        }
    }

    @Nested
    class GetTaskLog {

        @Test
        void returnsFetchedAndRemainingBooksWithTitlesAndFileNames() {
            MetadataFetchJobEntity task = buildTask("log-task", MetadataFetchTaskStatus.IN_PROGRESS, new ArrayList<>());
            task.setStatusMessage("Paused for ComicVine rate limit reset. Resets at 6:45:00 PM. Processed 2 of 10 books.");
            task.setRequestedBookIds(List.of(100L, 101L, 102L));
            task.setCompletedBookIds(List.of(100L, 102L));

            when(metadataFetchTaskRepository.findByTaskIdWithProposals("log-task")).thenReturn(Optional.of(task));
            when(taskService.isTaskRunning("log-task")).thenReturn(true);
            when(bookRepository.findAllWithMetadataByIds(any())).thenReturn(List.of(
                    buildBook(100L, "Fetched One", "fetched-one.cbz"),
                    buildBook(101L, "Remaining One", "remaining-one.cbz"),
                    buildBook(102L, "Fetched Two", "fetched-two.cbz")
            ));

            MetadataTaskLogResponse result = service.getTaskLog("log-task").orElseThrow();

            assertThat(result.getStatus()).isEqualTo(MetadataFetchTaskStatus.IN_PROGRESS);
            assertThat(result.getCompleted()).isEqualTo(2);
            assertThat(result.getPending()).isEqualTo(1);
            assertThat(result.getFetchedBooks()).extracting(book -> book.getTitle() + ":" + book.getFileName())
                    .containsExactly("Fetched One:fetched-one.cbz", "Fetched Two:fetched-two.cbz");
            assertThat(result.getRemainingBooks()).extracting(book -> book.getTitle() + ":" + book.getFileName())
                    .containsExactly("Remaining One:remaining-one.cbz");
        }
    }
}
