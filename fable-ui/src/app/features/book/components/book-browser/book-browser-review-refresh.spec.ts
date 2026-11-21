import {convertToParamMap} from '@angular/router';
import {of, Subject} from 'rxjs';
import {describe, expect, it, vi} from 'vitest';
import {BookBrowserComponent, EntityType} from './book-browser.component';

describe('BookBrowserComponent metadata review refresh', () => {
  it('refreshes Staging triage after the review dialog closes', () => {
    const onClose = new Subject<void>();
    const refreshStagingTriage = vi.fn();
    const openMetadataReviewDialog = vi.fn(() => ({onClose}));
    const componentLike = {
      entityType: EntityType.STAGING,
      destroy$: new Subject<void>(),
      dialogLauncherService: {openMetadataReviewDialog},
      refreshStagingTriage,
    };

    const openMetadataReviewAndRefresh = (BookBrowserComponent.prototype as unknown as {
      openMetadataReviewAndRefresh: (taskId: string, initialBookId?: number) => void;
    }).openMetadataReviewAndRefresh;

    openMetadataReviewAndRefresh.call(componentLike, 'task-1', 42);
    expect(openMetadataReviewDialog).toHaveBeenCalledWith('task-1', 42);
    expect(refreshStagingTriage).not.toHaveBeenCalled();

    onClose.next();

    expect(refreshStagingTriage).toHaveBeenCalledOnce();
  });

  it('stores the active Staging section in the return URL query', () => {
    const navigate = vi.fn();
    const activatedRoute = {
      snapshot: {queryParamMap: convertToParamMap({})},
    };
    const componentLike = {
      activatedRoute,
      router: {navigate},
    };
    const syncStagingTriageModeQuery = (BookBrowserComponent.prototype as unknown as {
      syncStagingTriageModeQuery: (mode: 'staging' | 'completed' | 'review') => void;
    }).syncStagingTriageModeQuery;

    syncStagingTriageModeQuery.call(componentLike, 'completed');

    expect(navigate).toHaveBeenCalledWith([], {
      relativeTo: activatedRoute,
      queryParams: {triage: 'completed'},
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });

  it('returns Review to Staging when the refreshed review count reaches zero', () => {
    const syncStagingTriageModeQuery = vi.fn();
    const rebuildStagingBookState = vi.fn();
    const markForCheck = vi.fn();
    const componentLike = {
      metadataTaskService: {
        getStagingTriage: () => of({
          stagingCount: 0,
          completedCount: 0,
          reviewCount: 0,
          stagingBookIds: [],
          completedBookIds: [],
          reviewBookIds: [],
          reviewTasks: [],
        }),
      },
      destroy$: new Subject<void>(),
      stagingTriageMode: 'review',
      stagingInboxCount: 1,
      stagingCompletedCount: 0,
      pendingReviewCount: 1,
      stagingInboxBookIds: new Set([42]),
      stagingCompletedBookIds: new Set<number>(),
      pendingReviewBookIds: new Set([42]),
      pendingReviewTasks: [{taskId: 'task-1', proposalCount: 1, bookIds: [42]}],
      pendingReviewPrimaryTaskId: 'task-1',
      lastStagingTriageSignature: '',
      entityType: EntityType.STAGING,
      syncStagingTriageModeQuery,
      rebuildStagingBookState,
      cdr: {markForCheck},
    };

    const refreshStagingTriage = (BookBrowserComponent.prototype as unknown as {
      refreshStagingTriage: () => void;
    }).refreshStagingTriage;

    refreshStagingTriage.call(componentLike);

    expect(componentLike.stagingTriageMode).toBe('staging');
    expect(componentLike.pendingReviewCount).toBe(0);
    expect(componentLike.pendingReviewBookIds).toEqual(new Set());
    expect(syncStagingTriageModeQuery).toHaveBeenCalledWith('staging');
    expect(rebuildStagingBookState).toHaveBeenCalledOnce();
    expect(markForCheck).toHaveBeenCalledOnce();
  });

  it('clears selections when switching Staging triage tabs', () => {
    const deselectAll = vi.fn();
    const syncStagingTriageModeQuery = vi.fn();
    const rebuildStagingBookState = vi.fn();
    const markForCheck = vi.fn();
    const componentLike = {
      entityType: EntityType.STAGING,
      stagingTriageMode: 'staging',
      stagingCompletedCount: 3,
      pendingReviewCount: 0,
      pendingReviewPrimaryTaskId: null,
      lastStagingTriageSignature: 'sig',
      bookSelectionService: {deselectAll},
      syncStagingTriageModeQuery,
      rebuildStagingBookState,
      cdr: {markForCheck},
    };

    const setStagingTriageMode = (BookBrowserComponent.prototype as unknown as {
      setStagingTriageMode: (mode: 'staging' | 'completed' | 'review') => void;
    }).setStagingTriageMode;

    setStagingTriageMode.call(componentLike, 'completed');

    expect(deselectAll).toHaveBeenCalledOnce();
    expect(componentLike.stagingTriageMode).toBe('completed');
    expect(rebuildStagingBookState).toHaveBeenCalledOnce();
  });

  it('requires confirmation before releasing when hidden selections exist', () => {
    const confirm = vi.fn();
    const executeReleaseFromStaging = vi.fn();
    const componentLike = {
      entityType: EntityType.STAGING,
      selectedBooks: new Set([1, 2, 3]),
      hiddenSelectedCount: 2,
      get hasHiddenSelections() {
        return this.hiddenSelectedCount > 0;
      },
      confirmationService: {confirm},
      t: {translate: (key: string) => key},
      executeReleaseFromStaging,
    };

    const releaseFromStaging = (BookBrowserComponent.prototype as unknown as {
      releaseFromStaging: () => void;
    }).releaseFromStaging;

    releaseFromStaging.call(componentLike);

    expect(confirm).toHaveBeenCalledOnce();
    expect(executeReleaseFromStaging).not.toHaveBeenCalled();

    const confirmArg = confirm.mock.calls[0][0] as {accept: () => void};
    confirmArg.accept();
    expect(executeReleaseFromStaging).toHaveBeenCalledWith(new Set([1, 2, 3]));
  });

  it('deselects books when custom metadata fetch dialog is submitted and preserves selection on cancel', () => {
    const onCloseSubmitted = new Subject<{submitted?: boolean} | undefined>();
    const onCloseCancelled = new Subject<{submitted?: boolean} | undefined>();
    const deselectAllBooks = vi.fn();
    const openMetadataRefreshDialog = vi.fn()
      .mockReturnValueOnce({onClose: onCloseSubmitted})
      .mockReturnValueOnce({onClose: onCloseCancelled});
    const componentLike = {
      selectedBooks: new Set([1, 2]),
      destroy$: new Subject<void>(),
      dynamicDialogRef: null as unknown,
      dialogHelperService: {openMetadataRefreshDialog},
      deselectAllBooks,
    };

    const fetchMetadata = (BookBrowserComponent.prototype as unknown as {
      fetchMetadata: () => void;
    }).fetchMetadata;

    fetchMetadata.call(componentLike);
    onCloseSubmitted.next({submitted: true});
    expect(deselectAllBooks).toHaveBeenCalledTimes(1);

    fetchMetadata.call(componentLike);
    onCloseCancelled.next(undefined);
    expect(deselectAllBooks).toHaveBeenCalledTimes(1);
  });

  it('prunes selected Staging books that moved out of the active triage tab', () => {
    const deselectAll = vi.fn();
    const setSelectedBooks = vi.fn();
    const componentLike = {
      entityType: EntityType.STAGING,
      stagingTriageMode: 'staging',
      stagingInboxBookIds: new Set([2]),
      stagingCompletedBookIds: new Set([1]),
      pendingReviewBookIds: new Set<number>(),
      bookSelectionService: {
        selectedBooks: new Set([1, 2]),
        deselectAll,
        setSelectedBooks,
      },
    };

    const pruneSelectionsOutsideActiveStagingTab = (BookBrowserComponent.prototype as unknown as {
      pruneSelectionsOutsideActiveStagingTab: () => void;
    }).pruneSelectionsOutsideActiveStagingTab;

    pruneSelectionsOutsideActiveStagingTab.call(componentLike);
    expect(setSelectedBooks).toHaveBeenCalledWith(new Set([2]));
    expect(deselectAll).not.toHaveBeenCalled();

    componentLike.stagingInboxBookIds = new Set<number>();
    componentLike.bookSelectionService.selectedBooks = new Set([2]);
    pruneSelectionsOutsideActiveStagingTab.call(componentLike);
    expect(deselectAll).toHaveBeenCalledOnce();
  });
});
