package org.fable.service;

import org.fable.config.security.service.AuthenticationService;
import org.fable.mapper.FetchedProposalMapper;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.FetchedProposal;
import org.fable.model.dto.MetadataBatchProgressNotification;
import org.fable.model.dto.MetadataFetchTask;
import org.fable.model.dto.response.MetadataTaskDetailsResponse;
import org.fable.model.entity.MetadataFetchJobEntity;
import org.fable.model.entity.MetadataFetchProposalEntity;
import org.fable.model.enums.FetchedMetadataProposalStatus;
import org.fable.model.enums.MetadataFetchTaskStatus;
import org.fable.repository.BookRepository;
import org.fable.repository.MetadataFetchJobRepository;
import org.fable.repository.MetadataFetchProposalRepository;
import org.fable.repository.TaskHistoryRepository;
import org.fable.service.library.LibraryVisibilityService;
import org.fable.service.metadata.MetadataRefreshService;
import org.fable.service.metadata.MetadataTaskService;
import org.fable.service.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MetadataTaskHistoryServiceTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2025-01-01T12:00:00Z");

    @Mock
    private MetadataFetchJobRepository jobRepository;

    @Mock
    private MetadataFetchProposalRepository proposalRepository;

    @Mock
    private TaskHistoryRepository taskHistoryRepository;

    @Mock
    private FetchedProposalMapper fetchedProposalMapper;

    @Mock
    private AuthenticationService authenticationService;

    @Mock
    private TaskService taskService;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private MetadataRefreshService metadataRefreshService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private LibraryVisibilityService libraryVisibilityService;

    @InjectMocks
    private MetadataTaskService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getTaskWithProposals_shouldReturnEmptyWhenNoTaskFound() {
        when(jobRepository.findById("task1")).thenReturn(Optional.empty());

        Optional<MetadataTaskDetailsResponse> result = service.getTaskWithProposals("task1");

        assertThat(result).isEmpty();
        verify(jobRepository).findById("task1");
        verifyNoMoreInteractions(proposalRepository, fetchedProposalMapper);
    }

    @Test
    void getTaskWithProposals_shouldReturnTaskWithFilteredProposals() {
        MetadataFetchJobEntity jobEntity = mock(MetadataFetchJobEntity.class);
        MetadataFetchProposalEntity p1 = mock(MetadataFetchProposalEntity.class);
        MetadataFetchProposalEntity p2 = mock(MetadataFetchProposalEntity.class);

        when(jobEntity.getTaskId()).thenReturn("task1");
        when(jobEntity.getStatus()).thenReturn(MetadataFetchTaskStatus.IN_PROGRESS);
        when(jobEntity.getCompletedBooks()).thenReturn(2);
        when(jobEntity.getTotalBooksCount()).thenReturn(3);
        when(jobEntity.getStartedAt()).thenReturn(FIXED_INSTANT.minusSeconds(60));
        when(jobEntity.getCompletedAt()).thenReturn(null);
        when(jobEntity.getUserId()).thenReturn(99L);
        when(jobEntity.getRequestedBookIds()).thenReturn(List.of(100L));

        when(p1.getStatus()).thenReturn(FetchedMetadataProposalStatus.FETCHED);
        when(p2.getStatus()).thenReturn(FetchedMetadataProposalStatus.ACCEPTED);

        when(jobEntity.getProposals()).thenReturn(List.of(p1, p2));
        when(jobRepository.findById("task1")).thenReturn(Optional.of(jobEntity));

        FetchedProposal dto1 = mock(FetchedProposal.class);
        when(dto1.getBookId()).thenReturn(100L);
        when(fetchedProposalMapper.toDto(p1)).thenReturn(dto1);

        Optional<MetadataTaskDetailsResponse> optResponse = service.getTaskWithProposals("task1");
        assertThat(optResponse).isPresent();

        MetadataTaskDetailsResponse response = optResponse.get();
        MetadataFetchTask taskDto = response.getTask();

        assertThat(taskDto.getId()).isEqualTo("task1");
        assertThat(taskDto.getStatus()).isEqualTo(MetadataFetchTaskStatus.IN_PROGRESS);
        assertThat(taskDto.getCompleted()).isEqualTo(2);
        assertThat(taskDto.getTotalBooks()).isEqualTo(3);
        assertThat(taskDto.getStartedAt()).isEqualTo(FIXED_INSTANT.minusSeconds(60));
        assertThat(taskDto.getCompletedAt()).isNull();
        assertThat(taskDto.getInitiatedBy()).isEqualTo(99L);
        assertThat(taskDto.getProposals()).containsExactly(dto1);

        verify(jobRepository).findById("task1");
        verify(fetchedProposalMapper).toDto(p1);
        verifyNoMoreInteractions(proposalRepository);
    }

    @Test
    void deleteTaskAndProposals_shouldDeleteWhenTaskExists() {
        MetadataFetchJobEntity jobEntity = mock(MetadataFetchJobEntity.class);
        when(jobRepository.findByTaskIdWithProposals("task1")).thenReturn(Optional.of(jobEntity));

        boolean result = service.deleteTaskAndProposals("task1");

        assertThat(result).isTrue();
        verify(jobRepository).findByTaskIdWithProposals("task1");
        verify(jobRepository).delete(jobEntity);
        verify(jobRepository).flush();
    }

    @Test
    void deleteTaskAndProposals_shouldReturnFalseWhenTaskMissing() {
        when(jobRepository.findByTaskIdWithProposals("missing")).thenReturn(Optional.empty());

        boolean result = service.deleteTaskAndProposals("missing");

        assertThat(result).isFalse();
        verify(jobRepository).findByTaskIdWithProposals("missing");
        verifyNoMoreInteractions(jobRepository);
    }

    @Test
    void updateProposalStatus_shouldReturnFalseIfInvalidStatus() {
        FableUser mockedUser = mock(FableUser.class);
        when(mockedUser.getId()).thenReturn(1L);
        when(authenticationService.getAuthenticatedUser()).thenReturn(mockedUser);
        boolean result = service.updateProposalStatus("task1", 1L, "INVALID_STATUS");
        assertThat(result).isFalse();
        verifyNoInteractions(proposalRepository);
    }


    @Test
    void updateProposalStatus_shouldReturnFalseIfProposalNotFoundOrJobMismatch() {
        FableUser mockedUser = mock(FableUser.class);
        when(mockedUser.getId()).thenReturn(123L);
        when(authenticationService.getAuthenticatedUser()).thenReturn(mockedUser);

        when(proposalRepository.findById(1L)).thenReturn(Optional.empty());

        boolean result = service.updateProposalStatus("task1", 1L, "ACCEPTED");
        assertThat(result).isFalse();

        MetadataFetchProposalEntity proposal = mock(MetadataFetchProposalEntity.class);
        when(proposal.getJob()).thenReturn(null);
        when(proposalRepository.findById(2L)).thenReturn(Optional.of(proposal));

        boolean result2 = service.updateProposalStatus("task1", 2L, "REJECTED");
        assertThat(result2).isFalse();
    }

    @Test
    void updateProposalStatus_shouldUpdateProposalWhenValid() {
        MetadataFetchProposalEntity proposal = mock(MetadataFetchProposalEntity.class);
        MetadataFetchJobEntity job = mock(MetadataFetchJobEntity.class);
        when(proposal.getJob()).thenReturn(job);
        when(job.getTaskId()).thenReturn("task1");

        FableUser mockedUser = mock(FableUser.class);
        when(mockedUser.getId()).thenReturn(42L);
        when(authenticationService.getAuthenticatedUser()).thenReturn(mockedUser);

        when(proposalRepository.findById(10L)).thenReturn(Optional.of(proposal));

        boolean result = service.updateProposalStatus("task1", 10L, "ACCEPTED");

        assertThat(result).isTrue();
        verify(proposal).setStatus(FetchedMetadataProposalStatus.ACCEPTED);
        verify(proposal).setReviewedAt(any(Instant.class));
        verify(proposal).setReviewerUserId(42L);
        verify(proposalRepository).save(proposal);
    }

    @Test
    void getActiveTasks_shouldReturnOnlyTasksWithRemainingProposals() {
        MetadataFetchJobEntity job1 = mock(MetadataFetchJobEntity.class);
        MetadataFetchProposalEntity p1 = mock(MetadataFetchProposalEntity.class);
        MetadataFetchProposalEntity p2 = mock(MetadataFetchProposalEntity.class);

        when(job1.getTaskId()).thenReturn("task1");
        when(job1.getStatus()).thenReturn(MetadataFetchTaskStatus.COMPLETED);
        when(job1.getProposals()).thenReturn(List.of(p1, p2));
        when(p1.getStatus()).thenReturn(FetchedMetadataProposalStatus.ACCEPTED);
        when(p2.getStatus()).thenReturn(FetchedMetadataProposalStatus.REJECTED);

        MetadataFetchJobEntity job2 = mock(MetadataFetchJobEntity.class);
        MetadataFetchProposalEntity p3 = mock(MetadataFetchProposalEntity.class);
        when(job2.getTaskId()).thenReturn("task2");
        when(job2.getStatus()).thenReturn(MetadataFetchTaskStatus.COMPLETED);
        when(job2.getProposals()).thenReturn(List.of(p3));
        when(p3.getStatus()).thenReturn(FetchedMetadataProposalStatus.FETCHED);

        when(jobRepository.findAllWithProposals()).thenReturn(List.of(job1, job2));

        List<MetadataBatchProgressNotification> notifications = service.getActiveTasks();

        assertThat(notifications).hasSize(2);

        MetadataBatchProgressNotification n1 = notifications.stream()
                .filter(n -> n.getTaskId().equals("task1"))
                .findFirst().orElseThrow();
        assertThat(n1.getTotal()).isEqualTo(1);
        assertThat(n1.getCompleted()).isEqualTo(1);
        assertThat(n1.getMessage()).contains("Metadata fetch completed! 0 books need review.");

        MetadataBatchProgressNotification n2 = notifications.stream()
                .filter(n -> n.getTaskId().equals("task2"))
                .findFirst().orElseThrow();
        assertThat(n2.getTotal()).isEqualTo(1);
        assertThat(n2.getCompleted()).isEqualTo(0);
        assertThat(n2.getMessage()).contains("Metadata fetch completed! 1 books need review.");

        verify(jobRepository).findAllWithProposals();
    }

    @Test
    void getActiveTasks_shouldKeepTasksWithNoRemainingProposalsVisible() {
        MetadataFetchJobEntity job = mock(MetadataFetchJobEntity.class);
        MetadataFetchProposalEntity p1 = mock(MetadataFetchProposalEntity.class);
        when(job.getTaskId()).thenReturn("task1");
        when(job.getStatus()).thenReturn(MetadataFetchTaskStatus.COMPLETED);
        when(job.getProposals()).thenReturn(List.of(p1));
        when(p1.getStatus()).thenReturn(FetchedMetadataProposalStatus.REJECTED);

        when(jobRepository.findAllWithProposals()).thenReturn(List.of(job));

        List<MetadataBatchProgressNotification> notifications = service.getActiveTasks();

        assertThat(notifications).hasSize(1);
        assertThat(notifications.getFirst().getTaskId()).isEqualTo("task1");
        assertThat(notifications.getFirst().getTotal()).isZero();
        assertThat(notifications.getFirst().getStatus()).isEqualTo("COMPLETED");
        verify(jobRepository).findAllWithProposals();
    }
}