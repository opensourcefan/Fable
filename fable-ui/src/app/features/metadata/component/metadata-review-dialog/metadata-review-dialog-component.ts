import {Component, DestroyRef, inject, OnInit, ViewChild} from '@angular/core';
import {CommonModule} from '@angular/common';
import {DynamicDialogConfig, DynamicDialogRef} from 'primeng/dynamicdialog';
import {FetchedProposal, MetadataTaskService} from '../../../book/service/metadata-task';
import {BookService} from '../../../book/service/book.service';
import {Book} from '../../../book/model/book.model';
import {BehaviorSubject, Observable, of} from 'rxjs';
import {filter, finalize, map, switchMap, tap} from 'rxjs/operators';
import {MessageService} from 'primeng/api';
import {Button} from 'primeng/button';
import {ProgressBar} from 'primeng/progressbar';
import {Tooltip} from 'primeng/tooltip';
import {MetadataProgressService} from '../../../../shared/service/metadata-progress.service';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {MetadataPickerComponent} from '../book-metadata-center/metadata-picker/metadata-picker.component';
import {BookMetadataManageService} from '../../../book/service/book-metadata-manage.service';
import {NotificationEventService} from '../../../../shared/websocket/notification-event.service';
import {LoadingIndicatorComponent} from '../../../../shared/components/loading-indicator/loading-indicator.component';

const METADATA_FOLLOW_UP_TAG = 'Metadata Follow-Up Req';

@Component({
  selector: 'app-metadata-review-dialog-component',
  standalone: true,
  templateUrl: './metadata-review-dialog-component.html',
  styleUrls: ['./metadata-review-dialog-component.scss'],
  imports: [CommonModule, MetadataPickerComponent, LoadingIndicatorComponent, Button, ProgressBar, Tooltip],
})
export class MetadataReviewDialogComponent implements OnInit {

  @ViewChild(MetadataPickerComponent)
  pickerComponent!: MetadataPickerComponent;

  private config = inject(DynamicDialogConfig);
  private dialogRef = inject(DynamicDialogRef);
  private metadataTaskService = inject(MetadataTaskService);
  private bookService = inject(BookService);
  private bookMetadataManageService = inject(BookMetadataManageService);
  private progressService = inject(MetadataProgressService);
  private notificationEventService = inject(NotificationEventService);
  private messageService = inject(MessageService, {optional: true});
  private destroyRef = inject(DestroyRef);

  proposals: FetchedProposal[] = [];
  currentBooks: Record<number, Book> = {};
  loading = true;
  currentIndex = 0;
  processingAction: 'save' | 'quick' | 'tag' | null = null;
  private initialized = false;

  private currentIndexSubject = new BehaviorSubject<number>(0);

  book$: Observable<Book | null> = this.currentIndexSubject.pipe(
    map(idx => {
      const proposal = this.proposals[idx];
      if (!proposal) return null;
      return this.currentBooks[proposal.bookId] ?? null;
    })
  );

  ngOnInit() {
    const taskId = this.config.data?.taskId;
    const initialBookId = this.config.data?.initialBookId;
    if (!taskId) {
      this.dialogRef.close();
      return;
    }

    this.metadataTaskService.getTaskWithProposals(taskId).subscribe({
      next: (task) => {
        const fetchedProposals = task.proposals || [];
        const bookIds = new Set(fetchedProposals.map(p => p.bookId));

        this.bookService.bookState$
          .pipe(
            filter(bookState => bookState.loaded && bookState.books !== null),
            takeUntilDestroyed(this.destroyRef)
          )
          .subscribe((bookState) => {
            if (this.initialized) {
              return;
            }
            const matchedBooks = (bookState.books ?? []).filter(book => bookIds.has(book.id));
            const matchedBookMap = matchedBooks.reduce((acc, book) => {
              acc[book.id] = book;
              return acc;
            }, {} as Record<number, Book>);

            this.proposals = fetchedProposals.filter(proposal => Boolean(matchedBookMap[proposal.bookId]));
            if (this.proposals.length === 0) {
              this.loading = false;
              this.initialized = true;
              this.messageService?.add({
                severity: 'info',
                summary: 'Metadata Review',
                detail: 'No reviewable books remain for this task.',
                life: 3500,
              });
              this.dialogRef.close();
              return;
            }

            this.currentBooks = matchedBookMap;
            this.loading = false;
            const requestedIndex = typeof initialBookId === 'number'
              ? this.proposals.findIndex(proposal => proposal.bookId === initialBookId)
              : -1;
            this.currentIndex = requestedIndex >= 0 ? requestedIndex : 0;
            this.currentIndexSubject.next(this.currentIndex);
            this.initialized = true;
          });
      },
      error: () => {
        this.dialogRef.close();
      },
    });
  }

  get currentProposal(): FetchedProposal | null {
    return this.proposals[this.currentIndex] ?? null;
  }

  get currentBook(): Book | null {
    const currentProposal = this.currentProposal;
    if (!currentProposal) {
      return null;
    }

    return this.currentBooks[currentProposal.bookId] ?? null;
  }

  get isBusy(): boolean {
    return this.processingAction !== null || this.pickerComponent?.isSaving === true;
  }

  get acceptSaveSeverity(): 'secondary' | 'warn' | 'success' | 'danger' {
    return this.pickerComponent?.saveSeverity ?? 'secondary';
  }

  get quickActionLabel(): string {
    return this.isLast ? 'Copy All, Save & Finish' : 'Copy All, Save & Next';
  }

  get tagActionLabel(): string {
    return this.isLast ? 'Tag & Finish' : 'Tag & Next';
  }

  onSave(): void {
    this.persistCurrentProposal('save', false);
  }

  onCopyAllSaveAndAdvance(): void {
    this.pickerComponent?.copyAll();
    this.persistCurrentProposal('quick', true);
  }

  onTagAndAdvance(): void {
    const currentBook = this.currentBook;
    const currentProposal = this.currentProposal;
    if (!currentBook || !currentProposal || this.isBusy) {
      return;
    }

    const existingTags = currentBook.metadata?.tags ?? [];
    const tags = existingTags.includes(METADATA_FOLLOW_UP_TAG)
      ? existingTags
      : [...existingTags, METADATA_FOLLOW_UP_TAG];

    this.processingAction = 'tag';

    const saveTag$ = existingTags.includes(METADATA_FOLLOW_UP_TAG)
      ? of(void 0)
      : this.bookMetadataManageService.updateBookMetadata(
        currentBook.id,
        {
          metadata: {
            bookId: currentBook.id,
            tags,
          },
          clearFlags: {},
        },
        false,
        'REPLACE_WHEN_PROVIDED'
      ).pipe(map(() => void 0));

    saveTag$.pipe(
      switchMap(() => this.isLast ? this.completeReview(currentProposal.taskId) : of(void 0)),
      finalize(() => {
        this.processingAction = null;
      })
    ).subscribe({
      next: () => {
        if (this.isLast) {
          this.close();
          return;
        }

        this.onNext();
      }
    });
  }

  onSkip(): void {
    if (this.isBusy || !this.currentProposal) {
      return;
    }

    if (this.currentIndex >= this.proposals.length - 1) {
      this.close();
      return;
    }

    const [skipped] = this.proposals.splice(this.currentIndex, 1);
    this.proposals = [...this.proposals, skipped];
    this.currentIndexSubject.next(this.currentIndex);
  }

  onCopyMissing(): void {
    this.pickerComponent?.copyMissing();
  }

  onCopyAll(): void {
    this.pickerComponent?.copyAll();
  }

  private persistCurrentProposal(action: 'save' | 'quick', advanceAfterSave: boolean): void {
    const currentProposal = this.currentProposal;
    if (!currentProposal || !this.pickerComponent || this.isBusy) return;

    this.processingAction = action;
    const shouldDeleteTask = this.isLast;

    this.pickerComponent.saveMetadata().pipe(
      switchMap(() => this.metadataTaskService.updateProposalStatus(currentProposal.taskId, currentProposal.proposalId, 'ACCEPTED')),
      switchMap(() => shouldDeleteTask ? this.completeReview(currentProposal.taskId) : of(void 0)),
      finalize(() => {
        this.processingAction = null;
      })
    ).subscribe({
      next: () => {
        if (shouldDeleteTask) {
          this.close();
          return;
        }

        if (advanceAfterSave) {
          this.onNext();
        }
      },
      error: () => {
        // The picker already reports save failures to the user. Keep the
        // current proposal open so the review can be corrected and retried.
      }
    });
  }

  onNext(): void {
    if (this.isLast) {
      const currentProposal = this.currentProposal;
      if (!currentProposal || this.isBusy) {
        return;
      }

      this.completeReview(currentProposal.taskId).subscribe({
        next: () => {
          this.close();
        }
      });
      return;
    }

    const nextIndex = this.currentIndex + 1;
    this.currentIndex = nextIndex;
    this.currentIndexSubject.next(nextIndex);
  }

  lockAllMetadata(): void {
    if (this.isBusy) return;
    this.pickerComponent?.lockAll();
  }

  unlockAllMetadata(): void {
    if (this.isBusy) return;
    this.pickerComponent?.unlockAll();
  }

  get isLast(): boolean {
    return this.currentIndex === this.proposals.length - 1;
  }

  private completeReview(taskId: string): Observable<void> {
    return this.metadataTaskService.deleteTask(taskId).pipe(
      tap(() => {
        this.progressService.clearTask(taskId);
        this.notificationEventService.clearNotification();
      })
    );
  }

  close(): void {
    this.dialogRef.close();
  }
}
