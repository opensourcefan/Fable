import {AsyncPipe} from '@angular/common';
import {Component, inject, OnInit} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {ConfirmationService, MessageService} from 'primeng/api';
import {FailureNotificationService} from '../../../shared/service/failure-notification.service';
import {Button} from 'primeng/button';
import {Select} from 'primeng/select';
import {Tab, TabList, TabPanel, TabPanels, Tabs} from 'primeng/tabs';
import {Tooltip} from 'primeng/tooltip';
import {filter, finalize, take} from 'rxjs/operators';
import {TranslocoDirective, TranslocoService} from '@jsverse/transloco';
import {Library} from '../../book/model/library.model';
import {LibraryService} from '../../book/service/library.service';
import {SidecarBackupHistoryEntry, SidecarService} from '../../metadata/service/sidecar.service';
import {AppSettingsService, AppSettingsTransferFile} from '../../../shared/service/app-settings.service';
import {SidecarBackupProgressService} from '../../../shared/service/sidecar-backup-progress.service';
import {UserService} from '../user-management/user.service';
import {AuditLogService, DatabaseHelperAuditAction} from '../audit-logs/audit-log.service';
import {
  AppSettingsBackupActivity,
  BackupsActivityService,
  DatabaseBackupActivity
} from './backups-activity.service';

type BackupsTab = 'status' | 'app-settings' | 'sidecar' | 'database-export' | 'database-restore';
type StatusTone = 'done' | 'partial' | 'fail' | '';

interface BackupAccessUser {
  permissions: {
    admin: boolean;
    canManageGlobalPreferences: boolean;
    canManageMetadataConfig: boolean;
  };
}

interface RestoreCheck {
  labelKey: string;
  detail: string;
  passed: boolean;
}

@Component({
  selector: 'app-backups',
  standalone: true,
  imports: [
    AsyncPipe,
    FormsModule,
    Tabs,
    TabList,
    Tab,
    TabPanels,
    TabPanel,
    Button,
    Select,
    Tooltip,
    TranslocoDirective
  ],
  templateUrl: './backups.component.html',
  styleUrl: './backups.component.scss'
})
export class BackupsComponent implements OnInit {
  protected readonly userService = inject(UserService);

  private readonly appSettingsService = inject(AppSettingsService);
  private readonly confirmationService = inject(ConfirmationService);
  private readonly messageService = inject(MessageService);
  private failureNotifications = inject(FailureNotificationService);
  private readonly translocoService = inject(TranslocoService);
  private readonly libraryService = inject(LibraryService);
  private readonly sidecarService = inject(SidecarService);
  private readonly sidecarBackupProgressService = inject(SidecarBackupProgressService);
  private readonly backupsActivityService = inject(BackupsActivityService);
  private readonly auditLogService = inject(AuditLogService);

  activeTab: BackupsTab = 'status';

  libraries: Library[] = [];
  selectedBackupLibraryId: number | null = null;
  isSidecarBackupRunning = false;
  isSidecarHistoryLoading = false;
  sidecarHistory: SidecarBackupHistoryEntry[] = [];
  sidecarHistoryLoadFailed = false;
  private canLoadSidecarHistory = false;

  appSettingsActivity: AppSettingsBackupActivity | null = null;
  databaseActivity: DatabaseBackupActivity | null = null;

  backupDirectory = '$HOME/fable-backups';
  backupFileName = '';
  dbContainerName = 'mariadb';
  dbName = 'fable';
  dbUser = 'fable';
  dbPassword = '';

  restoreSqlPath = '$HOME/fable-backups/fable_backup_20260414_020000.sql';
  restoreAppDataPath = '/srv/fable/data';
  restoreComposePath = '/opt/fable/docker-compose.yml';
  availableSpaceGb: number | null = null;
  packageFilesConfirmed = false;
  libraryMediaCopied = false;
  appDataConfirmed = false;
  deploymentConfigConfirmed = false;
  versionConfirmed = false;
  maintenanceReady = false;

  restoreChecks: RestoreCheck[] = [];
  restoreReady = false;

  private toastError(summary: string, detail: string, life = 3000): void {
    this.messageService.add({severity: 'error', summary, detail, life});
    this.failureNotifications.reportSafe(summary, detail);
  }

  ngOnInit(): void {
    this.loadDatabaseConfig();
    this.backupFileName = this.buildDefaultBackupFileName();
    this.refreshActivitySnapshots();
    this.loadLibraries();

    this.userService.userState$.pipe(
      filter((state) => state.loaded),
      take(1)
    ).subscribe({
      next: (state) => {
        this.canLoadSidecarHistory = this.canManageSidecar(state.user);
        if (this.canLoadSidecarHistory && this.selectedBackupLibraryId !== null) {
          this.loadSidecarHistory(this.selectedBackupLibraryId);
        }
      }
    });
  }

  onDbConfigChange(): void {
    this.backupsActivityService.setDatabaseConfig({
      containerName: this.dbContainerName,
      databaseName: this.dbName,
      databaseUser: this.dbUser,
      backupDirectory: this.backupDirectory
    });
  }

  private loadDatabaseConfig(): void {
    const config = this.backupsActivityService.getDatabaseConfig();
    if (config) {
      if (config.containerName !== undefined) {
        this.dbContainerName = config.containerName;
      }
      if (config.databaseName !== undefined) {
        this.dbName = config.databaseName;
      }
      if (config.databaseUser !== undefined) {
        this.dbUser = config.databaseUser;
      }
      if (config.backupDirectory !== undefined) {
        this.backupDirectory = config.backupDirectory;
      }
    }
  }

  canManageAppSettings(user: BackupAccessUser | null | undefined): boolean {
    return !!user && (user.permissions.admin || user.permissions.canManageGlobalPreferences);
  }

  canManageSidecar(user: BackupAccessUser | null | undefined): boolean {
    return !!user && (user.permissions.admin || user.permissions.canManageMetadataConfig);
  }

  canManageDatabase(user: BackupAccessUser | null | undefined): boolean {
    return !!user?.permissions.admin;
  }

  formatDateTime(timestamp: string | null | undefined): string {
    if (!timestamp) {
      return this.translocoService.translate('settingsBackups.common.never');
    }

    return new Intl.DateTimeFormat(undefined, {
      year: 'numeric',
      month: 'short',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    }).format(new Date(timestamp));
  }

  getDatabaseOutputPath(): string {
    return `${this.getNormalizedBackupDirectory()}/${this.backupFileName}`;
  }

  getDatabaseExportCommand(): string {
    const container = this.dbContainerName.trim() || 'mariadb';
    const user = this.dbUser.trim() || 'fable';
    const db = this.dbName.trim() || 'fable';
    const passwordArg = this.dbPassword.trim()
      ? `-p"${this.dbPassword.trim()}"`
      : '-p"$DB_PASSWORD"';

    return `mkdir -p "${this.getNormalizedBackupDirectory()}" && docker exec ${container} mariadb-dump --single-transaction --quick --no-tablespaces -u ${user} ${passwordArg} ${db} > "${this.getDatabaseOutputPath()}"`;
  }

  getDatabaseRestoreCommand(): string {
    const container = this.dbContainerName.trim() || 'mariadb';
    const user = this.dbUser.trim() || 'fable';
    const db = this.dbName.trim() || 'fable';
    const passwordArg = this.dbPassword.trim()
      ? `-p"${this.dbPassword.trim()}"`
      : '-p"$DB_PASSWORD"';

    return `docker exec -i ${container} mariadb -u ${user} ${passwordArg} ${db} < "${this.restoreSqlPath}"`;
  }

  onAvailableSpaceChange(value: string | number | null): void {
    if (value === null || value === '') {
      this.availableSpaceGb = null;
      return;
    }

    const parsedValue = typeof value === 'number' ? value : Number(value);
    this.availableSpaceGb = Number.isFinite(parsedValue) ? parsedValue : null;
  }

  regenerateBackupFileName(): void {
    this.backupFileName = this.buildDefaultBackupFileName();
  }

  getAppSettingsStatusTone(): StatusTone {
    return this.appSettingsActivity ? 'done' : '';
  }

  getAppSettingsStatusLabelKey(): string {
    return this.getStatusLabelKey(this.getAppSettingsStatusTone());
  }

  getSidecarStatusTone(): StatusTone {
    const latestBackup = this.getLatestSidecarHistoryEntry();
    if (!latestBackup) {
      return '';
    }

    if (latestBackup.status === 'FAILED' || (latestBackup.failed > 0 && latestBackup.exported === 0)) {
      return 'fail';
    }

    if (latestBackup.status === 'PARTIAL' || latestBackup.failed > 0) {
      return 'partial';
    }

    return 'done';
  }

  getSidecarStatusLabelKey(): string {
    return this.getStatusLabelKey(this.getSidecarStatusTone());
  }

  getDatabaseStatusTone(): StatusTone {
    if (!this.databaseActivity) {
      return '';
    }

    switch (this.databaseActivity.action) {
      case 'restore-preflight-passed':
        return 'partial';
      case 'restore-preflight-blocked':
        return 'fail';
      default:
        return 'done';
    }
  }

  getDatabaseStatusLabelKey(): string {
    return this.getStatusLabelKey(this.getDatabaseStatusTone());
  }

  getDatabaseActivityLabelKey(): string {
    if (!this.databaseActivity) {
      return 'settingsBackups.common.none';
    }

    switch (this.databaseActivity.action) {
      case 'restore-command':
        return 'settingsBackups.status.database.restorePrepared';
      case 'restore-preflight-passed':
        return 'settingsBackups.status.database.preflightPassed';
      case 'restore-preflight-blocked':
        return 'settingsBackups.status.database.preflightBlocked';
      default:
        return 'settingsBackups.status.database.exportPrepared';
    }
  }

  exportSettings(): void {
    this.appSettingsService.exportSettings().subscribe({
      next: (fileName) => {
        this.backupsActivityService.setAppSettingsActivity({
          action: 'export',
          fileName,
          timestamp: new Date().toISOString()
        });
        this.refreshActivitySnapshots();
        this.showMessage('success', 'settingsBackups.messages.exportRecorded', 'settingsApp.transfer.exportSuccessDetail');
      },
      error: () => {
        this.showMessage('error', 'common.error', 'settingsApp.transfer.exportError');
      }
    });
  }

  async onImportFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';

    if (!file) {
      return;
    }

    try {
      const text = await file.text();
      const payload = JSON.parse(text) as AppSettingsTransferFile;

      if (!payload || typeof payload.version !== 'number' || !Array.isArray(payload.settings)) {
        this.showMessage('error', 'settingsApp.transfer.importInvalid', 'settingsApp.transfer.importInvalidDetail');
        return;
      }

      this.confirmationService.confirm({
        header: this.translocoService.translate('settingsApp.transfer.importConfirmHeader'),
        message: this.translocoService.translate('settingsApp.transfer.importConfirmMessage'),
        icon: 'pi pi-exclamation-triangle',
        acceptLabel: this.translocoService.translate('common.yes'),
        rejectLabel: this.translocoService.translate('common.no'),
        rejectButtonStyleClass: 'p-button-text',
        accept: () => {
          this.appSettingsService.importSettings(payload).subscribe({
            next: () => {
              this.backupsActivityService.setAppSettingsActivity({
                action: 'import',
                fileName: file.name,
                timestamp: new Date().toISOString()
              });
              this.refreshActivitySnapshots();
              this.showMessage('success', 'settingsApp.transfer.importSuccess', 'settingsApp.transfer.importSuccessDetail');
            },
            error: () => {
              this.showMessage('error', 'common.error', 'settingsApp.transfer.importError');
            }
          });
        }
      });
    } catch {
      this.showMessage('error', 'settingsApp.transfer.importInvalid', 'settingsApp.transfer.importInvalidDetail');
    }
  }

  backupLibrarySidecars(): void {
    if (this.selectedBackupLibraryId === null || this.isSidecarBackupRunning) {
      return;
    }

    const selectedLibrary = this.libraries.find((library) => library.id === this.selectedBackupLibraryId);
    if (!selectedLibrary) {
      return;
    }

    this.isSidecarBackupRunning = true;
    this.sidecarBackupProgressService.start();

    this.sidecarService.backupLibrarySidecars(this.selectedBackupLibraryId).pipe(
      finalize(() => {
        this.isSidecarBackupRunning = false;
        this.sidecarBackupProgressService.clear();
      })
    ).subscribe({
      next: (response) => {
        this.loadSidecarHistory(this.selectedBackupLibraryId ?? selectedLibrary.id ?? null);

        if (response.failed > 0) {
          this.toastError(this.translocoService.translate('common.error'), this.translocoService.translate(
              response.exported > 0
                ? 'settingsMeta.persistence.sidecarBackupPartial'
                : 'settingsMeta.persistence.sidecarBackupFailureDetail',
              {
                count: response.exported,
                attempted: response.attempted,
                failed: response.failed,
                error: response.firstError || this.translocoService.translate('settingsMeta.persistence.sidecarBackupUnknownError')
              }
            ), 3000);
          return;
        }

        const detailKey = (response.pruned && response.pruned > 0)
          ? 'settingsMeta.persistence.sidecarBackupSuccessWithPruned'
          : 'settingsMeta.persistence.sidecarBackupSuccess';

        this.messageService.add({
          severity: 'success',
          summary: this.translocoService.translate('common.success'),
          detail: this.translocoService.translate(detailKey, {
            count: response.exported,
            pruned: response.pruned
          })
        });
      },
      error: () => {
        this.showMessage('error', 'common.error', 'settingsMeta.persistence.sidecarBackupError');
      }
    });
  }

  async copyDatabaseExportCommand(): Promise<void> {
    const copied = await this.copyText(this.getDatabaseExportCommand());

    if (!copied) {
      return;
    }

    this.backupsActivityService.setDatabaseActivity({
      action: 'export',
      outputPath: this.getDatabaseOutputPath(),
      timestamp: new Date().toISOString()
    });
    this.refreshActivitySnapshots();
    this.recordDatabaseHelperAction(
      'DATABASE_BACKUP_COMMAND_COPIED',
      `Prepared database backup command for ${this.getDatabaseOutputPath()}. The command creates the target folder before writing the SQL dump.`
    );
    this.showMessage('success', 'settingsBackups.messages.exportCommandCopied', 'settingsBackups.messages.exportCommandCopiedDetail');
  }

  runRestorePreflight(): void {
    this.restoreChecks = [
      {
        labelKey: 'settingsBackups.databaseRestore.checks.sqlPath',
        detail: this.restoreSqlPath.trim()
          ? this.restoreSqlPath.trim()
          : this.translocoService.translate('settingsBackups.databaseRestore.missingSqlPath'),
        passed: !!this.restoreSqlPath.trim() && this.packageFilesConfirmed
      },
      {
        labelKey: 'settingsBackups.databaseRestore.checks.media',
        detail: this.translocoService.translate(
          this.libraryMediaCopied
            ? 'settingsBackups.databaseRestore.details.mediaReady'
            : 'settingsBackups.databaseRestore.details.mediaMissing'
        ),
        passed: this.libraryMediaCopied
      },
      {
        labelKey: 'settingsBackups.databaseRestore.checks.appData',
        detail: this.restoreAppDataPath.trim()
          ? this.restoreAppDataPath.trim()
          : this.translocoService.translate('settingsBackups.databaseRestore.missingAppDataPath'),
        passed: !!this.restoreAppDataPath.trim() && this.appDataConfirmed
      },
      {
        labelKey: 'settingsBackups.databaseRestore.checks.compose',
        detail: this.restoreComposePath.trim()
          ? this.restoreComposePath.trim()
          : this.translocoService.translate('settingsBackups.databaseRestore.missingComposePath'),
        passed: !!this.restoreComposePath.trim() && this.deploymentConfigConfirmed
      },
      {
        labelKey: 'settingsBackups.databaseRestore.checks.version',
        detail: this.translocoService.translate(
          this.versionConfirmed
            ? 'settingsBackups.databaseRestore.details.versionReady'
            : 'settingsBackups.databaseRestore.details.versionBlocked'
        ),
        passed: this.versionConfirmed
      },
      {
        labelKey: 'settingsBackups.databaseRestore.checks.maintenance',
        detail: this.translocoService.translate(
          this.maintenanceReady
            ? 'settingsBackups.databaseRestore.details.maintenanceReady'
            : 'settingsBackups.databaseRestore.details.maintenanceBlocked'
        ),
        passed: this.maintenanceReady
      },
      {
        labelKey: 'settingsBackups.databaseRestore.checks.freeSpace',
        detail: this.availableSpaceGb === null
          ? this.translocoService.translate('settingsBackups.databaseRestore.missingFreeSpace')
          : this.translocoService.translate('settingsBackups.databaseRestore.details.freeSpace', {
            value: this.availableSpaceGb
          }),
        passed: this.availableSpaceGb !== null && this.availableSpaceGb >= 15
      }
    ];

    this.restoreReady = this.restoreChecks.every((check) => check.passed);

    this.backupsActivityService.setDatabaseActivity({
      action: this.restoreReady ? 'restore-preflight-passed' : 'restore-preflight-blocked',
      outputPath: this.restoreSqlPath.trim() || this.translocoService.translate('settingsBackups.common.notRecorded'),
      timestamp: new Date().toISOString()
    });
    this.refreshActivitySnapshots();
    this.recordDatabaseHelperAction(
      this.restoreReady ? 'DATABASE_RESTORE_PREFLIGHT_PASSED' : 'DATABASE_RESTORE_PREFLIGHT_BLOCKED',
      this.buildRestorePreflightAuditDescription(this.restoreReady)
    );

    this.messageService.add({
      severity: this.restoreReady ? 'success' : 'warn',
      summary: this.translocoService.translate(this.restoreReady ? 'common.success' : 'settingsBackups.databaseRestore.preflightBlocked'),
      detail: this.translocoService.translate(
        this.restoreReady
          ? 'settingsBackups.databaseRestore.preflightPassedDetail'
          : 'settingsBackups.databaseRestore.preflightBlockedDetail'
      )
    });
  }

  resetRestorePreflight(): void {
    this.packageFilesConfirmed = false;
    this.libraryMediaCopied = false;
    this.appDataConfirmed = false;
    this.deploymentConfigConfirmed = false;
    this.versionConfirmed = false;
    this.maintenanceReady = false;
    this.restoreChecks = [];
    this.restoreReady = false;
  }

  async copyDatabaseRestoreCommand(): Promise<void> {
    if (!this.restoreReady) {
      return;
    }

    const copied = await this.copyText(this.getDatabaseRestoreCommand());

    if (!copied) {
      return;
    }

    this.backupsActivityService.setDatabaseActivity({
      action: 'restore-command',
      outputPath: this.restoreSqlPath,
      timestamp: new Date().toISOString()
    });
    this.refreshActivitySnapshots();
    this.recordDatabaseHelperAction(
      'DATABASE_RESTORE_COMMAND_COPIED',
      `Prepared database restore command for ${this.restoreSqlPath.trim() || this.translocoService.translate('settingsBackups.common.notRecorded')}.`
    );
    this.showMessage('success', 'settingsBackups.messages.restoreCommandCopied', 'settingsBackups.messages.restoreCommandCopiedDetail');
  }

  private refreshActivitySnapshots(): void {
    this.appSettingsActivity = this.backupsActivityService.getAppSettingsActivity();
    this.databaseActivity = this.backupsActivityService.getDatabaseActivity();
  }

  private loadLibraries(): void {
    this.libraryService.libraryState$.pipe(
      filter((state) => state.loaded),
      take(1)
    ).subscribe({
      next: (state) => {
        this.libraries = state.libraries ?? [];
        if (this.selectedBackupLibraryId === null && this.libraries.length > 0) {
          this.selectedBackupLibraryId = this.libraries[0].id ?? null;
        }
        if (this.canLoadSidecarHistory) {
          this.loadSidecarHistory(this.selectedBackupLibraryId);
        }
      }
    });
  }

  onSelectedBackupLibraryChange(libraryId: number | null): void {
    this.selectedBackupLibraryId = libraryId;
    this.loadSidecarHistory(libraryId);
  }

  getLatestSidecarHistoryEntry(): SidecarBackupHistoryEntry | null {
    return this.sidecarHistory[0] ?? null;
  }

  getSelectedBackupLibraryName(): string {
    return this.libraries.find((library) => library.id === this.selectedBackupLibraryId)?.name
      ?? this.translocoService.translate('settingsBackups.common.notRecorded');
  }

  getSidecarHistoryStatusLabelKey(entry: SidecarBackupHistoryEntry): string {
    switch (entry.status) {
      case 'FAILED':
        return 'settingsBackups.status.failed';
      case 'PARTIAL':
        return 'settingsBackups.status.partial';
      default:
        return 'settingsBackups.status.done';
    }
  }

  private loadSidecarHistory(libraryId: number | null): void {
    if (!this.canLoadSidecarHistory) {
      this.sidecarHistory = [];
      this.sidecarHistoryLoadFailed = false;
      this.isSidecarHistoryLoading = false;
      return;
    }

    if (libraryId === null) {
      this.sidecarHistory = [];
      this.sidecarHistoryLoadFailed = false;
      this.isSidecarHistoryLoading = false;
      return;
    }

    this.isSidecarHistoryLoading = true;
    this.sidecarHistoryLoadFailed = false;

    this.sidecarService.getBackupHistory(libraryId, 10).subscribe({
      next: (history) => {
        this.sidecarHistory = history;
        this.isSidecarHistoryLoading = false;
      },
      error: () => {
        this.sidecarHistory = [];
        this.sidecarHistoryLoadFailed = true;
        this.isSidecarHistoryLoading = false;
      }
    });
  }

  private buildDefaultBackupFileName(): string {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const hours = String(now.getHours()).padStart(2, '0');
    const minutes = String(now.getMinutes()).padStart(2, '0');
    const seconds = String(now.getSeconds()).padStart(2, '0');

    return `fable_backup_${year}${month}${day}_${hours}${minutes}${seconds}.sql`;
  }

  private getNormalizedBackupDirectory(): string {
    const trimmedDirectory = this.backupDirectory.trim();
    return (trimmedDirectory || '$HOME/fable-backups').replace(/\/$/, '');
  }

  private getStatusLabelKey(tone: StatusTone): string {
    switch (tone) {
      case 'done':
        return 'settingsBackups.status.done';
      case 'partial':
        return 'settingsBackups.status.partial';
      case 'fail':
        return 'settingsBackups.status.failed';
      default:
        return 'settingsBackups.status.idle';
    }
  }

  private buildRestorePreflightAuditDescription(passed: boolean): string {
    const sqlPath = this.restoreSqlPath.trim() || this.translocoService.translate('settingsBackups.common.notRecorded');
    const blockedChecks = this.restoreChecks
      .filter((check) => !check.passed)
      .map((check) => this.translocoService.translate(check.labelKey));

    if (passed) {
      return `Database restore pre-flight passed for ${sqlPath}. App data path: ${this.restoreAppDataPath.trim() || this.translocoService.translate('settingsBackups.common.notRecorded')}. Deployment config: ${this.restoreComposePath.trim() || this.translocoService.translate('settingsBackups.common.notRecorded')}. Free space: ${this.availableSpaceGb ?? this.translocoService.translate('settingsBackups.common.notRecorded')} GB.`;
    }

    return `Database restore pre-flight blocked for ${sqlPath}. Blocked checks: ${blockedChecks.join(', ')}.`;
  }

  private showMessage(severity: 'success' | 'error', summaryKey: string, detailKey: string): void {
    this.messageService.add({
      severity,
      summary: this.translocoService.translate(summaryKey),
      detail: this.translocoService.translate(detailKey)
    });
  }

  private async copyText(text: string): Promise<boolean> {
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch {
        // Fall through to the document command fallback for browsers that block async clipboard writes.
      }
    }

    if (this.copyTextWithDocumentCommand(text)) {
      return true;
    }

    this.showMessage('error', 'common.error', 'settingsBackups.messages.clipboardUnavailable');
    return false;
  }

  private copyTextWithDocumentCommand(text: string): boolean {
    if (typeof document === 'undefined' || typeof document.execCommand !== 'function') {
      return false;
    }

    const textArea = document.createElement('textarea');
    textArea.value = text;
    textArea.setAttribute('readonly', 'true');
    textArea.style.position = 'fixed';
    textArea.style.opacity = '0';
    textArea.style.pointerEvents = 'none';

    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    textArea.setSelectionRange(0, textArea.value.length);

    try {
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      document.body.removeChild(textArea);
    }
  }

  private recordDatabaseHelperAction(action: DatabaseHelperAuditAction, description: string): void {
    this.auditLogService.recordDatabaseHelperAction(action, description).subscribe({
      error: () => undefined
    });
  }
}