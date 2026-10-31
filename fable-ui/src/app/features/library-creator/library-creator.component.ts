import {Component, inject, OnInit} from '@angular/core';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {FailureNotificationService} from '../../shared/service/failure-notification.service';
import {Router} from '@angular/router';
import {LibraryService} from '../book/service/library.service';
import {LibraryHealthService} from '../book/service/library-health.service';
import {FormsModule} from '@angular/forms';
import {InputText} from 'primeng/inputtext';
import {Library, MetadataSource, OrganizationMode, DirectoryTagDepth} from '../book/model/library.model';
import {BookType} from '../book/model/book.model';
import {ToggleSwitch} from 'primeng/toggleswitch';
import {Tooltip} from 'primeng/tooltip';
import {IconPickerService, IconSelection} from '../../shared/service/icon-picker.service';
import {Button} from 'primeng/button';
import {IconDisplayComponent} from '../../shared/components/icon-display/icon-display.component';
import {DialogLauncherService} from '../../shared/services/dialog-launcher.service';
import {switchMap} from 'rxjs/operators';
import {map, take} from 'rxjs';
import {CdkDragDrop, DragDropModule, moveItemInArray} from '@angular/cdk/drag-drop';
import {Checkbox} from 'primeng/checkbox';
import {Select} from 'primeng/select';
import {TranslocoDirective, TranslocoPipe, TranslocoService} from '@jsverse/transloco';

type LibraryCreatorMode = 'create' | 'edit' | 'edit-settings' | 'edit-directories';

@Component({
  selector: 'app-library-creator',
  standalone: true,
  templateUrl: './library-creator.component.html',
  imports: [FormsModule, InputText, ToggleSwitch, Tooltip, Button, IconDisplayComponent, DragDropModule, Checkbox, Select, TranslocoDirective, TranslocoPipe],
  styleUrl: './library-creator.component.scss'
})
export class LibraryCreatorComponent implements OnInit {
  private readonly largeLibraryThreshold = 500;

  chosenLibraryName = '';
  folders: string[] = [];
  originalFolders: string[] = [];
  foldersToDelete = new Set<string>();
  inaccessibleFolders = new Set<string>();
  selectedIcon: IconSelection | null = null;

  mode: LibraryCreatorMode = 'create';
  library!: Library | undefined;
  editModeLibraryName = '';
  watch = false;
  tagByDirectory = false;
  directoryTagDepth: DirectoryTagDepth = 'LAST_ONLY';
  directoryTagDepthOptions: {label: string, value: string}[] = [];
  formatPriority: {type: BookType, label: string}[] = [];
  allowAllFormats = true;
  selectedAllowedFormats = new Set<BookType>();
  formatCounts: Record<string, number> = {};
  scanningDirectory: string | null = null;
  metadataSource: MetadataSource = 'EMBEDDED';
  organizationMode: OrganizationMode = 'BOOK_PER_FILE';

  metadataSourceOptions: {label: string, value: string}[] = [];
  organizationModeOptions: {label: string, value: string}[] = [];
  isSubmitting = false;
  submissionState: 'scanning' | 'creating' | 'updating' | 'scanningDirectories' | 'handoff' | null = null;

  readonly allBookFormats: {type: BookType, label: string}[] = [
    {type: 'EPUB', label: 'EPUB'},
    {type: 'PDF', label: 'PDF'},
    {type: 'CBX', label: 'CBX (CBZ/CBR/CB7)'},
    {type: 'MOBI', label: 'MOBI'},
    {type: 'AZW3', label: 'AZW3'},
    {type: 'FB2', label: 'FB2'},
    {type: 'AUDIOBOOK', label: 'Audiobook'}
  ];

  private dialogLauncherService = inject(DialogLauncherService);
  private dynamicDialogRef = inject(DynamicDialogRef);
  private dynamicDialogConfig = inject(DynamicDialogConfig);
  private libraryService = inject(LibraryService);
  private libraryHealthService = inject(LibraryHealthService);
  private messageService = inject(MessageService);
  private failureNotifications = inject(FailureNotificationService);
  private router = inject(Router);
  private iconPicker = inject(IconPickerService);
  private readonly t = inject(TranslocoService);


  private toastError(summary: string, detail: string): void {
    this.failureNotifications.reportSafe(summary, detail);
    this.messageService.add({severity: 'error', summary, detail});
  }

  ngOnInit(): void {
    this.metadataSourceOptions = [
      {label: this.t.translate('libraryCreator.creator.metadataSourceEmbedded'), value: 'EMBEDDED'},
      {label: this.t.translate('libraryCreator.creator.metadataSourceSidecar'), value: 'SIDECAR'},
      {label: this.t.translate('libraryCreator.creator.metadataSourcePreferSidecar'), value: 'PREFER_SIDECAR'},
      {label: this.t.translate('libraryCreator.creator.metadataSourcePreferEmbedded'), value: 'PREFER_EMBEDDED'},
      {label: this.t.translate('libraryCreator.creator.metadataSourceNone'), value: 'NONE'}
    ];
    this.directoryTagDepthOptions = [
      {label: this.t.translate('libraryCreator.creator.directoryTagDepthLastOnly'), value: 'LAST_ONLY'},
      {label: this.t.translate('libraryCreator.creator.directoryTagDepthAllSegments'), value: 'ALL_SEGMENTS'}
    ];
    this.initializeFormatPriority();
    this.initializeAllowedFormats();
    this.initializeOrganizationModeOptions();

    const data = this.dynamicDialogConfig?.data;
    this.mode = (data?.mode as LibraryCreatorMode | undefined) ?? 'create';

    if (this.isEditMode()) {
      this.library = this.libraryService.findLibraryById(data.libraryId);
      if (this.library) {
        const {name, icon, iconType, paths, watch, formatPriority, allowedFormats} = this.library;
        this.chosenLibraryName = name;
        this.editModeLibraryName = name;

        if (icon != null && iconType) {
          if (iconType === 'CUSTOM_SVG') {
            this.selectedIcon = {type: 'CUSTOM_SVG', value: icon};
          } else {
            const value = icon.slice(0, 6) === 'pi pi-' ? icon : `pi pi-${icon}`;
            this.selectedIcon = {type: 'PRIME_NG', value: value};
          }
        }

        this.watch = watch;
        this.tagByDirectory = this.library.tagByDirectory ?? false;
        this.directoryTagDepth = this.library.directoryTagDepth ?? 'LAST_ONLY';
        if (formatPriority && formatPriority.length > 0) {
          this.formatPriority = formatPriority.map(type =>
            this.allBookFormats.find(f => f.type === type)!
          ).filter(f => f !== undefined);
          const existingTypes = new Set(formatPriority);
          this.allBookFormats.forEach(f => {
            if (!existingTypes.has(f.type)) {
              this.formatPriority.push(f);
            }
          });
        }

        if (allowedFormats && allowedFormats.length > 0) {
          this.allowAllFormats = false;
          this.selectedAllowedFormats = new Set(allowedFormats);
        } else {
          this.allowAllFormats = true;
          this.selectedAllowedFormats = new Set(this.allBookFormats.map(f => f.type));
        }

        if (this.library.metadataSource) {
          this.metadataSource = this.library.metadataSource;
        }

        if (this.library.organizationMode) {
          this.organizationMode = this.library.organizationMode;
          if (this.library.organizationMode === 'AUTO_DETECT') {
            this.organizationModeOptions.push({
              label: this.t.translate('libraryCreator.creator.organizationModeAutoDetect'),
              value: 'AUTO_DETECT'
            });
          }
        }

        this.libraryService.getBookCountsByFormat(this.library.id!).subscribe(counts => {
          this.formatCounts = counts;
        });

        this.folders = paths.map(path => path.path).sort((a, b) => a.localeCompare(b));
        this.originalFolders = [...this.folders];
        const initialInaccessible = paths.filter(p => p.accessible === false).map(p => p.path);
        this.inaccessibleFolders = new Set(initialInaccessible);
        if (this.library.id != null) {
          this.libraryHealthService.getInaccessiblePaths$(this.library.id, initialInaccessible).pipe(take(1)).subscribe(pathsList => {
            this.inaccessibleFolders = new Set([...initialInaccessible, ...pathsList]);
          });
        }
      }
    }
  }

  private initializeFormatPriority(): void {
    this.formatPriority = [...this.allBookFormats];
  }

  private initializeOrganizationModeOptions(): void {
    this.organizationModeOptions = [
      {label: this.t.translate('libraryCreator.creator.organizationModeBookPerFile'), value: 'BOOK_PER_FILE'},
      {label: this.t.translate('libraryCreator.creator.organizationModeBookPerFolder'), value: 'BOOK_PER_FOLDER'}
    ];
  }

  private initializeAllowedFormats(): void {
    this.selectedAllowedFormats = new Set(this.allBookFormats.map(f => f.type));
  }

  onAllowAllFormatsChange(): void {
    if (this.allowAllFormats) {
      this.selectedAllowedFormats = new Set(this.allBookFormats.map(f => f.type));
    }
  }

  onFormatCheckboxChange(formatType: BookType, checked: boolean): void {
    if (checked) {
      this.selectedAllowedFormats.add(formatType);
    } else {
      this.selectedAllowedFormats.delete(formatType);
    }
    this.allowAllFormats = this.selectedAllowedFormats.size === this.allBookFormats.length;
  }

  isFormatSelected(formatType: BookType): boolean {
    return this.selectedAllowedFormats.has(formatType);
  }

  getFormatWarning(formatType: BookType): string | null {
    if (this.mode !== 'edit') return null;
    const count = this.formatCounts[formatType];
    if (count && count > 0 && !this.selectedAllowedFormats.has(formatType)) {
      return this.t.translate('libraryCreator.creator.formatWarning', {count});
    }
    return null;
  }

  hasAnyFormatWarning(): boolean {
    if (this.mode !== 'edit') return false;
    return this.allBookFormats.some(f => this.getFormatWarning(f.type) !== null);
  }

  closeDialog(): void {
    if (this.isSubmitting) {
      return;
    }

    this.dynamicDialogRef.close();
  }

  openDirectoryPicker(): void {
    const activeFolders = this.folders.filter(f => !this.isFolderMarkedForDeletion(f));
    const ref = this.dialogLauncherService.openDirectoryPickerDialog({
      existingFolders: [...activeFolders]
    });
    ref?.onClose.subscribe((selectedFolders: string[] | null) => {
      if (selectedFolders && selectedFolders.length > 0) {
        selectedFolders.forEach(folder => {
          if (this.isFolderMarkedForDeletion(folder)) {
            this.foldersToDelete.delete(folder);
          } else if (!this.folders.includes(folder)) {
            this.addFolder(folder);
          }
        });
        this.folders.sort((a, b) => a.localeCompare(b));
      }
    });
  }

  openIconPicker(): void {
    this.iconPicker.open().subscribe(icon => {
      if (icon) {
        this.selectedIcon = icon;
      }
    });
  }

  addFolder(folder: string): void {
    this.folders.push(folder);
  }

  removeFolder(index: number, folder: string): void {
    if (this.isDirectoryManagementMode() && this.originalFolders.includes(folder)) {
      if (this.foldersToDelete.has(folder)) {
        this.foldersToDelete.delete(folder);
      } else {
        this.foldersToDelete.add(folder);
      }
    } else {
      this.folders.splice(index, 1);
    }
  }

  isFolderMarkedForDeletion(folder: string): boolean {
    return this.foldersToDelete.has(folder);
  }

  isFolderInaccessible(folder: string): boolean {
    return this.inaccessibleFolders.has(folder);
  }

  getInaccessibleActiveFolders(): string[] {
    return this.folders.filter(f => this.isFolderInaccessible(f) && !this.isFolderMarkedForDeletion(f));
  }

  rescanDirectory(path: string): void {
    if (this.isSubmitting || !this.library?.id) return;
    this.scanningDirectory = path;
    this.libraryService.scanLibraryDirectoriesForNewFiles(this.library.id as number, [path]).subscribe({
      complete: () => {
        this.scanningDirectory = null;
        this.messageService.add({
          severity: 'success',
          summary: this.t.translate('libraryCreator.creator.toast.updatedSummary'),
          detail: this.t.translate('libraryCreator.creator.toast.rescannedDirectoryDetail', {path})
        });
      },
      error: (e) => {
        this.scanningDirectory = null;
        this.messageService.add({
          severity: 'warn',
          summary: this.t.translate('libraryCreator.creator.toast.updatedSummary'),
          detail: this.t.translate('libraryCreator.creator.toast.rescanDirectoryFailedDetail', {path})
        });
        console.error(e);
      }
    });
  }

  clearSelectedIcon(): void {
    this.selectedIcon = null;
  }

  isLibraryDetailsValid(): boolean {
    return !!this.chosenLibraryName.trim();
  }

  isDirectorySelectionValid(): boolean {
    const activeFolders = this.folders.filter(f => !this.isFolderMarkedForDeletion(f));
    return activeFolders.length > 0;
  }

  isCreateMode(): boolean {
    return this.mode === 'create';
  }

  isEditMode(): boolean {
    return !this.isCreateMode();
  }

  isDirectoryManagementMode(): boolean {
    return this.mode === 'edit-directories';
  }

  isSettingsMode(): boolean {
    return this.mode === 'edit-settings';
  }

  showLibraryDetailsSection(): boolean {
    return !this.isDirectoryManagementMode();
  }

  showFoldersSection(): boolean {
    return this.isCreateMode() || this.isDirectoryManagementMode();
  }

  showOptionsSection(): boolean {
    return !this.isDirectoryManagementMode();
  }

  private switchWorkflowDialog(openNext: () => void): void {
    this.dynamicDialogRef.onDestroy.pipe(take(1)).subscribe(() => openNext());
    this.dynamicDialogRef.close();
  }

  openLibrarySettingsView(): void {
    if (!this.library?.id || this.isSubmitting || this.isSettingsMode()) {
      return;
    }

    this.switchWorkflowDialog(() => this.dialogLauncherService.openLibrarySettingsDialog(this.library!.id!));
  }

  openLibraryDirectoriesView(): void {
    if (!this.library?.id || this.isSubmitting || this.isDirectoryManagementMode()) {
      return;
    }

    this.switchWorkflowDialog(() => this.dialogLauncherService.openLibraryDirectoriesDialog(this.library!.id!));
  }

  openLibraryMaintenanceView(): void {
    if (!this.library?.id || this.isSubmitting) {
      return;
    }

    this.switchWorkflowDialog(() => this.dialogLauncherService.openLibraryMaintenanceDialog(this.library!.id!));
  }

  getDialogTitleKey(): string {
    if (this.isCreateMode()) {
      return 'titleNew';
    }

    return this.isDirectoryManagementMode() ? 'titleDirectories' : 'titleSettings';
  }

  getDialogSubtitleKey(): string {
    if (this.isCreateMode()) {
      return 'subtitleNew';
    }

    return this.isDirectoryManagementMode() ? 'subtitleDirectories' : 'subtitleSettings';
  }

  getFoldersTitleKey(): string {
    return this.isDirectoryManagementMode() ? 'directoriesTitle' : 'bookFolders';
  }

  getAddFolderKey(): string {
    return this.isDirectoryManagementMode() ? 'addDirectory' : 'addFolder';
  }

  getEmptyFoldersHintKey(): string {
    return this.isDirectoryManagementMode() ? 'emptyDirectoriesHint' : 'emptyFoldersHint';
  }

  getRemoveFolderKey(): string {
    return this.isDirectoryManagementMode() ? 'removeDirectory' : 'removeFolder';
  }

  getSubmitButtonLabelKey(): string {
    if (this.isCreateMode()) {
      return 'buttonCreate';
    }

    return this.isDirectoryManagementMode() ? 'buttonSaveDirectories' : 'buttonSaveSettings';
  }

  getSubmitButtonIcon(): string {
    if (this.isSubmitting) {
      return 'pi pi-spin pi-spinner';
    }

    return this.isCreateMode() ? 'pi pi-check' : 'pi pi-save';
  }

  canSubmit(): boolean {
    const detailsValid = !this.requiresLibraryDetails() || this.isLibraryDetailsValid();
    const directoriesValid = !this.requiresDirectories() || this.isDirectorySelectionValid();
    const modeValid = !this.isDirectoryManagementMode() || this.hasDirectoryChanges();

    return detailsValid && directoriesValid && modeValid;
  }

  canSaveAndScanDirectories(): boolean {
    return this.isDirectoryManagementMode() && this.canSubmit() && this.getAddedDirectories().length > 0;
  }

  getValidationMessageKey(): string {
    if (this.requiresLibraryDetails() && !this.isLibraryDetailsValid()) {
      return 'validation.enterName';
    }

    if (this.requiresDirectories() && !this.isDirectorySelectionValid()) {
      return this.isDirectoryManagementMode() ? 'validation.addDirectory' : 'validation.addFolder';
    }

    if (this.isDirectoryManagementMode() && !this.hasDirectoryChanges()) {
      return 'validation.noDirectoryChanges';
    }

    return this.isCreateMode()
      ? 'validation.readyCreate'
      : this.isDirectoryManagementMode()
        ? 'validation.readyDirectories'
        : 'validation.readySettings';
  }

  hasDirectoryChanges(): boolean {
    const activeFolders = this.folders.filter(f => !this.isFolderMarkedForDeletion(f));
    return this.normalizeFolderSet(activeFolders) !== this.normalizeFolderSet(this.originalFolders);
  }

  hasDeletionChanges(): boolean {
    return this.foldersToDelete.size > 0;
  }

  getAddedDirectories(): string[] {
    const original = new Set(this.originalFolders.map(folder => this.normalizeFolderPath(folder)));
    return this.folders
      .filter(folder => !this.isFolderMarkedForDeletion(folder))
      .filter(folder => !original.has(this.normalizeFolderPath(folder)));
  }

  isImportedDirectory(folder: string): boolean {
    const normalizedFolder = this.normalizeFolderPath(folder);
    return this.originalFolders.some(existingFolder => this.normalizeFolderPath(existingFolder) === normalizedFolder);
  }

  private requiresLibraryDetails(): boolean {
    return !this.isDirectoryManagementMode();
  }

  private requiresDirectories(): boolean {
    return this.isCreateMode() || this.isDirectoryManagementMode();
  }

  getSubmissionMessage(): string {
    switch (this.submissionState) {
      case 'scanning':
        return this.t.translate('libraryCreator.creator.submission.scanning');
      case 'creating':
        return this.t.translate('libraryCreator.creator.submission.creating');
      case 'updating':
        return this.t.translate('libraryCreator.creator.submission.updating');
      case 'scanningDirectories':
        return this.t.translate('libraryCreator.creator.submission.scanningDirectories');
      case 'handoff':
        return this.t.translate('libraryCreator.creator.submission.handoff');
      default:
        return '';
    }
  }

  createOrUpdateLibrary(): void {
    this.submitCurrentLibrary(false);
  }

  saveAndScanDirectories(): void {
    this.submitCurrentLibrary(true);
  }

  private submitCurrentLibrary(scanNewDirectories: boolean): void {
    if (this.isSubmitting) {
      return;
    }

    if (scanNewDirectories ? !this.canSaveAndScanDirectories() : !this.canSubmit()) {
      return;
    }

    const trimmedLibraryName = this.chosenLibraryName.trim();
    if (trimmedLibraryName && trimmedLibraryName !== this.editModeLibraryName) {
      const exists = this.libraryService.doesLibraryExistByName(trimmedLibraryName);
      if (exists) {
        this.messageService.add({
          severity: 'error',
          summary: this.t.translate('libraryCreator.creator.toast.nameExistsSummary'),
          detail: this.t.translate('libraryCreator.creator.toast.nameExistsDetail')
        });
        return;
      }
    }

    const iconValue = this.selectedIcon?.value ?? null;
    const iconType = this.selectedIcon?.type ?? null;
    const selectedAllowedFormats = this.allowAllFormats ? [] : Array.from(this.selectedAllowedFormats);

    const library: Library = {
      name: this.chosenLibraryName,
      icon: iconValue,
      iconType: iconType,
      paths: this.folders.filter(f => !this.isFolderMarkedForDeletion(f)).map(folder => ({path: folder})),
      watch: this.watch,
      formatPriority: this.formatPriority.map(f => f.type),
      allowedFormats: selectedAllowedFormats,
      metadataSource: this.metadataSource,
      organizationMode: this.organizationMode,
      tagByDirectory: this.tagByDirectory,
      directoryTagDepth: this.directoryTagDepth
    };

    this.isSubmitting = true;
    this.submissionState = this.isEditMode()
      ? (scanNewDirectories ? 'scanningDirectories' : 'updating')
      : 'scanning';

    window.setTimeout(() => this.submitLibrary(library, selectedAllowedFormats, scanNewDirectories), 0);
  }

  private submitLibrary(library: Library, selectedAllowedFormats: BookType[], scanNewDirectories = false): void {
    if (this.isEditMode()) {
      const requiresReconcile = this.requiresReconcileAfterEdit(selectedAllowedFormats);
      const directoryTagBehaviorChanged = !this.isDirectoryManagementMode() && this.didDirectoryTagBehaviorChange();
      const addedDirectories = scanNewDirectories ? this.getAddedDirectories() : [];
      this.libraryService.updateLibrary(library, this.library?.id).subscribe({
        next: () => {
          if (scanNewDirectories && addedDirectories.length > 0) {
            this.libraryService.scanLibraryDirectoriesForNewFiles(this.library!.id as number, addedDirectories).subscribe({
              complete: () => {
                this.originalFolders = [...this.folders];
                this.resetSubmissionState();
                this.messageService.add({
                  severity: 'success',
                  summary: this.t.translate('libraryCreator.creator.toast.updatedSummary'),
                  detail: this.t.translate('libraryCreator.creator.toast.updatedDirectoriesAndScanDetail', {count: addedDirectories.length})
                });
                if (requiresReconcile) {
                  this.messageService.add({
                    severity: 'info',
                    summary: this.t.translate('libraryCreator.creator.toast.reconcileRecommendedSummary'),
                    detail: this.t.translate('libraryCreator.creator.toast.reconcileRecommendedDetail')
                  });
                }
                this.dynamicDialogRef.close();
              },
              error: (e) => {
                this.originalFolders = [...this.folders];
                this.resetSubmissionState();
                this.messageService.add({
                  severity: 'warn',
                  summary: this.t.translate('libraryCreator.creator.toast.updatedSummary'),
                  detail: this.t.translate('libraryCreator.creator.toast.updatedDirectoriesSavedButScanFailedDetail', {count: addedDirectories.length})
                });
                console.error(e);
                this.dynamicDialogRef.close();
              }
            });
            return;
          }

          this.originalFolders = [...this.folders];
          this.resetSubmissionState();
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('libraryCreator.creator.toast.updatedSummary'),
            detail: this.t.translate(this.isDirectoryManagementMode()
              ? 'libraryCreator.creator.toast.updatedDirectoriesDetail'
              : 'libraryCreator.creator.toast.updatedSettingsDetail')
          });
          if (directoryTagBehaviorChanged) {
            this.messageService.add({
              severity: 'info',
              summary: this.t.translate('libraryCreator.creator.toast.directoryTagSettingsInfoSummary'),
              detail: this.t.translate('libraryCreator.creator.toast.directoryTagSettingsInfoDetail')
            });
          }
          if (requiresReconcile) {
            this.messageService.add({
              severity: 'info',
              summary: this.t.translate('libraryCreator.creator.toast.reconcileRecommendedSummary'),
              detail: this.t.translate('libraryCreator.creator.toast.reconcileRecommendedDetail')
            });
          }
          this.dynamicDialogRef.close();
        },
        error: (e) => {
          this.resetSubmissionState();
          this.toastError(this.t.translate('libraryCreator.creator.toast.updateFailedSummary'), this.t.translate(this.isDirectoryManagementMode()
              ? 'libraryCreator.creator.toast.updateDirectoriesFailedDetail'
              : 'libraryCreator.creator.toast.updateSettingsFailedDetail'));
          console.error(e);
        }
      });
      return;
    }

    this.libraryService.scanLibraryPaths(library).pipe(
      switchMap(count => {
        this.submissionState = 'creating';

        if (count >= this.largeLibraryThreshold) {
          console.warn(`Library has ${count} processable files (>500). Will use buffered loading.`);
          this.submissionState = 'handoff';
          this.libraryService.setLargeLibraryLoading(true, count);
        }

        return this.libraryService.createLibrary(library).pipe(
          map(createdLibrary => ({ createdLibrary, count }))
        );
      })
    ).subscribe({
      next: ({ createdLibrary, count }) => {
        if (createdLibrary) {
          this.resetSubmissionState();
          this.router.navigate(['/library', createdLibrary.id, 'books']);
          this.messageService.add({
            severity: 'success',
            summary: this.t.translate('libraryCreator.creator.toast.createdSummary'),
            detail: count >= this.largeLibraryThreshold
              ? this.t.translate('libraryCreator.creator.toast.createdLargeDetail', {count})
              : this.t.translate('libraryCreator.creator.toast.createdDetail')
          });
          this.dynamicDialogRef.close();
        }
      },
      error: (e) => {
        this.resetSubmissionState();
        this.libraryService.setLargeLibraryLoading(false, 0);
        this.toastError(this.t.translate('libraryCreator.creator.toast.createFailedSummary'), this.t.translate('libraryCreator.creator.toast.createFailedDetail'));
        console.error(e);
      }
    });
  }

  private resetSubmissionState(): void {
    this.isSubmitting = false;
    this.submissionState = null;
  }

  onFormatPriorityDrop(event: CdkDragDrop<{type: BookType, label: string}[]>): void {
    moveItemInArray(this.formatPriority, event.previousIndex, event.currentIndex);
  }

  private requiresFullRefreshAfterEdit(nextAllowedFormats: BookType[]): boolean {
    return this.requiresReconcileAfterEdit(nextAllowedFormats);
  }

  private requiresReconcileAfterEdit(nextAllowedFormats: BookType[]): boolean {
    if (!this.library) {
      return false;
    }

    const currentAllowedFormats = this.library.allowedFormats ?? [];
    const currentOrganizationMode = this.library.organizationMode ?? 'BOOK_PER_FILE';

    return this.normalizeFormats(currentAllowedFormats) !== this.normalizeFormats(nextAllowedFormats)
      || currentOrganizationMode !== this.organizationMode;
  }

  private didDirectoryTagBehaviorChange(): boolean {
    if (!this.library) {
      return false;
    }

    const currentTagByDirectory = this.library.tagByDirectory ?? false;
    const currentDirectoryTagDepth = this.library.directoryTagDepth ?? 'LAST_ONLY';

    return currentTagByDirectory !== this.tagByDirectory
      || (this.tagByDirectory && currentDirectoryTagDepth !== this.directoryTagDepth);
  }

  private normalizeFormats(formats: BookType[]): string {
    return [...formats].sort().join('|');
  }

  private normalizeFolderSet(folders: string[]): string {
    return [...folders]
      .map(folder => this.normalizeFolderPath(folder))
      .sort()
      .join('|');
  }

  private normalizeFolderPath(folder: string): string {
    if (!folder || folder === '/') {
      return '/';
    }

    return folder.replace(/\/+$/, '') || '/';
  }

  getDisplayPath(folder: string): string {
    if (!folder || folder === '/') {
      return '/';
    }
    const parts = folder.split('/').filter(p => p);
    if (parts.length <= 2) {
      return folder;
    }
    return '.../' + parts.slice(parts.length - 2).join('/');
  }
}
