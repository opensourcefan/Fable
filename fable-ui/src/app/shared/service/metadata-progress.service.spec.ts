import {TestBed} from '@angular/core/testing';
import {of} from 'rxjs';
import {afterEach, describe, expect, it, vi} from 'vitest';
import {
  MetadataBatchPhase,
  MetadataBatchProgressNotification,
  MetadataBatchStatus
} from '../model/metadata-batch-progress.model';
import {mergeMetadataTaskProgress} from './metadata-progress.service';
import {MetadataProgressService} from './metadata-progress.service';
import {MetadataTaskService} from '../../features/book/service/metadata-task';
import {UserService} from '../../features/settings/user-management/user.service';
import {LocalStorageService} from './local-storage.service';

function progress(
  overrides: Partial<MetadataBatchProgressNotification> = {}
): MetadataBatchProgressNotification {
  return {
    taskId: 'task-1',
    completed: 0,
    total: 1,
    message: 'Working',
    status: MetadataBatchStatus.IN_PROGRESS,
    review: false,
    ...overrides,
  };
}

describe('mergeMetadataTaskProgress', () => {
  it('retains ISBN phase when polling sends an in-progress payload without phase', () => {
    const existing = progress({phase: MetadataBatchPhase.ISBN_DISCOVERY});
    const polled = progress({message: 'Still working', phase: null});

    expect(mergeMetadataTaskProgress(existing, polled).phase)
      .toBe(MetadataBatchPhase.ISBN_DISCOVERY);
  });

  it('retains metadata phase when the terminal payload omits phase', () => {
    const existing = progress({phase: MetadataBatchPhase.METADATA_FETCH});
    const completed = progress({
      completed: 1,
      message: 'Done',
      status: MetadataBatchStatus.COMPLETED,
      phase: null,
    });

    const merged = mergeMetadataTaskProgress(existing, completed);

    expect(merged.phase).toBe(MetadataBatchPhase.METADATA_FETCH);
    expect(merged.cancellationRequested).toBe(false);
  });

  it('uses a newer explicit phase', () => {
    const existing = progress({phase: MetadataBatchPhase.ISBN_DISCOVERY});
    const metadata = progress({phase: MetadataBatchPhase.METADATA_FETCH});

    expect(mergeMetadataTaskProgress(existing, metadata).phase)
      .toBe(MetadataBatchPhase.METADATA_FETCH);
  });
});

describe('MetadataProgressService dismissal', () => {
  let service: MetadataProgressService;
  let localStorageMock: {get: ReturnType<typeof vi.fn>; trySet: ReturnType<typeof vi.fn>};

  afterEach(() => {
    service?.ngOnDestroy();
    TestBed.resetTestingModule();
  });

  function setup(savedDismissed: string[] | null = null): void {
    localStorageMock = {
      get: vi.fn().mockReturnValue(savedDismissed),
      trySet: vi.fn().mockReturnValue(true),
    };
    TestBed.configureTestingModule({
      providers: [
        MetadataProgressService,
        {
          provide: MetadataTaskService,
          useValue: {getActiveTasks: () => of([])},
        },
        {
          provide: UserService,
          useValue: {
            userState$: of({
              user: {permissions: {admin: true, canEditMetadata: true}},
            }),
          },
        },
        {
          provide: LocalStorageService,
          useValue: localStorageMock,
        },
      ],
    });
    service = TestBed.inject(MetadataProgressService);
  }

  it('ignores later updates for a dismissed task without deleting backend state', () => {
    setup();
    const task = progress({review: true, status: MetadataBatchStatus.COMPLETED});

    service.handleIncomingProgress(task);
    service.dismissTask(task.taskId);
    service.handleIncomingProgress({...task, message: 'Polled again'});

    expect(service.getActiveTasks()).toEqual({});
    expect(localStorageMock.trySet).toHaveBeenCalledWith(
      'bl-dismissed-metadata-task-ids',
      [task.taskId]
    );
  });

  it('restores dismissed task ids across service recreation so reload keeps them hidden', () => {
    setup(['persisted-task']);
    service.handleIncomingProgress(progress({taskId: 'persisted-task'}));
    expect(service.getActiveTasks()).toEqual({});
  });

  it('dismissFinishedTasks clears completed cards and leaves in-progress alone', () => {
    setup();
    service.handleIncomingProgress(progress({
      taskId: 'done-1',
      status: MetadataBatchStatus.COMPLETED,
      completed: 1,
    }));
    service.handleIncomingProgress(progress({
      taskId: 'err-1',
      status: MetadataBatchStatus.ERROR,
    }));
    service.handleIncomingProgress(progress({
      taskId: 'run-1',
      status: MetadataBatchStatus.IN_PROGRESS,
    }));

    expect(service.hasFinishedTasks()).toBe(true);
    expect(service.dismissFinishedTasks()).toBe(2);

    const remaining = service.getActiveTasks();
    expect(Object.keys(remaining)).toEqual(['run-1']);
    expect(service.hasFinishedTasks()).toBe(false);
    expect(localStorageMock.trySet).toHaveBeenCalledWith(
      'bl-dismissed-metadata-task-ids',
      expect.arrayContaining(['done-1', 'err-1'])
    );
  });
});
