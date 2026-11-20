package org.fable.service.metadata;

import org.fable.config.security.service.AuthenticationService;
import org.fable.mapper.FetchedProposalMapper;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.FetchedProposal;
import org.fable.model.dto.MetadataBatchProgressNotification;
import org.fable.model.dto.MetadataFetchTask;
import org.fable.model.dto.response.MetadataTaskLogBookResponse;
import org.fable.model.dto.response.MetadataTaskLogResponse;
import org.fable.model.dto.response.MetadataTaskDetailsResponse;
import org.fable.model.dto.response.MetadataResumableTaskResponse;
import org.fable.model.dto.response.PendingMetadataReviewResponse;
import org.fable.model.dto.response.StagingTriageResponse;
import org.fable.model.dto.response.TaskCancelResponse;
import org.fable.model.dto.response.TaskCreateResponse;
import org.fable.model.dto.request.IsbnDiscoveryRequest;
import org.fable.model.dto.request.MetadataRefreshOptions;
import org.fable.model.dto.request.MetadataRefreshRequest;
import org.fable.model.dto.request.TaskCreateRequest;
import org.fable.model.entity.BookEntity;
import org.fable.model.entity.BookFileEntity;
import org.fable.model.entity.BookMetadataEntity;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.entity.MetadataFetchProposalEntity;
import org.fable.model.entity.TaskHistoryEntity;
import org.fable.model.enums.TaskType;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.repository.MetadataFetchProposalRepository;
import org.fable.repository.TaskHistoryRepository;
import org.fable.repository.BookRepository;
import org.fable.service.library.LibraryVisibilityService;
import org.fable.service.task.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.fable.task.TaskStatus.ACCEPTED;
import static org.fable.task.TaskStatus.IN_PROGRESS;
import static org.fable.task.TaskStatus.FAILED;

@Service
@RequiredArgsConstructor
public class MetadataTaskService {

    private static final Pattern ISSUE_RANGE_PATTERN = Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)$");

    private final MetadataFetchJobRepository metadataFetchTaskRepository;
    private final MetadataFetchProposalRepository proposalRepository;
    private final TaskHistoryRepository taskHistoryRepository;
    private final FetchedProposalMapper fetchedProposalMapper;
    private final AuthenticationService authenticationService;
    private final TaskService taskService;
    private final BookRepository bookRepository;
    private final MetadataRefreshService metadataRefreshService;
    private final ObjectMapper objectMapper;
    private final LibraryVisibilityService libraryVisibilityService;

    public Optional<MetadataTaskDetailsResponse> getTaskWithProposals(String taskId) {
        return metadataFetchTaskRepository.findById(taskId)
                .map(this::buildTaskDetailsResponse);
    }

    /**
     * Distinct books with FETCHED proposals across all jobs, grouped by task for review dialog launch.
     */
    @Transactional(readOnly = true)
    public PendingMetadataReviewResponse getPendingReviews() {
        List<MetadataFetchProposalEntity> proposals =
                proposalRepository.findAllByStatusWithJob(FetchedMetadataProposalStatus.FETCHED);

        FableUser user = authenticationService.getAuthenticatedUser();
        java.util.Set<Long> allowedBookIds = null;
        if (user != null) {
            java.util.Set<Long> accessibleLibraryIds = libraryVisibilityService.getAccessibleLibraryIds(user);
            if (accessibleLibraryIds.isEmpty()) {
                return PendingMetadataReviewResponse.builder()
                        .count(0)
                        .bookIds(List.of())
                        .tasks(List.of())
                        .build();
            }
            java.util.Set<Long> proposalBookIds = proposals.stream()
                    .map(MetadataFetchProposalEntity::getBookId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            if (proposalBookIds.isEmpty()) {
                return PendingMetadataReviewResponse.builder()
                        .count(0)
                        .bookIds(List.of())
                        .tasks(List.of())
                        .build();
            }
            allowedBookIds = bookRepository.findBookIdsByIdInAndLibraryIdIn(proposalBookIds, accessibleLibraryIds);
        }

        LinkedHashSet<Long> bookIds = new LinkedHashSet<>();
        Map<String, LinkedHashSet<Long>> taskBookIds = new LinkedHashMap<>();

        for (MetadataFetchProposalEntity proposal : proposals) {
            if (proposal.getBookId() == null || proposal.getJob() == null) {
                continue;
            }
            if (allowedBookIds != null && !allowedBookIds.contains(proposal.getBookId())) {
                continue;
            }
            String taskId = proposal.getJob().getTaskId();
            bookIds.add(proposal.getBookId());
            taskBookIds.computeIfAbsent(taskId, ignored -> new LinkedHashSet<>()).add(proposal.getBookId());
        }

        List<PendingMetadataReviewResponse.PendingReviewTask> tasks = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<Long>> entry : taskBookIds.entrySet()) {
            tasks.add(PendingMetadataReviewResponse.PendingReviewTask.builder()
                    .taskId(entry.getKey())
                    .bookIds(new ArrayList<>(entry.getValue()))
                    .proposalCount(entry.getValue().size())
                    .build());
        }

        return PendingMetadataReviewResponse.builder()
                .count(bookIds.size())
                .bookIds(new ArrayList<>(bookIds))
                .tasks(tasks)
                .build();
    }

    /**
     * Exclusive Staging triage buckets: inbox (Staging), Completed, Review.
     * Review (FETCHED proposals) wins; Completed = metadata applied/fetched; else Staging inbox.
     */
    @Transactional(readOnly = true)
    public StagingTriageResponse getStagingTriage() {
        FableUser user = authenticationService.getAuthenticatedUser();
        List<BookEntity> stagedBooks;
        if (user != null) {
            java.util.Set<Long> accessibleLibraryIds = libraryVisibilityService.getAccessibleLibraryIds(user);
            if (accessibleLibraryIds.isEmpty()) {
                return StagingTriageResponse.builder().build();
            }
            stagedBooks = bookRepository.findAllStagedWithMetadataByLibraryIds(accessibleLibraryIds);
        } else {
            stagedBooks = bookRepository.findAllStagedWithMetadata();
        }

        PendingMetadataReviewResponse pending = getPendingReviews();
        LinkedHashSet<Long> reviewIds = new LinkedHashSet<>(pending.getBookIds());
        LinkedHashSet<Long> stagedBookIds = new LinkedHashSet<>();
        for (BookEntity book : stagedBooks) {
            if (book.getId() != null) {
                stagedBookIds.add(book.getId());
            }
        }
        reviewIds.retainAll(stagedBookIds);

        List<Long> stagingIds = new ArrayList<>();
        List<Long> completedIds = new ArrayList<>();

        for (BookEntity book : stagedBooks) {
            Long id = book.getId();
            if (id == null) {
                continue;
            }
            if (reviewIds.contains(id)) {
                continue;
            }
            if (isStagingCompleted(book)) {
                completedIds.add(id);
            } else {
                stagingIds.add(id);
            }
        }

        List<Long> reviewBookIds = new ArrayList<>(reviewIds);
        List<PendingMetadataReviewResponse.PendingReviewTask> reviewTasks = new ArrayList<>();
        for (PendingMetadataReviewResponse.PendingReviewTask task : pending.getTasks()) {
            List<Long> stagedReviewBookIds = task.getBookIds().stream()
                    .filter(reviewIds::contains)
                    .toList();
            if (stagedReviewBookIds.isEmpty()) {
                continue;
            }
            reviewTasks.add(PendingMetadataReviewResponse.PendingReviewTask.builder()
                    .taskId(task.getTaskId())
                    .bookIds(stagedReviewBookIds)
                    .proposalCount(stagedReviewBookIds.size())
                    .build());
        }

        return StagingTriageResponse.builder()
                .stagingCount(stagingIds.size())
                .completedCount(completedIds.size())
                .reviewCount(reviewBookIds.size())
                .stagingBookIds(stagingIds)
                .completedBookIds(completedIds)
                .reviewBookIds(reviewBookIds)
                .reviewTasks(reviewTasks)
                .build();
    }

    private static boolean isStagingCompleted(BookEntity book) {
        if (book.getStagingCompletedAt() != null) {
            return true;
        }
        BookMetadataEntity metadata = book.getMetadata();
        return metadata != null && Boolean.TRUE.equals(metadata.getIsbnVerified());
    }

    public Optional<TaskCancelResponse> cancelMetadataTask(String taskId) {
        return taskHistoryRepository.findById(taskId)
                .filter(task -> task.getType() == TaskType.REFRESH_METADATA_MANUAL
                        || task.getType() == TaskType.ISBN_DISCOVERY)
                .map(task -> taskService.cancelTask(taskId));
    }

    public Optional<MetadataResumableTaskResponse> getLatestResumableTask() {
        FableUser user = authenticationService.getAuthenticatedUser();
        List<MetadataFetchJobEntity> tasks = metadataFetchTaskRepository.findAllWithProposalsByUserIdOrderByStartedAtDesc(user.getId());
        reconcileOrphanedInProgressTasks(tasks);

        return tasks.stream()
                .map(this::toResumableTaskResponse)
                .flatMap(Optional::stream)
                .findFirst();
    }

    public Optional<MetadataTaskLogResponse> getTaskLog(String taskId) {
        Optional<MetadataFetchJobEntity> taskOptional = metadataFetchTaskRepository.findByTaskIdWithProposals(taskId);
        if (taskOptional.isEmpty()) {
            return Optional.empty();
        }

        MetadataFetchJobEntity task = taskOptional.get();
        reconcileOrphanedInProgressTasks(List.of(task));

        List<Long> requestedBookIds = resolveRequestedBookIds(task);
        LinkedHashSet<Long> completedBookIds = resolveCompletedBookIds(task, requestedBookIds);
        LinkedHashSet<Long> remainingBookIds = new LinkedHashSet<>(requestedBookIds);
        remainingBookIds.removeAll(completedBookIds);

        LinkedHashSet<Long> orderedBookIds = new LinkedHashSet<>(requestedBookIds);
        orderedBookIds.addAll(completedBookIds);
        Map<Long, MetadataTaskLogBookResponse> entriesById = buildTaskLogEntries(orderedBookIds);

        return Optional.of(MetadataTaskLogResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .message(task.getStatusMessage())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .completed(completedBookIds.size())
                .total(requestedBookIds.size())
                .pending(remainingBookIds.size())
                .fetchedBooks(mapBookEntries(completedBookIds, entriesById))
                .remainingBooks(mapBookEntries(remainingBookIds, entriesById))
                .build());
    }

    public Optional<TaskCreateResponse> resumeMetadataTask(String taskId) {
        Optional<MetadataFetchJobEntity> taskOptional = metadataFetchTaskRepository.findByTaskIdWithProposals(taskId);
        if (taskOptional.isEmpty()) {
            return Optional.empty();
        }

        MetadataFetchJobEntity task = taskOptional.get();
        reconcileOrphanedInProgressTasks(List.of(task));

        LinkedHashSet<Long> pendingBookIds = getPendingBookIds(task);
        if (pendingBookIds.isEmpty() || !isResumableStatus(task.getStatus())) {
            return Optional.empty();
        }

        TaskHistoryEntity originalTask = taskHistoryRepository.findById(taskId).orElse(null);
        if (originalTask == null
                || (originalTask.getType() != TaskType.REFRESH_METADATA_MANUAL
                && originalTask.getType() != TaskType.ISBN_DISCOVERY)) {
            return Optional.empty();
        }

        TaskCreateRequest taskCreateRequest;
        if (originalTask.getType() == TaskType.ISBN_DISCOVERY) {
            IsbnDiscoveryRequest originalIsbnRequest = originalTask.getTaskOptions() != null
                    ? objectMapper.convertValue(originalTask.getTaskOptions(), IsbnDiscoveryRequest.class)
                    : null;
            IsbnDiscoveryRequest resumeIsbnRequest = IsbnDiscoveryRequest.builder()
                    .bookIds(pendingBookIds)
                    .providers(originalIsbnRequest != null ? originalIsbnRequest.getProviders() : null)
                    .build();
            taskCreateRequest = TaskCreateRequest.builder()
                    .taskType(TaskType.ISBN_DISCOVERY)
                    .triggeredByCron(false)
                    .options(resumeIsbnRequest)
                    .build();
        } else {
            MetadataRefreshRequest originalRequest = objectMapper.convertValue(originalTask.getTaskOptions(), MetadataRefreshRequest.class);
            MetadataRefreshOptions resumeOptions = shiftIssueRangeForResume(
                    originalRequest != null ? originalRequest.getRefreshOptions() : null,
                    resolveRequestedBookIds(task),
                    pendingBookIds
            );
            MetadataRefreshRequest resumeRequest = MetadataRefreshRequest.builder()
                    .refreshType(MetadataRefreshRequest.RefreshType.BOOKS)
                    .bookIds(pendingBookIds)
                    .refreshOptions(resumeOptions)
                    .targetMode(MetadataRefreshRequest.TargetMode.ALL)
                    .build();

            taskCreateRequest = TaskCreateRequest.builder()
                    .taskType(TaskType.REFRESH_METADATA_MANUAL)
                    .triggeredByCron(false)
                    .options(resumeRequest)
                    .build();
        }

        TaskCreateResponse resumedTask = taskService.runAsUser(taskCreateRequest);
        task.setCompletedBookIds(requestedAsList(task));
        task.setStatusMessage(String.format("Metadata fetch resumed as task %s.", resumedTask.getTaskId()));
        metadataFetchTaskRepository.save(task);

        return Optional.of(resumedTask);
    }

    MetadataRefreshOptions shiftIssueRangeForResume(
            MetadataRefreshOptions originalOptions,
            List<Long> requestedBookIds,
            LinkedHashSet<Long> pendingBookIds) {
        if (originalOptions == null || !StringUtils.hasText(originalOptions.getIssueRange())
                || requestedBookIds == null || requestedBookIds.isEmpty()
                || pendingBookIds == null || pendingBookIds.isEmpty()) {
            return originalOptions;
        }

        String rawRange = originalOptions.getIssueRange().trim();
        Matcher matcher = ISSUE_RANGE_PATTERN.matcher(rawRange);
        if (!matcher.matches()) {
            return originalOptions;
        }

        List<Long> pendingList = new ArrayList<>(pendingBookIds);
        int offset = requestedBookIds.size() - pendingList.size();
        if (offset <= 0 || !requestedBookIds.subList(offset, requestedBookIds.size()).equals(pendingList)) {
            return originalOptions;
        }

        int start = Integer.parseInt(matcher.group(1));
        int end = Integer.parseInt(matcher.group(2));
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        int shiftedStart = Math.min(start + offset, end);

        return MetadataRefreshOptions.builder()
                .libraryId(originalOptions.getLibraryId())
                .refreshCovers(originalOptions.isRefreshCovers())
                .mergeCategories(originalOptions.isMergeCategories())
                .reviewBeforeApply(originalOptions.getReviewBeforeApply())
                .sourceUrl(originalOptions.getSourceUrl())
                .issueNumber(originalOptions.getIssueNumber())
                .issueRange(shiftedStart + "-" + end)
                .replaceMode(originalOptions.getReplaceMode())
                .fieldOptions(originalOptions.getFieldOptions())
                .enabledFields(originalOptions.getEnabledFields())
                .build();
    }

    private MetadataTaskDetailsResponse buildTaskDetailsResponse(MetadataFetchJobEntity task) {
        Map<Long, Integer> requestedOrder = new LinkedHashMap<>();
        List<Long> requestedBookIds = resolveRequestedBookIds(task);
        for (int i = 0; i < requestedBookIds.size(); i++) {
            requestedOrder.putIfAbsent(requestedBookIds.get(i), i);
        }

        List<FetchedProposal> proposals = task.getProposals().stream()
                .filter(p -> p.getStatus() == FetchedMetadataProposalStatus.FETCHED)
                .map(fetchedProposalMapper::toDto)
                .sorted(Comparator
                        .comparingInt((FetchedProposal proposal) -> requestedOrder.getOrDefault(proposal.getBookId(), Integer.MAX_VALUE))
                        .thenComparing(FetchedProposal::getProposalId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        MetadataFetchTask taskDto = MetadataFetchTask.builder()
                .id(task.getTaskId())
                .status(task.getStatus())
                .completed(task.getCompletedBooks())
                .totalBooks(task.getTotalBooksCount())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .initiatedBy(task.getUserId())
                .proposals(proposals)
                .build();

        return new MetadataTaskDetailsResponse(taskDto);
    }

    @Transactional
    public boolean deleteTaskAndProposals(String taskId) {
        return metadataFetchTaskRepository.findByTaskIdWithProposals(taskId)
                .map(task -> {
                    metadataFetchTaskRepository.delete(task);
                    metadataFetchTaskRepository.flush();
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public boolean updateProposalStatus(String taskId, Long proposalId, String statusStr) {
        Long userId = authenticationService.getAuthenticatedUser().getId();
        Optional<FetchedMetadataProposalStatus> statusOpt = parseStatus(statusStr);
        return statusOpt.map(fetchedMetadataProposalStatus -> proposalRepository.findById(proposalId)
                .filter(p -> p.getJob() != null && taskId.equals(p.getJob().getTaskId()))
                .map(proposal -> {
                    Instant now = Instant.now();
                    proposal.setStatus(fetchedMetadataProposalStatus);
                    proposal.setReviewedAt(now);
                    proposal.setReviewerUserId(userId);
                    proposalRepository.save(proposal);
                    if (fetchedMetadataProposalStatus == FetchedMetadataProposalStatus.ACCEPTED && proposal.getBookId() != null) {
                        bookRepository.findById(proposal.getBookId()).ifPresent(book -> {
                            book.setLastMetadataFetchAt(now);
                            if (Boolean.TRUE.equals(book.getStaged())) {
                                book.setStagingCompletedAt(now);
                            }
                            bookRepository.save(book);
                        });
                    }
                    return true;
                })
                .orElse(false)).orElse(false);

    }

    private Optional<FetchedMetadataProposalStatus> parseStatus(String statusStr) {
        try {
            return Optional.of(FetchedMetadataProposalStatus.valueOf(statusStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public List<MetadataBatchProgressNotification> getActiveTasks() {
        List<MetadataFetchJobEntity> tasks = metadataFetchTaskRepository.findAllWithProposals();
        reconcileOrphanedInProgressTasks(tasks);

        return tasks.stream()
            .filter(task -> task.getStatus() == MetadataFetchTaskStatus.IN_PROGRESS
                || task.getStatus() == MetadataFetchTaskStatus.COMPLETED
                || task.getStatus() == MetadataFetchTaskStatus.ERROR
                || task.getStatus() == MetadataFetchTaskStatus.CANCELLED)
                .map(task -> {
                    List<MetadataFetchProposalEntity> proposals = task.getProposals();
                    List<MetadataFetchProposalEntity> remaining = proposals.stream()
                            .filter(p -> p.getStatus() != FetchedMetadataProposalStatus.REJECTED)
                            .toList();
                    int pendingCount = 0;
                    boolean resumable = false;
                    if (isResumableStatus(task.getStatus())) {
                        pendingCount = getPendingBookIds(task).size();
                        resumable = pendingCount > 0;
                    }

                    int total;
                    long acceptedCount = remaining.stream()
                            .filter(p -> p.getStatus() == FetchedMetadataProposalStatus.ACCEPTED)
                            .count();
                    long fetchedCount = remaining.stream()
                            .filter(p -> p.getStatus() == FetchedMetadataProposalStatus.FETCHED)
                            .count();

                    String message;
                    String status;
                    int completedCount = task.getCompletedBooks() != null ? task.getCompletedBooks() : 0;
                    boolean isReview = task.getStatus() != MetadataFetchTaskStatus.IN_PROGRESS;

                    if (task.getStatus() == MetadataFetchTaskStatus.IN_PROGRESS) {
                        total = task.getTotalBooksCount() != null ? task.getTotalBooksCount() : remaining.size();
                        message = StringUtils.hasText(task.getStatusMessage())
                                ? task.getStatusMessage()
                                : String.format("Metadata fetch still running, processed %d of %d books.", completedCount, total);
                        status = "IN_PROGRESS";
                    } else if (task.getStatus() == MetadataFetchTaskStatus.ERROR) {
                        total = task.getTotalBooksCount() != null ? task.getTotalBooksCount() : remaining.size();
                        message = StringUtils.hasText(task.getStatusMessage())
                                ? task.getStatusMessage()
                                : String.format("Metadata fetch failed, processed %d of %d books.", completedCount, total);
                        status = "ERROR";
                    } else if (task.getStatus() == MetadataFetchTaskStatus.CANCELLED) {
                        total = task.getTotalBooksCount() != null ? task.getTotalBooksCount() : remaining.size();
                        message = StringUtils.hasText(task.getStatusMessage())
                                ? task.getStatusMessage()
                                : String.format("Metadata fetch cancelled after processing %d of %d books.", completedCount, total);
                        status = "CANCELLED";
                    } else {
                        if (remaining.isEmpty()) {
                            total = task.getTotalBooksCount() != null ? task.getTotalBooksCount() : 0;
                            message = StringUtils.hasText(task.getStatusMessage())
                                    ? task.getStatusMessage()
                                    : "Batch metadata fetch successfully completed!";
                            status = "COMPLETED";
                            completedCount = task.getCompletedBooks() != null ? task.getCompletedBooks() : 0;
                        } else {
                            total = remaining.size();
                            message = String.format("Metadata fetch completed! %d books need review.", fetchedCount);
                            status = "COMPLETED";
                            completedCount = (int) acceptedCount;
                        }
                    }

                    return new MetadataBatchProgressNotification(
                            task.getTaskId(),
                            completedCount,
                            total,
                            message,
                            status,
                            isReview,
                            resumable,
                            resumable ? pendingCount : null
                    );
                })
                .toList();
    }

    private Optional<MetadataResumableTaskResponse> toResumableTaskResponse(MetadataFetchJobEntity task) {
        LinkedHashSet<Long> pendingBookIds = getPendingBookIds(task);
        if (!isResumableStatus(task.getStatus()) || pendingBookIds.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(MetadataResumableTaskResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getStatus())
                .startedAt(task.getStartedAt())
                .pendingBooksCount(pendingBookIds.size())
                .message(task.getStatusMessage())
                .build());
    }

    private void reconcileOrphanedInProgressTasks(List<MetadataFetchJobEntity> tasks) {
        for (MetadataFetchJobEntity task : tasks) {
            if (task.getStatus() != MetadataFetchTaskStatus.IN_PROGRESS || taskService.isTaskRunning(task.getTaskId())) {
                continue;
            }

            LinkedHashSet<Long> pendingBookIds = getPendingBookIds(task);
            String message = pendingBookIds.isEmpty()
                    ? "Metadata fetch stopped unexpectedly."
                    : String.format("Metadata fetch stopped unexpectedly. %d books can be resumed.", pendingBookIds.size());

            task.setStatus(MetadataFetchTaskStatus.ERROR);
            task.setCompletedAt(Instant.now());
            task.setStatusMessage(message);
            metadataFetchTaskRepository.save(task);

            taskHistoryRepository.findById(task.getTaskId()).ifPresent(history -> {
                if (history.getStatus() == ACCEPTED || history.getStatus() == IN_PROGRESS) {
                    history.setStatus(FAILED);
                    history.setMessage(message);
                    history.setErrorDetails(message);
                    history.setUpdatedAt(LocalDateTime.now());
                    history.setCompletedAt(LocalDateTime.now());
                    taskHistoryRepository.save(history);
                }
            });
        }
    }

    private boolean isResumableStatus(MetadataFetchTaskStatus status) {
        return status == MetadataFetchTaskStatus.ERROR || status == MetadataFetchTaskStatus.CANCELLED;
    }

    private LinkedHashSet<Long> getPendingBookIds(MetadataFetchJobEntity task) {
        List<Long> requestedBookIds = resolveRequestedBookIds(task);
        if (requestedBookIds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        LinkedHashSet<Long> pendingBookIds = new LinkedHashSet<>(requestedBookIds);
        pendingBookIds.removeAll(resolveCompletedBookIds(task, requestedBookIds));
        return pendingBookIds;
    }

    private List<Long> requestedAsList(MetadataFetchJobEntity task) {
        return resolveRequestedBookIds(task);
    }

    private Map<Long, MetadataTaskLogBookResponse> buildTaskLogEntries(LinkedHashSet<Long> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }

        List<BookEntity> books = bookRepository.findAllWithMetadataByIds(new LinkedHashSet<>(bookIds));
        Map<Long, BookEntity> booksById = books.stream()
                .collect(java.util.stream.Collectors.toMap(BookEntity::getId, java.util.function.Function.identity()));

        Map<Long, MetadataTaskLogBookResponse> entriesById = new LinkedHashMap<>();
        for (Long bookId : bookIds) {
            BookEntity book = booksById.get(bookId);
            entriesById.put(bookId, MetadataTaskLogBookResponse.builder()
                    .bookId(bookId)
                    .title(resolveLogTitle(book, bookId))
                    .fileName(resolvePrimaryFileName(book))
                    .build());
        }
        return entriesById;
    }

    private List<MetadataTaskLogBookResponse> mapBookEntries(LinkedHashSet<Long> bookIds, Map<Long, MetadataTaskLogBookResponse> entriesById) {
        return bookIds.stream()
                .map(bookId -> entriesById.getOrDefault(bookId, MetadataTaskLogBookResponse.builder()
                        .bookId(bookId)
                        .title(String.format("Book %d", bookId))
                        .fileName("Unknown file")
                        .build()))
                .toList();
    }

    private String resolveLogTitle(BookEntity book, Long bookId) {
        if (book != null && book.getMetadata() != null && StringUtils.hasText(book.getMetadata().getTitle())) {
            return book.getMetadata().getTitle();
        }

        String fileName = resolvePrimaryFileName(book);
        if (StringUtils.hasText(fileName) && !"Unknown file".equals(fileName)) {
            return org.fable.util.FileUtils.deriveTitleFromFileName(fileName, isFolderBased(book));
        }

        return String.format("Book %d", bookId);
    }

    private String resolvePrimaryFileName(BookEntity book) {
        BookFileEntity primaryBookFile = book != null ? book.getPrimaryBookFile() : null;
        return primaryBookFile != null && StringUtils.hasText(primaryBookFile.getFileName())
                ? primaryBookFile.getFileName()
                : "Unknown file";
    }

    private boolean isFolderBased(BookEntity book) {
        BookFileEntity primaryBookFile = book != null ? book.getPrimaryBookFile() : null;
        return primaryBookFile != null && primaryBookFile.isFolderBased();
    }

    private List<Long> resolveRequestedBookIds(MetadataFetchJobEntity task) {
        if (task.getRequestedBookIds() != null && !task.getRequestedBookIds().isEmpty()) {
            return task.getRequestedBookIds();
        }

        return taskHistoryRepository.findById(task.getTaskId())
                .map(TaskHistoryEntity::getTaskOptions)
                .map(options -> objectMapper.convertValue(options, MetadataRefreshRequest.class))
                .map(metadataRefreshService::getBookEntities)
                .map(ids -> ids.stream().toList())
                .orElse(List.of());
    }

    private LinkedHashSet<Long> resolveCompletedBookIds(MetadataFetchJobEntity task, List<Long> requestedBookIds) {
        if (task.getCompletedBookIds() != null && !task.getCompletedBookIds().isEmpty()) {
            return new LinkedHashSet<>(task.getCompletedBookIds());
        }

        LinkedHashSet<Long> proposalBookIds = task.getProposals().stream()
                .map(MetadataFetchProposalEntity::getBookId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!proposalBookIds.isEmpty()) {
            return proposalBookIds;
        }

        if (task.getStartedAt() == null || requestedBookIds.isEmpty()) {
            return new LinkedHashSet<>();
        }

        return new LinkedHashSet<>(bookRepository.findBookIdsByIdInAndLastMetadataFetchAtOnOrAfter(requestedBookIds, task.getStartedAt()));
    }
}