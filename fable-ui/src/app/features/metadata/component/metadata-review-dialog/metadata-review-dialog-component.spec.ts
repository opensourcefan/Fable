import {Component, EventEmitter, Input, Output} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {By} from '@angular/platform-browser';
import {BehaviorSubject, of, Subject, throwError} from 'rxjs';
import {beforeEach, describe, expect, it, vi} from 'vitest';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {MetadataReviewDialogComponent} from './metadata-review-dialog-component';
import {MetadataPickerComponent} from '../book-metadata-center/metadata-picker/metadata-picker.component';
import {MetadataTaskService, FetchedMetadataProposalStatus, FetchedProposal, MetadataFetchTask} from '../../../book/service/metadata-task';
import {BookService} from '../../../book/service/book.service';
import {Book, BookMetadata} from '../../../book/model/book.model';
import {MetadataProgressService} from '../../../../shared/service/metadata-progress.service';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';
import {NotificationEventService} from '../../../../shared/websocket/notification-event.service';

@Component({
  selector: 'app-metadata-picker',
  standalone: true,
  template: ''
})
class MetadataPickerStubComponent {
  @Input() reviewMode!: boolean;
  @Input() fetchedMetadata!: BookMetadata;
  @Input() book$!: unknown;
  @Input() detailLoading = false;
  @Input() reviewQuickActionLabel: string | null = null;
  @Input() reviewActionBusy = false;
  @Input() reviewActionDisabled = false;
  @Input() reviewSecondaryActionLabel: string | null = null;
  @Input() reviewSecondaryActionBusy = false;
  @Input() reviewSecondaryActionDisabled = false;
  @Output() goBack = new EventEmitter<boolean>();
  @Output() reviewQuickAction = new EventEmitter<void>();
  @Output() reviewSecondaryAction = new EventEmitter<void>();

  isSaving = false;
  copyMissing = vi.fn();
  copyAll = vi.fn();
  lockAll = vi.fn();
  unlockAll = vi.fn();
  saveMetadata = vi.fn(() => of(void 0));
}

function createProposal(proposalId: number, bookId: number): FetchedProposal {
  return {
    proposalId,
    taskId: 'task-1',
    bookId,
    fetchedAt: '2026-04-09T00:00:00Z',
    reviewedAt: null,
    reviewerUserId: null,
    status: FetchedMetadataProposalStatus.FETCHED,
    metadataJson: {bookId} as BookMetadata,
  };
}

function createBook(bookId: number): Book {
  return {
    id: bookId,
    metadata: {bookId} as BookMetadata,
  } as Book;
}

describe('MetadataReviewDialogComponent', () => {
  function createTask(): MetadataFetchTask {
    return {
      id: 'task-1',
      status: 'COMPLETED',
      completed: 0,
      totalBooks: 3,
      startedAt: '2026-04-09T00:00:00Z',
      completedAt: '2026-04-09T00:01:00Z',
      initiatedBy: '1',
      errorMessage: null,
      proposals: [createProposal(1, 11), createProposal(2, 22), createProposal(3, 33)],
    };
  }

  const metadataTaskServiceMock = {
    getTaskWithProposals: vi.fn(() => of(createTask())),
    updateProposalStatus: vi.fn(() => of(void 0)),
    deleteTask: vi.fn(() => of(void 0)),
  };

  const dialogRefMock = {
    close: vi.fn(),
  };

  const progressServiceMock = {
    clearTask: vi.fn(),
  };

  const bookMetadataManageServiceMock = {
    updateBookMetadata: vi.fn(() => of({bookId: 11} as BookMetadata)),
  };

  const notificationEventServiceMock = {
    clearNotification: vi.fn(),
  };

  const dialogConfigMock = {
    data: {
      taskId: 'task-1',
      initialBookId: undefined as number | undefined,
    },
  };

  beforeEach(async () => {
    vi.clearAllMocks();
    dialogConfigMock.data.initialBookId = undefined;

    await TestBed.configureTestingModule({
      imports: [MetadataReviewDialogComponent],
      providers: [
        {provide: DynamicDialogConfig, useValue: dialogConfigMock},
        {provide: DynamicDialogRef, useValue: dialogRefMock},
        {provide: MetadataTaskService, useValue: metadataTaskServiceMock},
        {
          provide: BookService,
          useValue: {
            bookState$: new BehaviorSubject({loaded: true, books: [createBook(11), createBook(22), createBook(33)]}),
          },
        },
        {provide: MetadataProgressService, useValue: progressServiceMock},
        {provide: BookMetadataManageService, useValue: bookMetadataManageServiceMock},
        {provide: NotificationEventService, useValue: notificationEventServiceMock},
      ]
    })
      .overrideComponent(MetadataReviewDialogComponent, {
        remove: {imports: [MetadataPickerComponent]},
        add: {imports: [MetadataPickerStubComponent]},
      })
      .compileComponents();
  });

  function createComponent() {
    const fixture = TestBed.createComponent(MetadataReviewDialogComponent);
    fixture.detectChanges();
    fixture.detectChanges();
    const picker = fixture.debugElement.query(By.directive(MetadataPickerStubComponent)).componentInstance as MetadataPickerStubComponent;
    fixture.componentInstance.pickerComponent = picker as unknown as MetadataPickerComponent;
    return {
      fixture,
      component: fixture.componentInstance,
      picker,
    };
  }

  it('moves skipped proposals to the end and shows the next review immediately', () => {
    const {component} = createComponent();

    component.onSkip();

    expect(component.proposals.map(proposal => proposal.proposalId)).toEqual([2, 3, 1]);
    expect(component.currentProposal?.proposalId).toBe(2);
  });

  it('starts on the book selected from the Staging Review card', () => {
    dialogConfigMock.data.initialBookId = 22;

    const {component} = createComponent();

    expect(component.currentIndex).toBe(1);
    expect(component.currentProposal?.bookId).toBe(22);
  });

  it('copy-all save-and-next waits for the save and accept calls before advancing', () => {
    const {component, picker} = createComponent();
    const saveSubject = new Subject<undefined>();
    const acceptSubject = new Subject<undefined>();

    picker.saveMetadata.mockReturnValue(saveSubject.asObservable());
    metadataTaskServiceMock.updateProposalStatus.mockReturnValue(acceptSubject.asObservable());

    component.onCopyAllSaveAndAdvance();

    expect(picker.copyAll).toHaveBeenCalledOnce();
    expect(component.currentProposal?.proposalId).toBe(1);

    saveSubject.next(undefined);
    saveSubject.complete();

    expect(metadataTaskServiceMock.updateProposalStatus).toHaveBeenCalledWith('task-1', 1, 'ACCEPTED');
    expect(component.currentProposal?.proposalId).toBe(1);

    acceptSubject.next(undefined);
    acceptSubject.complete();

    expect(component.currentProposal?.proposalId).toBe(2);
  });

  it('does not advance when save fails', () => {
    const {component, picker} = createComponent();

    picker.saveMetadata.mockReturnValue(throwError(() => new Error('save failed')));

    component.onCopyAllSaveAndAdvance();

    expect(metadataTaskServiceMock.updateProposalStatus).not.toHaveBeenCalled();
    expect(component.currentProposal?.proposalId).toBe(1);
  });

  it('deletes the task, clears progress, and closes when the last review is accepted through the quick action', () => {
    const {component, picker} = createComponent();

    component.onNext();
    component.onNext();
    picker.saveMetadata.mockReturnValue(of(void 0));
    metadataTaskServiceMock.updateProposalStatus.mockReturnValue(of(void 0));
    metadataTaskServiceMock.deleteTask.mockReturnValue(of(void 0));

    component.onCopyAllSaveAndAdvance();

    expect(metadataTaskServiceMock.updateProposalStatus).toHaveBeenCalledWith('task-1', 3, 'ACCEPTED');
    expect(metadataTaskServiceMock.deleteTask).toHaveBeenCalledWith('task-1');
    expect(progressServiceMock.clearTask).toHaveBeenCalledWith('task-1');
    expect(notificationEventServiceMock.clearNotification).toHaveBeenCalled();
    expect(dialogRefMock.close).toHaveBeenCalled();
  });

  it('adds the metadata follow-up tag and advances to the next proposal', () => {
    const {component} = createComponent();

    component.onTagAndAdvance();

    expect(bookMetadataManageServiceMock.updateBookMetadata).toHaveBeenCalledWith(
      11,
      {
        metadata: {
          bookId: 11,
          tags: ['Metadata Follow-Up Req'],
        },
        clearFlags: {},
      },
      false,
      'REPLACE_WHEN_PROVIDED'
    );
    expect(component.currentProposal?.proposalId).toBe(2);
  });

  it('clears metadata task state and live notification state when finish and close is pressed on the last proposal', () => {
    const {component} = createComponent();

    component.onNext();
    component.onNext();
    component.onNext();

    expect(metadataTaskServiceMock.deleteTask).toHaveBeenCalledWith('task-1');
    expect(progressServiceMock.clearTask).toHaveBeenCalledWith('task-1');
    expect(notificationEventServiceMock.clearNotification).toHaveBeenCalled();
    expect(dialogRefMock.close).toHaveBeenCalled();
  });

  it('skips proposals whose books are missing from loaded book state instead of hanging on loading', () => {
    const bookService = TestBed.inject(BookService) as unknown as {
      bookState$: BehaviorSubject<{loaded: boolean; books: Book[] | null}>;
    };
    bookService.bookState$.next({loaded: true, books: [createBook(11), createBook(33)]});

    const {component} = createComponent();

    expect(component.loading).toBe(false);
    expect(component.proposals.map(proposal => proposal.bookId)).toEqual([11, 33]);
    expect(dialogRefMock.close).not.toHaveBeenCalled();
  });

  it('closes cleanly when no proposal books exist in loaded book state', () => {
    const bookService = TestBed.inject(BookService) as unknown as {
      bookState$: BehaviorSubject<{loaded: boolean; books: Book[] | null}>;
    };
    bookService.bookState$.next({loaded: true, books: []});

    const fixture = TestBed.createComponent(MetadataReviewDialogComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.loading).toBe(false);
    expect(dialogRefMock.close).toHaveBeenCalledOnce();
  });
});