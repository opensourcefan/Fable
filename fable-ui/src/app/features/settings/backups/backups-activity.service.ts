import {inject, Injectable} from '@angular/core';
import {LocalStorageService} from '../../../shared/service/local-storage.service';

export interface AppSettingsBackupActivity {
  action: 'export' | 'import';
  fileName: string;
  timestamp: string;
}

export interface SidecarBackupActivity {
  libraryId: number;
  libraryName: string;
  attempted: number;
  exported: number;
  failed: number;
  timestamp: string;
}

export interface DatabaseBackupActivity {
  action: 'export' | 'restore-command' | 'restore-preflight-passed' | 'restore-preflight-blocked';
  outputPath: string;
  timestamp: string;
}

export interface DatabaseBackupConfig {
  containerName?: string;
  databaseName?: string;
  databaseUser?: string;
  backupDirectory?: string;
}

@Injectable({providedIn: 'root'})
export class BackupsActivityService {
  private readonly localStorageService = inject(LocalStorageService);

  private readonly appSettingsKey = 'settingsBackupsAppSettingsActivity';
  private readonly sidecarKey = 'settingsBackupsSidecarActivity';
  private readonly databaseKey = 'settingsBackupsDatabaseActivity';
  private readonly databaseConfigKey = 'settingsBackupsDatabaseConfig';

  getAppSettingsActivity(): AppSettingsBackupActivity | null {
    return this.localStorageService.get<AppSettingsBackupActivity>(this.appSettingsKey);
  }

  setAppSettingsActivity(activity: AppSettingsBackupActivity): void {
    this.localStorageService.set(this.appSettingsKey, activity);
  }

  getSidecarActivity(): SidecarBackupActivity | null {
    return this.localStorageService.get<SidecarBackupActivity>(this.sidecarKey);
  }

  setSidecarActivity(activity: SidecarBackupActivity): void {
    this.localStorageService.set(this.sidecarKey, activity);
  }

  getDatabaseActivity(): DatabaseBackupActivity | null {
    return this.localStorageService.get<DatabaseBackupActivity>(this.databaseKey);
  }

  setDatabaseActivity(activity: DatabaseBackupActivity): void {
    this.localStorageService.set(this.databaseKey, activity);
  }

  getDatabaseConfig(): DatabaseBackupConfig | null {
    return this.localStorageService.get<DatabaseBackupConfig>(this.databaseConfigKey);
  }

  setDatabaseConfig(config: DatabaseBackupConfig): void {
    this.localStorageService.set(this.databaseConfigKey, config);
  }
}