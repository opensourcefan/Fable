package org.fable.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fable.config.security.service.AuthenticationService;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.MetadataBatchProgressNotification;
import org.fable.model.dto.request.IsbnDiscoveryRequest;
import org.fable.model.dto.request.TaskCreateRequest;
import org.fable.model.dto.response.TaskCreateResponse;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.entity.MetadataFetchProposalEntity;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.model.enums.TaskType;
import org.fable.model.websocket.Topic;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.service.NotificationService;
import org.fable.service.book.BookUpdateService;
import org.fable.service.metadata.IsbnMetadataFillService;
import org.fable.service.metadata.MetadataTaskContext;
import org.fable.service.network.NetworkOutageExhaustedException;
import org.fable.service.network.NetworkResilienceService;
import org.fable.task.TaskCancellationManager;
import org.fable.task.TaskStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.fable.exception.ApiError.PERMISSION_DENIED;
import static org.fable.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

/**
 * Two-phase ISBN batch skeleton:
 * discover when needed, then multi-pass fill per book via {@link IsbnMetadataFillService}.
 * Outcomes with reviewable metadata become proposals (same review dialog as metadata refresh).
 * Hard failures (no ISBN found) are reported in the task message without a fake review queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IsbnDiscoveryTask implements Task {

    private final IsbnMetadataFillService isbnMetadataFillService;
    private final MetadataFetchJobRepository metadataFetchJobRepository;
    private final BookUpdateService bookUpdateService;
    private final NotificationService notificationService;
    private final AuthenticationService authenticationService;
    private final TaskCancellationManager cancellationManager;
    private final ObjectMapper objectMapper;
    private final NetworkResilienceService networkResilienceService;

    @Override
    public void validatePermissions(FableUser user, TaskCreateRequest request) {
        IsbnDiscoveryRequest options = request.getOptionsAs(IsbnDiscoveryRequest.class);
        if (options != null && options.getBookIds() != null && options.getBookIds().size() > 1
                && !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        IsbnDiscoveryRequest options = request.getOptionsAs(IsbnDiscoveryRequest.class);
        Set<Long> bookIds = options != null && options.getBookIds() != null ? options.getBookIds() : Set.of();
        String taskId = request.getTaskId();

        FableUser user = authenticationService.getAuthenticatedUser();
        Long userId = user != null ? user.getId() : null;

        MetadataFetchJobEntity job = MetadataFetchJobEntity.builder()
                .taskId(taskId)
                .userId(userId)
                .status(MetadataFetchTaskStatus.IN_PROGRESS)
                .startedAt(Instant.now())
                .totalBooksCount(bookIds.size())
                .completedBooks(0)
                .requestedBookIds(new ArrayList<>(bookIds))
                .build();
        metadataFetchJobRepository.save(job);

        int total = bookIds.size();
        sendProgress(taskId, 0, total,
                total == 1
                        ? "Starting ISBN discovery…"
                        : "Starting ISBN discovery for " + total + " book(s)…",
                MetadataFetchTaskStatus.IN_PROGRESS,
                false,
                MetadataBatchProgressNotification.PHASE_ISBN_DISCOVERY);

        int completed = 0;
        int reviewCount = 0;
        int failedCount = 0;
        for (Long bookId : bookIds) {
            if (cancellationManager.isTaskCancelled(taskId)) {
                job.setStatus(MetadataFetchTaskStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                job.setStatusMessage("Task cancelled by user");
                metadataFetchJobRepository.save(job);
                sendProgress(taskId, completed, total, "Cancelled", MetadataFetchTaskStatus.CANCELLED, reviewCount > 0, null);
                cancellationManager.clearCancellation(taskId);
                return TaskCreateResponse.builder()
                        .taskType(TaskType.ISBN_DISCOVERY)
                        .taskId(taskId)
                        .status(TaskStatus.CANCELLED)
                        .build();
            }

            String inProgressMessage = "ISBN discovery in progress — book "
                    + (completed + 1) + " of " + total + "…";
            job.setStatusMessage(inProgressMessage);
            metadataFetchJobRepository.save(job);

            MetadataTaskContext.set(taskId, completed, total, reviewCount > 0);
            try {
                final int currentCompleted = completed;
                final int currentReviewCount = reviewCount;
                var requestedProviders = options != null ? options.getProviders() : null;
                IsbnMetadataFillService.IsbnFillOutcome outcome = networkResilienceService != null
                        ? networkResilienceService.executeWithOutageRecovery(
                                taskId,
                                requestedProviders,
                                NetworkResilienceService.DEFAULT_MAX_OUTAGE_WAIT,
                                pauseMsg -> {
                                    job.setStatusMessage(pauseMsg);
                                    metadataFetchJobRepository.save(job);
                                    sendProgress(taskId, currentCompleted, total, pauseMsg,
                                            MetadataFetchTaskStatus.IN_PROGRESS, currentReviewCount > 0,
                                            MetadataBatchProgressNotification.PHASE_ISBN_DISCOVERY);
                                },
                                () -> isbnMetadataFillService.fillBookFromIsbn(bookId, requestedProviders))
                        : isbnMetadataFillService.fillBookFromIsbn(bookId, requestedProviders);
                if (outcome.status() == IsbnMetadataFillService.IsbnFillOutcome.Status.NEEDS_REVIEW
                        && outcome.metadata() != null) {
                    MetadataFetchProposalEntity proposal = MetadataFetchProposalEntity.builder()
                            .job(job)
                            .bookId(bookId)
                            .metadataJson(objectMapper.writeValueAsString(outcome.metadata()))
                            .status(FetchedMetadataProposalStatus.FETCHED)
                            .fetchedAt(Instant.now())
                            .build();
                    job.getProposals().add(proposal);
                    reviewCount++;
                    stageBookForReview(bookId);
                } else if (outcome.status() == IsbnMetadataFillService.IsbnFillOutcome.Status.ERROR
                        || outcome.status() == IsbnMetadataFillService.IsbnFillOutcome.Status.DISABLED) {
                    failedCount++;
                    log.info("ISBN discovery did not fill book {}: {}", bookId, outcome.message());
                }
                completed++;
                markBookCompleted(job, bookId);
                job.setCompletedBooks(completed);
                job.setStatusMessage(buildProgressMessage(bookId, outcome));
                metadataFetchJobRepository.save(job);
                String postPhase = outcome.status() == IsbnMetadataFillService.IsbnFillOutcome.Status.ERROR
                        || outcome.status() == IsbnMetadataFillService.IsbnFillOutcome.Status.DISABLED
                        ? MetadataBatchProgressNotification.PHASE_ISBN_FAILED
                        : MetadataBatchProgressNotification.PHASE_METADATA_FETCH;
                sendProgress(taskId, completed, total,
                        job.getStatusMessage(),
                        MetadataFetchTaskStatus.IN_PROGRESS,
                        reviewCount > 0,
                        postPhase);
            } catch (CancellationException e) {
                job.setStatus(MetadataFetchTaskStatus.CANCELLED);
                job.setCompletedAt(Instant.now());
                job.setStatusMessage("Task cancelled by user");
                metadataFetchJobRepository.save(job);
                sendProgress(taskId, completed, total, "Cancelled", MetadataFetchTaskStatus.CANCELLED, reviewCount > 0, null);
                cancellationManager.clearCancellation(taskId);
                return TaskCreateResponse.builder()
                        .taskType(TaskType.ISBN_DISCOVERY)
                        .taskId(taskId)
                        .status(TaskStatus.CANCELLED)
                        .build();
            } catch (NetworkOutageExhaustedException e) {
                int remainingBooks = Math.max(1, total - completed);
                String outageMsg = String.format(
                        "Paused for network outage and timed out waiting for connection. %d of %d books remain and can be resumed.",
                        remainingBooks, total);
                log.warn("ISBN discovery task {} stopping cleanly after network outage timeout ({} remaining)", taskId, remainingBooks);
                job.setStatus(MetadataFetchTaskStatus.ERROR);
                job.setCompletedAt(Instant.now());
                job.setCompletedBooks(completed);
                job.setStatusMessage(outageMsg);
                metadataFetchJobRepository.save(job);
                sendProgress(taskId, completed, total, outageMsg, MetadataFetchTaskStatus.ERROR, reviewCount > 0, null);
                return TaskCreateResponse.builder()
                        .taskType(TaskType.ISBN_DISCOVERY)
                        .taskId(taskId)
                        .status(TaskStatus.FAILED)
                        .build();
            } catch (Exception e) {
                log.error("ISBN discovery failed for book {}", bookId, e);
                completed++;
                failedCount++;
                job.setCompletedBooks(completed);
                metadataFetchJobRepository.save(job);
                sendProgress(taskId, completed, total,
                        "Failed book " + bookId + ": " + e.getMessage(),
                        MetadataFetchTaskStatus.IN_PROGRESS,
                        reviewCount > 0,
                        MetadataBatchProgressNotification.PHASE_ISBN_FAILED);
            } finally {
                MetadataTaskContext.clear();
            }
        }

        boolean allFailed = total > 0 && failedCount == total;
        MetadataFetchTaskStatus finalJobStatus = allFailed ? MetadataFetchTaskStatus.ERROR : MetadataFetchTaskStatus.COMPLETED;
        job.setStatus(finalJobStatus);
        job.setCompletedAt(Instant.now());
        job.setCompletedBooks(completed);
        String finalMessage = buildFinalMessage(reviewCount, failedCount, completed);
        job.setStatusMessage(finalMessage);
        metadataFetchJobRepository.save(job);

        // Mirror MetadataTaskService.getActiveTasks semantics so the Review button stays visible:
        // completed = accepted count (0), total = pending FETCHED proposals.
        if (reviewCount > 0) {
            sendProgress(taskId, 0, reviewCount, finalMessage, finalJobStatus, true, null);
        } else {
            sendProgress(taskId, completed, total, finalMessage, finalJobStatus, false, null);
        }

        return TaskCreateResponse.builder()
                .taskType(TaskType.ISBN_DISCOVERY)
                .taskId(taskId)
                .status(allFailed ? TaskStatus.FAILED : TaskStatus.COMPLETED)
                .build();
    }

    private void markBookCompleted(MetadataFetchJobEntity job, Long bookId) {
        if (job == null || bookId == null) {
            return;
        }
        List<Long> completedIds = new ArrayList<>(Optional.ofNullable(job.getCompletedBookIds()).orElseGet(ArrayList::new));
        if (!completedIds.contains(bookId)) {
            completedIds.add(bookId);
            job.setCompletedBookIds(completedIds);
        }
    }

    private void stageBookForReview(Long bookId) {
        try {
            bookUpdateService.stageForReview(Set.of(bookId));
        } catch (Exception e) {
            log.warn("Failed to stage book {} for metadata review: {}", bookId, e.getMessage());
        }
    }

    private static String buildProgressMessage(Long bookId, IsbnMetadataFillService.IsbnFillOutcome outcome) {
        return "Book " + bookId + ": " + outcome.status()
                + (outcome.message() != null ? " — " + outcome.message() : "");
    }

    private static String buildFinalMessage(int reviewCount, int failedCount, int completed) {
        if (reviewCount > 0) {
            return String.format(
                    "ISBN discovery completed. %d book(s) need review%s.",
                    reviewCount,
                    failedCount > 0 ? ", " + failedCount + " failed" : "");
        }
        if (failedCount > 0) {
            return String.format(
                    "ISBN discovery completed with %d failure(s) of %d. No reviewable proposals were created (often: no ISBN in front matter).",
                    failedCount, completed);
        }
        return "ISBN discovery completed. Metadata applied automatically.";
    }

    private void sendProgress(String taskId, int completed, int total, String message,
                              MetadataFetchTaskStatus status, boolean review, String phase) {
        notificationService.sendMessage(
                Topic.BOOK_METADATA_BATCH_PROGRESS,
                new MetadataBatchProgressNotification(
                        taskId, completed, total, message, status.name(), review, false, null, phase));
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.ISBN_DISCOVERY;
    }
}
