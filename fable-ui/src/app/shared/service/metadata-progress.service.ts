import {inject, Injectable, OnDestroy} from '@angular/core';
import {BehaviorSubject, Subject, Subscription, timer} from 'rxjs';
import {MetadataBatchProgressNotification} from '../model/metadata-batch-progress.model';
import {MetadataTaskService} from '../../features/book/service/metadata-task';
import {UserService} from '../../features/settings/user-management/user.service';
import {LocalStorageService} from './local-storage.service';
import {filter, switchMap, take} from 'rxjs/operators';

export function mergeMetadataTaskProgress(
  existing: MetadataBatchProgressNotification | undefined,
  incoming: MetadataBatchProgressNotification
): MetadataBatchProgressNotification {
  const retainedPhase = incoming.phase ?? existing?.phase ?? null;

  if (incoming.status !== 'IN_PROGRESS') {
    return {
      ...incoming,
      phase: retainedPhase,
      cancellationRequested: false,
    };
  }

  return {
    ...incoming,
    phase: retainedPhase,
    cancellationRequested: existing?.cancellationRequested ?? false,
  };
}

const DISMISSED_TASKS_STORAGE_KEY = 'bl-dismissed-metadata-task-ids';

@Injectable({providedIn: 'root'})
export class MetadataProgressService implements OnDestroy {
  private progressMap = new Map<string, BehaviorSubject<MetadataBatchProgressNotification>>();
  private dismissedTaskIds = new Set<string>();

  private progressUpdatesSubject = new Subject<MetadataBatchProgressNotification>();
  progressUpdates$ = this.progressUpdatesSubject.asObservable();

  private activeTasksSubject = new BehaviorSubject<Record<string, MetadataBatchProgressNotification>>({});
  activeTasks$ = this.activeTasksSubject.asObservable();

  private metadataTaskService = inject(MetadataTaskService);
  private userService = inject(UserService);
  private localStorageService = inject(LocalStorageService);

  private subscriptions = new Subscription();

  constructor() {
    this.restoreDismissedTaskIds();

    const sub = this.userService.userState$
      .pipe(
        filter(userState => !!userState?.user),
        take(1)
      )
      .subscribe(userState => {
        if (!this.hasMetadataPermissions(userState.user)) {
          return;
        }
        const activeTasksSub = timer(0, 30000).pipe(
          switchMap(() => this.metadataTaskService.getActiveTasks())
        ).subscribe({
          next: (tasks) => this.syncActiveTasks(tasks),
          error: (err) => console.warn('Failed to fetch active metadata tasks:', err)
        });
        this.subscriptions.add(activeTasksSub);
      });

    this.subscriptions.add(sub);
  }

  handleIncomingProgress(progress: MetadataBatchProgressNotification): void {
    const {taskId} = progress;
    if (this.dismissedTaskIds.has(taskId)) {
      return;
    }
    const existing = this.progressMap.get(taskId)?.getValue();
    const mergedProgress = mergeMetadataTaskProgress(existing, progress);

    if (!this.progressMap.has(taskId)) {
      this.progressMap.set(taskId, new BehaviorSubject(mergedProgress));
    } else {
      this.progressMap.get(taskId)!.next(mergedProgress);
    }

    this.progressUpdatesSubject.next(mergedProgress);
    this.activeTasksSubject.next(this.getActiveTasks());
  }

  markCancellationRequested(taskId: string, message: string): void {
    const task = this.progressMap.get(taskId)?.getValue();
    if (!task || task.status !== 'IN_PROGRESS') {
      return;
    }

    const updatedTask: MetadataBatchProgressNotification = {
      ...task,
      cancellationRequested: true,
      message,
    };

    this.progressMap.get(taskId)!.next(updatedTask);
    this.progressUpdatesSubject.next(updatedTask);
    this.activeTasksSubject.next(this.getActiveTasks());
  }

  clearTask(taskId: string): void {
    this.dismissedTaskIds.delete(taskId);
    this.persistDismissedTaskIds();
    this.progressMap.delete(taskId);
    this.activeTasksSubject.next(this.getActiveTasks());
  }

  /** Hide a notification locally without deleting its task or review proposals. */
  dismissTask(taskId: string): void {
    this.dismissedTaskIds.add(taskId);
    this.persistDismissedTaskIds();
    this.progressMap.delete(taskId);
    this.activeTasksSubject.next(this.getActiveTasks());
  }

  /** True when at least one finished (non-running) task card is visible. */
  hasFinishedTasks(): boolean {
    for (const task of this.progressMap.values()) {
      if (task.getValue().status !== 'IN_PROGRESS') {
        return true;
      }
    }
    return false;
  }

  /**
   * Dismiss every finished task card (COMPLETED / ERROR / CANCELLED).
   * Leaves IN_PROGRESS cards alone; does not cancel work or delete review proposals.
   */
  dismissFinishedTasks(): number {
    const finishedIds: string[] = [];
    this.progressMap.forEach((subject, taskId) => {
      if (subject.getValue().status !== 'IN_PROGRESS') {
        finishedIds.push(taskId);
      }
    });
    for (const taskId of finishedIds) {
      this.dismissedTaskIds.add(taskId);
      this.progressMap.delete(taskId);
    }
    if (finishedIds.length > 0) {
      this.persistDismissedTaskIds();
      this.activeTasksSubject.next(this.getActiveTasks());
    }
    return finishedIds.length;
  }

  getActiveTasks(): Record<string, MetadataBatchProgressNotification> {
    const result: Record<string, MetadataBatchProgressNotification> = {};
    this.progressMap.forEach((subject, taskId) => {
      result[taskId] = subject.getValue();
    });
    return result;
  }

  private hasMetadataPermissions(user: { permissions: { admin: boolean; canEditMetadata: boolean } } | null): boolean {
    return !!(user?.permissions?.admin || user?.permissions?.canEditMetadata);
  }

  private syncActiveTasks(tasks: MetadataBatchProgressNotification[]): void {
    const incomingTaskIds = new Set(tasks.map(task => task.taskId));

    for (const taskId of this.dismissedTaskIds) {
      if (!incomingTaskIds.has(taskId)) {
        this.dismissedTaskIds.delete(taskId);
      }
    }
    this.persistDismissedTaskIds();

    for (const taskId of this.progressMap.keys()) {
      if (!incomingTaskIds.has(taskId)) {
        this.progressMap.delete(taskId);
      }
    }

    for (const task of tasks) {
      if (this.dismissedTaskIds.has(task.taskId)) {
        continue;
      }
      const existing = this.progressMap.get(task.taskId)?.getValue();
      const mergedTask = mergeMetadataTaskProgress(existing, task);

      if (!this.progressMap.has(task.taskId)) {
        this.progressMap.set(task.taskId, new BehaviorSubject(mergedTask));
      } else {
        this.progressMap.get(task.taskId)!.next(mergedTask);
      }
      this.progressUpdatesSubject.next(mergedTask);
    }
    this.activeTasksSubject.next(this.getActiveTasks());
  }

  private restoreDismissedTaskIds(): void {
    const saved = this.localStorageService.get<string[]>(DISMISSED_TASKS_STORAGE_KEY);
    if (!Array.isArray(saved)) {
      return;
    }
    for (const taskId of saved) {
      if (typeof taskId === 'string' && taskId.length > 0) {
        this.dismissedTaskIds.add(taskId);
      }
    }
  }

  private persistDismissedTaskIds(): void {
    this.localStorageService.trySet(DISMISSED_TASKS_STORAGE_KEY, [...this.dismissedTaskIds]);
  }

  ngOnDestroy(): void {
    this.subscriptions.unsubscribe();
    this.progressMap.forEach(subject => subject.complete());
    this.progressUpdatesSubject.complete();
    this.activeTasksSubject.complete();
  }
}
