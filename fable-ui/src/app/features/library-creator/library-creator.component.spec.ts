import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {TestBed} from '@angular/core/testing';
import {of, Subject} from 'rxjs';
import {LibraryCreatorComponent} from './library-creator.component';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MessageService} from 'primeng/api';
import {Router} from '@angular/router';
import {LibraryService} from '../book/service/library.service';
import {IconPickerService} from '../../shared/service/icon-picker.service';
import {DialogLauncherService} from '../../shared/services/dialog-launcher.service';
import {TranslocoService} from '@jsverse/transloco';
import {Library} from '../book/model/library.model';
import libraryCreatorTranslations from '../../../i18n/en/library-creator.json';

describe('LibraryCreatorComponent', () => {
  let component: LibraryCreatorComponent;
  let libraryServiceMock: {
    updateLibrary: ReturnType<typeof vi.fn>;
    refreshLibrary: ReturnType<typeof vi.fn>;
    scanLibraryPaths: ReturnType<typeof vi.fn>;
    scanLibraryDirectoriesForNewFiles: ReturnType<typeof vi.fn>;
    createLibrary: ReturnType<typeof vi.fn>;
    doesLibraryExistByName: ReturnType<typeof vi.fn>;
    setLargeLibraryLoading: ReturnType<typeof vi.fn>;
    findLibraryById: ReturnType<typeof vi.fn>;
    getBookCountsByFormat: ReturnType<typeof vi.fn>;
  };
  let messageServiceMock: { add: ReturnType<typeof vi.fn> };
  let dialogClose$: Subject<void>;
  let dialogDestroy$: Subject<void>;
  let dialogRefMock: { close: ReturnType<typeof vi.fn>; onClose: Subject<void>; onDestroy: Subject<void> };
  let dialogLauncherMock: {
    openDirectoryPickerDialog: ReturnType<typeof vi.fn>;
    openLibrarySettingsDialog: ReturnType<typeof vi.fn>;
    openLibraryDirectoriesDialog: ReturnType<typeof vi.fn>;
    openLibraryMaintenanceDialog: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    vi.useFakeTimers();

    libraryServiceMock = {
      updateLibrary: vi.fn().mockReturnValue(of({})),
      refreshLibrary: vi.fn().mockReturnValue(of(void 0)),
      scanLibraryPaths: vi.fn().mockReturnValue(of(0)),
      scanLibraryDirectoriesForNewFiles: vi.fn().mockReturnValue(of(void 0)),
      createLibrary: vi.fn().mockReturnValue(of({id: 1})),
      doesLibraryExistByName: vi.fn().mockReturnValue(false),
      setLargeLibraryLoading: vi.fn(),
      findLibraryById: vi.fn(),
      getBookCountsByFormat: vi.fn().mockReturnValue(of({}))
    };
    messageServiceMock = {add: vi.fn()};
    dialogClose$ = new Subject<void>();
    dialogDestroy$ = new Subject<void>();
    dialogRefMock = {
      close: vi.fn(() => dialogClose$.next()),
      onClose: dialogClose$,
      onDestroy: dialogDestroy$
    };
    dialogLauncherMock = {
      openDirectoryPickerDialog: vi.fn(),
      openLibrarySettingsDialog: vi.fn(),
      openLibraryDirectoriesDialog: vi.fn(),
      openLibraryMaintenanceDialog: vi.fn()
    };

    TestBed.configureTestingModule({
      imports: [LibraryCreatorComponent],
      providers: [
        {provide: DynamicDialogRef, useValue: dialogRefMock},
        {provide: DynamicDialogConfig, useValue: {data: null}},
        {provide: MessageService, useValue: messageServiceMock},
        {provide: Router, useValue: {navigate: vi.fn()}},
        {provide: LibraryService, useValue: libraryServiceMock},
        {provide: IconPickerService, useValue: {open: vi.fn().mockReturnValue(of(null))}},
        {provide: DialogLauncherService, useValue: dialogLauncherMock},
        {
          provide: TranslocoService,
          useValue: {
            translate: (key: string) => key,
            langChanges$: of('en'),
            getActiveLang: () => 'en',
            config: {reRenderOnLangChange: false}
          }
        }
      ]
    });

    component = TestBed.runInInjectionContext(() => new LibraryCreatorComponent());
    component.mode = 'edit-settings';
    component.library = {
      id: 5,
      name: 'Library',
      watch: false,
      paths: [{path: '/books'}],
      allowedFormats: ['EPUB'],
      organizationMode: 'BOOK_PER_FILE'
    };
    component.chosenLibraryName = 'Library';
    component.folders = ['/books'];
    component.originalFolders = ['/books'];
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function submitLibrary(library: Library, selectedAllowedFormats: 'EPUB'[]): void {
    (component as unknown as {submitLibrary: (nextLibrary: Library, nextAllowedFormats: 'EPUB'[]) => void}).submitLibrary(library, selectedAllowedFormats);
  }

  it('does not auto-reconcile after saving library edits', () => {
    submitLibrary({name: 'Library', watch: false, paths: []}, ['EPUB']);

    expect(libraryServiceMock.updateLibrary).toHaveBeenCalledWith(expect.any(Object), 5);
    expect(libraryServiceMock.refreshLibrary).not.toHaveBeenCalled();
  });

  it('shows a follow-up info toast when saved changes would require manual reconcile', () => {
    component.organizationMode = 'BOOK_PER_FOLDER';

    submitLibrary({name: 'Library', watch: false, paths: []}, ['EPUB']);

    expect(messageServiceMock.add).toHaveBeenCalledWith(expect.objectContaining({
      severity: 'info',
      summary: 'libraryCreator.creator.toast.reconcileRecommendedSummary'
    }));
    expect(libraryServiceMock.refreshLibrary).not.toHaveBeenCalled();
  });

  it('allows directory management mode to submit without showing the settings sections', () => {
    component.mode = 'edit-directories';
    component.folders = ['/books', '/new'];

    expect(component.showLibraryDetailsSection()).toBe(false);
    expect(component.showOptionsSection()).toBe(false);
    expect(component.showFoldersSection()).toBe(true);
    expect(component.canSubmit()).toBe(true);
  });

  it('does not submit directory changes when no directories remain', () => {
    component.mode = 'edit-directories';
    component.folders = [];

    component.createOrUpdateLibrary();

    expect(libraryServiceMock.updateLibrary).not.toHaveBeenCalled();
  });

  it('saves directory changes without scanning when using the plain save action', () => {
    component.mode = 'edit-directories';
    component.folders = ['/books', '/new'];

    component.createOrUpdateLibrary();
    vi.runAllTimers();

    expect(libraryServiceMock.updateLibrary).toHaveBeenCalledWith(expect.any(Object), 5);
    expect(libraryServiceMock.scanLibraryDirectoriesForNewFiles).not.toHaveBeenCalled();
  });

  it('saves and scans only newly added directories when requested', () => {
    component.mode = 'edit-directories';
    component.folders = ['/books', '/new', '/another'];

    component.saveAndScanDirectories();
    vi.runAllTimers();

    expect(libraryServiceMock.updateLibrary).toHaveBeenCalledWith(expect.any(Object), 5);
    expect(libraryServiceMock.scanLibraryDirectoriesForNewFiles).toHaveBeenCalledWith(5, ['/new', '/another']);
  });

  it('keeps directory save guidance text free of embedded html markup', () => {
    expect(libraryCreatorTranslations.creator.directorySaveBehaviorPathsOnly).not.toContain('<strong>');
    expect(libraryCreatorTranslations.creator.directorySaveBehavior).not.toContain('<strong>');
    expect(libraryCreatorTranslations.creator.directorySaveBehaviorWithPendingScan).not.toContain('<strong>');
  });

  it('shows an info toast when directory tag behavior changes', () => {
    component.library = {
      id: 5,
      name: 'Library',
      watch: false,
      paths: [{path: '/books'}],
      allowedFormats: ['EPUB'],
      organizationMode: 'BOOK_PER_FILE',
      tagByDirectory: false,
      directoryTagDepth: 'LAST_ONLY'
    };
    component.tagByDirectory = true;

    submitLibrary({name: 'Library', watch: false, paths: []}, ['EPUB']);

    expect(messageServiceMock.add).toHaveBeenCalledWith(expect.objectContaining({
      severity: 'info',
      summary: 'libraryCreator.creator.toast.directoryTagSettingsInfoSummary'
    }));
  });

  it('closes before opening the settings dialog', () => {
    component.mode = 'edit-directories';

    component.openLibrarySettingsView();

    expect(dialogRefMock.close).toHaveBeenCalledTimes(1);
    expect(dialogLauncherMock.openLibrarySettingsDialog).not.toHaveBeenCalled();

    dialogClose$.next();

    expect(dialogLauncherMock.openLibrarySettingsDialog).not.toHaveBeenCalled();

    dialogDestroy$.next();

    expect(dialogLauncherMock.openLibrarySettingsDialog).toHaveBeenCalledWith(5);
  });

  it('closes before opening the directories dialog', () => {
    component.mode = 'edit-settings';

    component.openLibraryDirectoriesView();

    expect(dialogRefMock.close).toHaveBeenCalledTimes(1);
    expect(dialogLauncherMock.openLibraryDirectoriesDialog).not.toHaveBeenCalled();

    dialogClose$.next();

    expect(dialogLauncherMock.openLibraryDirectoriesDialog).not.toHaveBeenCalled();

    dialogDestroy$.next();

    expect(dialogLauncherMock.openLibraryDirectoriesDialog).toHaveBeenCalledWith(5);
  });

  it('closes before opening the maintenance dialog', () => {
    component.openLibraryMaintenanceView();

    expect(dialogRefMock.close).toHaveBeenCalledTimes(1);
    expect(dialogLauncherMock.openLibraryMaintenanceDialog).not.toHaveBeenCalled();

    dialogClose$.next();

    expect(dialogLauncherMock.openLibraryMaintenanceDialog).not.toHaveBeenCalled();

    dialogDestroy$.next();

    expect(dialogLauncherMock.openLibraryMaintenanceDialog).toHaveBeenCalledWith(5);
  });

  it('sorts folder paths alphabetically on load', () => {
    const unsortedPaths = [
      {path: '/books/poetry'},
      {path: '/books/fiction/sci-fi'},
      {path: '/books/non-fiction/history'}
    ];
    const testLibrary = {
      id: 5,
      name: 'Library',
      watch: false,
      paths: unsortedPaths,
      allowedFormats: ['EPUB'],
      organizationMode: 'BOOK_PER_FILE'
    };
    libraryServiceMock.findLibraryById.mockReturnValue(testLibrary);
    component['dynamicDialogConfig'] = { data: { mode: 'edit-settings', libraryId: 5 } } as unknown as DynamicDialogConfig;
    component.ngOnInit();
    expect(component.folders).toEqual([
      '/books/fiction/sci-fi',
      '/books/non-fiction/history',
      '/books/poetry'
    ]);
  });

  it('truncates paths to last two directories', () => {
    component.folders = [
      '/home/michael/fable/books/poetry',
      '/home/michael/fable/books/fiction/sci-fi',
      '/home/michael/fable/books/non-fiction/history'
    ];
    expect(component.getDisplayPath('/home/michael/fable/books/poetry')).toBe('.../books/poetry');
    expect(component.getDisplayPath('/home/michael/fable/books/fiction/sci-fi')).toBe('.../fiction/sci-fi');
  });

  it('tracks inaccessible folders on load and excludes folders marked for deletion', () => {
    const testLibrary = {
      id: 5,
      name: 'Library',
      watch: false,
      paths: [
        {path: '/books/accessible', accessible: true},
        {path: '/books/missing', accessible: false}
      ],
      allowedFormats: ['EPUB'],
      organizationMode: 'BOOK_PER_FILE'
    };
    libraryServiceMock.findLibraryById.mockReturnValue(testLibrary);
    component['dynamicDialogConfig'] = { data: { mode: 'edit-directories', libraryId: 5 } } as unknown as DynamicDialogConfig;
    component.ngOnInit();

    expect(component.isFolderInaccessible('/books/accessible')).toBe(false);
    expect(component.isFolderInaccessible('/books/missing')).toBe(true);
    expect(component.getInaccessibleActiveFolders()).toEqual(['/books/missing']);

    component.removeFolder(1, '/books/missing');
    expect(component.getInaccessibleActiveFolders()).toEqual([]);
  });
});