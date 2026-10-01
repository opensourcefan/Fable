import {ComponentFixture, TestBed} from '@angular/core/testing';
import {TranslocoTestingModule} from '@jsverse/transloco';
import {ConfirmationService, MessageService} from 'primeng/api';
import {of} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {LibraryService} from '../../book/service/library.service';
import {SidecarService} from '../../metadata/service/sidecar.service';
import {AppSettingsService} from '../../../shared/service/app-settings.service';
import {LocalStorageService} from '../../../shared/service/local-storage.service';
import {SidecarBackupProgressService} from '../../../shared/service/sidecar-backup-progress.service';
import {UserService} from '../user-management/user.service';
import {AuditLogService} from '../audit-logs/audit-log.service';
import {BackupsComponent} from './backups.component';

describe('BackupsComponent', () => {
  let fixture: ComponentFixture<BackupsComponent>;
  let component: BackupsComponent;
  let storage: Record<string, unknown>;
  let appSettingsServiceMock: {exportSettings: ReturnType<typeof vi.fn>; importSettings: ReturnType<typeof vi.fn>};
  let sidecarServiceMock: {backupLibrarySidecars: ReturnType<typeof vi.fn>; getBackupHistory: ReturnType<typeof vi.fn>};
  let auditLogServiceMock: {recordDatabaseHelperAction: ReturnType<typeof vi.fn>};
  let clipboardWriteText: ReturnType<typeof vi.fn>;
  let execCommandMock: ReturnType<typeof vi.fn>;
  let messageServiceAdd: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    storage = {};
    clipboardWriteText = vi.fn().mockResolvedValue(undefined);
    execCommandMock = vi.fn(() => true);
    messageServiceAdd = vi.fn();

    Object.defineProperty(globalThis, 'ResizeObserver', {
      configurable: true,
      writable: true,
      value: class {
        observe() {
          return undefined;
        }
        unobserve() {
          return undefined;
        }
        disconnect() {
          return undefined;
        }
      }
    });

    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {writeText: clipboardWriteText}
    });

    Object.defineProperty(document, 'execCommand', {
      configurable: true,
      value: execCommandMock
    });

    appSettingsServiceMock = {
      exportSettings: vi.fn(() => of('fable-settings-2026-04-14T12-00-00-000Z.json')),
      importSettings: vi.fn(() => of(void 0))
    };

    sidecarServiceMock = {
      backupLibrarySidecars: vi.fn(() => of({
        message: 'ok',
        attempted: 12,
        exported: 12,
        failed: 0,
        firstError: ''
      })),
      getBackupHistory: vi.fn(() => of([{
        status: 'COMPLETED',
        attempted: 12,
        exported: 12,
        failed: 0,
        firstError: null,
        description: 'Backed up 12 sidecars.',
        username: 'admin',
        createdAt: '2026-04-14T12:00:00'
      }]))
    };

    auditLogServiceMock = {
      recordDatabaseHelperAction: vi.fn(() => of(void 0))
    };

    TestBed.configureTestingModule({
      imports: [
        BackupsComponent,
        TranslocoTestingModule.forRoot({langs: {}})
      ],
      providers: [
        {
          provide: UserService,
          useValue: {
            userState$: of({
              loaded: true,
              user: {
                permissions: {
                  admin: true,
                  canManageGlobalPreferences: true,
                  canManageMetadataConfig: true
                }
              }
            })
          }
        },
        {provide: AppSettingsService, useValue: appSettingsServiceMock},
        {
          provide: LibraryService,
          useValue: {
            libraryState$: of({
              loaded: true,
              libraries: [{id: 1, name: 'Main Library'}]
            })
          }
        },
        {provide: SidecarService, useValue: sidecarServiceMock},
        {provide: AuditLogService, useValue: auditLogServiceMock},
        {
          provide: LocalStorageService,
          useValue: {
            get: vi.fn((key: string) => (storage[key] as unknown) ?? null),
            set: vi.fn((key: string, value: unknown) => {
              storage[key] = value;
            }),
            remove: vi.fn()
          }
        },
        {
          provide: SidecarBackupProgressService,
          useValue: {
            start: vi.fn(),
            clear: vi.fn()
          }
        },
        {
          provide: ConfirmationService,
          useValue: {
            confirm: vi.fn((options: {accept?: () => void}) => options.accept?.())
          }
        },
        {
          provide: MessageService,
          useValue: {
            add: messageServiceAdd
          }
        }
      ]
    });

    fixture = TestBed.createComponent(BackupsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('records app-settings export activity', () => {
    component.exportSettings();

    expect(appSettingsServiceMock.exportSettings).toHaveBeenCalledOnce();
    expect(storage['settingsBackupsAppSettingsActivity']).toMatchObject({
      action: 'export',
      fileName: 'fable-settings-2026-04-14T12-00-00-000Z.json'
    });
    expect(messageServiceAdd).toHaveBeenCalled();
  });

  it('backs up sidecars and records the selected library', () => {
    component.selectedBackupLibraryId = 1;

    component.backupLibrarySidecars();

    expect(sidecarServiceMock.backupLibrarySidecars).toHaveBeenCalledWith(1);
    expect(sidecarServiceMock.getBackupHistory).toHaveBeenCalledWith(1, 10);
    expect(component.sidecarHistory[0]).toMatchObject({
      exported: 12,
      failed: 0
    });
  });

  it('records a failed sidecar backup when no files were exported', () => {
    sidecarServiceMock.backupLibrarySidecars.mockReturnValueOnce(of({
      message: 'failed',
      attempted: 5,
      exported: 0,
      failed: 5,
      firstError: 'Disk full'
    }));
    sidecarServiceMock.getBackupHistory.mockReturnValueOnce(of([{
      status: 'FAILED',
      attempted: 5,
      exported: 0,
      failed: 5,
      firstError: 'Disk full',
      description: 'Backup failed.',
      username: 'admin',
      createdAt: '2026-04-14T12:05:00'
    }]));
    component.selectedBackupLibraryId = 1;

    component.backupLibrarySidecars();

    expect(component.sidecarHistory[0]).toMatchObject({
      exported: 0,
      failed: 5
    });
    expect(component.getSidecarStatusTone()).toBe('fail');
  });

  it('builds the export command with mkdir -p for the destination folder and proper password syntax', () => {
    component.backupDirectory = '/srv/fable/backups';
    component.backupFileName = 'fable_backup.sql';
    component.dbContainerName = 'mariadb';
    component.dbUser = 'booklore';
    component.dbName = 'booklore';
    component.dbPassword = 'secret';

    expect(component.getDatabaseExportCommand()).toBe(
      'mkdir -p "/srv/fable/backups" && docker exec mariadb mariadb-dump --single-transaction --quick --no-tablespaces -u booklore -p"secret" booklore > "/srv/fable/backups/fable_backup.sql"'
    );
  });

  it('builds the export command with default environment variable placeholder when password is blank', () => {
    component.backupDirectory = '/srv/fable/backups';
    component.backupFileName = 'fable_backup.sql';
    component.dbContainerName = 'mariadb';
    component.dbUser = 'fable';
    component.dbName = 'fable';
    component.dbPassword = '';

    expect(component.getDatabaseExportCommand()).toBe(
      'mkdir -p "/srv/fable/backups" && docker exec mariadb mariadb-dump --single-transaction --quick --no-tablespaces -u fable -p"$DB_PASSWORD" fable > "/srv/fable/backups/fable_backup.sql"'
    );
  });

  it('saves database configuration to local storage on change', () => {
    component.dbContainerName = 'custom-db';
    component.dbName = 'booklore';
    component.dbUser = 'booklore';
    component.backupDirectory = '/custom/backups';
    component.onDbConfigChange();

    expect(storage['settingsBackupsDatabaseConfig']).toEqual({
      containerName: 'custom-db',
      databaseName: 'booklore',
      databaseUser: 'booklore',
      backupDirectory: '/custom/backups'
    });
  });

  it('keeps available free space empty until a real number is entered', () => {
    component.onAvailableSpaceChange('');
    expect(component.availableSpaceGb).toBeNull();

    component.onAvailableSpaceChange('16');
    expect(component.availableSpaceGb).toBe(16);
  });

  it('copies the restore command only after a successful pre-flight', async () => {
    component.restoreSqlPath = '/tmp/fable_backup.sql';
    component.restoreAppDataPath = '/srv/fable/data';
    component.restoreComposePath = '/opt/fable/docker-compose.yml';
    component.availableSpaceGb = 42;
    component.packageFilesConfirmed = true;
    component.libraryMediaCopied = true;
    component.appDataConfirmed = true;
    component.deploymentConfigConfirmed = true;
    component.versionConfirmed = true;
    component.maintenanceReady = true;

    component.runRestorePreflight();
    await component.copyDatabaseRestoreCommand();

    expect(component.restoreReady).toBe(true);
    expect(clipboardWriteText).toHaveBeenCalledWith(
      'docker exec -i mariadb mariadb -u fable -p"$DB_PASSWORD" fable < "/tmp/fable_backup.sql"'
    );
    expect(auditLogServiceMock.recordDatabaseHelperAction).toHaveBeenCalledWith(
      'DATABASE_RESTORE_PREFLIGHT_PASSED',
      expect.stringContaining('/tmp/fable_backup.sql')
    );
    expect(auditLogServiceMock.recordDatabaseHelperAction).toHaveBeenCalledWith(
      'DATABASE_RESTORE_COMMAND_COPIED',
      'Prepared database restore command for /tmp/fable_backup.sql.'
    );
    expect(storage['settingsBackupsDatabaseActivity']).toMatchObject({
      action: 'restore-command',
      outputPath: '/tmp/fable_backup.sql'
    });
  });

  it('falls back to document copy when the async clipboard API is unavailable', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined
    });
    component.backupDirectory = '/srv/fable/backups';
    component.backupFileName = 'fable_backup.sql';

    await component.copyDatabaseExportCommand();

    expect(execCommandMock).toHaveBeenCalledWith('copy');
    expect(auditLogServiceMock.recordDatabaseHelperAction).toHaveBeenCalledWith(
      'DATABASE_BACKUP_COMMAND_COPIED',
      expect.stringContaining('/srv/fable/backups/fable_backup.sql')
    );
    expect(storage['settingsBackupsDatabaseActivity']).toMatchObject({
      action: 'export',
      outputPath: '/srv/fable/backups/fable_backup.sql'
    });
  });
});