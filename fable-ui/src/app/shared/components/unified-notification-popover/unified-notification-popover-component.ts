import {Component, inject} from '@angular/core';
import {LiveNotificationBoxComponent} from '../live-notification-box/live-notification-box.component';
import {MetadataProgressService} from '../../service/metadata-progress.service';
import {map, startWith} from 'rxjs/operators';
import {AsyncPipe} from '@angular/common';
import {BookdropFileService} from '../../../features/bookdrop/service/bookdrop-file.service';
import {BookdropFilesWidgetComponent} from '../../../features/bookdrop/component/bookdrop-files-widget/bookdrop-files-widget.component';
import {MetadataProgressWidgetComponent} from '../metadata-progress-widget/metadata-progress-widget-component';
import {combineLatest} from 'rxjs';
import {AiSearchScanProgressService} from '../../service/ai-search-scan-progress.service';
import {AiSearchProgressWidgetComponent} from '../ai-search-progress-widget/ai-search-progress-widget-component';
import {SystemJobProgressWidgetComponent} from '../system-job-progress-widget/system-job-progress-widget.component';
import {Tab, TabList, TabPanel, TabPanels, Tabs} from 'primeng/tabs';
import {TranslocoDirective} from '@jsverse/transloco';
import {NotificationEventService} from '../../websocket/notification-event.service';
import {
  TaskService,
  TaskStatus,
  TaskType
} from '../../../features/settings/task-management/task.service';
import {WriteProgressService} from '../../service/write-progress.service';
import {SidecarBackupProgressService} from '../../service/sidecar-backup-progress.service';

const SYSTEM_JOB_TYPES = new Set<string>([
  TaskType.FLUSH_METADATA_TO_FILES,
  TaskType.SYNC_LIBRARY_FILES,
  TaskType.DIRECTORY_TAGGING,
  TaskType.REFRESH_LIBRARY_METADATA,
  TaskType.REFRESH_METADATA_MANUAL,
  TaskType.UPDATE_BOOK_RECOMMENDATIONS
]);

@Component({
  selector: 'app-unified-notification-popover-component',
  imports: [
    LiveNotificationBoxComponent,
    MetadataProgressWidgetComponent,
    AsyncPipe,
    BookdropFilesWidgetComponent,
    AiSearchProgressWidgetComponent,
    SystemJobProgressWidgetComponent,
    Tabs,
    TabList,
    Tab,
    TabPanels,
    TabPanel,
    TranslocoDirective
  ],
  templateUrl: './unified-notification-popover-component.html',
  standalone: true,
  styleUrl: './unified-notification-popover-component.scss'
})
export class UnifiedNotificationBoxComponent {
  private taskService = inject(TaskService);
  private writeProgressService = inject(WriteProgressService);
  private sidecarBackupProgressService = inject(SidecarBackupProgressService);

  metadataProgressService = inject(MetadataProgressService);
  bookdropFileService = inject(BookdropFileService);
  aiSearchScanProgressService = inject(AiSearchScanProgressService);
  notificationEventService = inject(NotificationEventService);

  hasMetadataTasks$ = this.metadataProgressService.activeTasks$.pipe(
    map(tasks => Object.keys(tasks).length > 0)
  );

  hasClearableFinishedTasks$ = combineLatest([
    this.metadataProgressService.activeTasks$,
    this.aiSearchScanProgressService.progress$,
    this.aiSearchScanProgressService.isStopping$
  ]).pipe(
    map(([tasks]) =>
      Object.values(tasks).some(task => task.status !== 'IN_PROGRESS')
      || this.aiSearchScanProgressService.hasDismissableFinishedBatch()),
    startWith(false)
  );

  hasPendingBookdropFiles$ = this.bookdropFileService.hasPendingFiles$;

  hasAiSearchScan$ = combineLatest([
    this.aiSearchScanProgressService.progress$,
    this.aiSearchScanProgressService.isStopping$
  ]).pipe(
    map(([progress, isStopping]) => {
      if (isStopping) return true;
      return !!(progress && progress.mode === 'BATCH');
    })
  );

  /** Topbar-only jobs must appear in Tasks on phone (chips are hidden &lt;768px). */
  hasSystemJobs$ = combineLatest([
    this.taskService.taskProgress$.pipe(
      map(p => !!(p && SYSTEM_JOB_TYPES.has(p.taskType) && p.taskStatus === TaskStatus.IN_PROGRESS)),
      startWith(false)
    ),
    this.writeProgressService.progress$.pipe(
      map(p => !!p && p.status === 'IN_PROGRESS'),
      startWith(false)
    ),
    this.sidecarBackupProgressService.active$.pipe(startWith(false))
  ]).pipe(
    map(([task, write, sidecar]) => task || write || sidecar)
  );

  hasRunningTasks$ = combineLatest([
    this.hasMetadataTasks$,
    this.hasPendingBookdropFiles$,
    this.hasAiSearchScan$,
    this.hasSystemJobs$
  ]).pipe(
    map(([meta, bookdrop, ai, system]) => meta || bookdrop || ai || system)
  );

  failureCount$ = this.notificationEventService.unreadFailureCount$;

  clearFinishedTasks(): void {
    this.metadataProgressService.dismissFinishedTasks();
    this.aiSearchScanProgressService.dismissFinishedBatch();
  }
}
