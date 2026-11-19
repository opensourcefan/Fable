package org.fable.task.tasks;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fable.model.dto.FableUser;
import org.fable.model.dto.request.MetadataRefreshRequest;
import org.fable.model.dto.request.TaskCreateRequest;
import org.fable.model.dto.response.TaskCreateResponse;
import org.fable.model.enums.TaskType;
import org.fable.service.metadata.MetadataRefreshService;
import org.fable.task.TaskStatus;
import org.springframework.stereotype.Component;

import static org.fable.exception.ApiError.PERMISSION_DENIED;
import static org.fable.model.enums.UserPermission.CAN_BULK_AUTO_FETCH_METADATA;

@AllArgsConstructor
@Component
@Slf4j
public class RefreshMetadataTask implements Task {

    private final MetadataRefreshService metadataRefreshService;

    @Override
    public void validatePermissions(FableUser user, TaskCreateRequest request) {
        MetadataRefreshRequest refreshRequest = request.getOptionsAs(MetadataRefreshRequest.class);

        if (requiresBulkPermission(refreshRequest) &&
            !CAN_BULK_AUTO_FETCH_METADATA.isGranted(user.getPermissions())) {
            throw PERMISSION_DENIED.createException(CAN_BULK_AUTO_FETCH_METADATA);
        }
    }

    private boolean requiresBulkPermission(MetadataRefreshRequest request) {
        if (MetadataRefreshRequest.RefreshType.LIBRARY.equals(request.getRefreshType())) {
            return true;
        }
        return request.getBookIds() != null && request.getBookIds().size() > 1;
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        MetadataRefreshRequest refreshRequest = request.getOptionsAs(MetadataRefreshRequest.class);
        String taskId = request.getTaskId();

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started. TaskId: {}, Options: {}", getTaskType(), taskId, refreshRequest);

        metadataRefreshService.refreshMetadata(refreshRequest, taskId);

        TaskStatus terminalStatus = metadataRefreshService.getTerminalTaskStatus(taskId);
        if (terminalStatus == null) {
            terminalStatus = TaskStatus.COMPLETED;
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task finished with status {}. Duration: {} ms", getTaskType(), terminalStatus, endTime - startTime);

        return TaskCreateResponse
                .builder()
                .taskType(TaskType.REFRESH_METADATA_MANUAL)
                .taskId(taskId)
                .status(terminalStatus)
                .build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.REFRESH_METADATA_MANUAL;
    }
}
