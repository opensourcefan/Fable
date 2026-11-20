import {inject, Injectable} from '@angular/core';
import {ConfirmationService, MenuItem, MessageService} from 'primeng/api';
import {BookService} from './book.service';
import {BookMetadataManageService} from './book-metadata-manage.service';
import {AGE_RATING_OPTIONS, CONTENT_RATING_LABELS, readStatusLabels} from '../components/book-browser/book-filter/book-filter.config';
import {ReadStatus} from '../model/book.model';
import {ResetProgressTypes} from '../../../shared/constants/reset-progress-type';
import {User} from '../../settings/user-management/user.service';
import {APIException} from '../../../shared/models/api-exception.model';
import {HttpErrorResponse} from '@angular/common/http';
import {TranslocoService} from '@jsverse/transloco';
import {WriteProgressService} from '../../../shared/service/write-progress.service';
import {PagedBookBrowserStateService} from './paged-book-browser-state.service';

@Injectable({
  providedIn: 'root'
})
export class BookMenuService {

  confirmationService = inject(ConfirmationService);
  messageService = inject(MessageService);
  bookService = inject(BookService);
  pagedBookBrowserStateService = inject(PagedBookBrowserStateService);
  bookMetadataManageService = inject(BookMetadataManageService);
  writeProgressService = inject(WriteProgressService);
  private readonly t = inject(TranslocoService);

  getMetadataMenuItems(
    autoFetchMetadata: () => void,
    fetchMetadata: () => void,
    bulkEditMetadata: () => void,
    multiBookEditMetadata: () => void,
    restoreTitlesFromFilenames: () => void,
    regenerateCovers: () => void,
    generateCustomCovers: () => void,
    user: User | null,
    isbnDiscovery?: () => void,
    showIsbnDiscovery = false): MenuItem[] {

    const permissions = user?.permissions;
    const items: MenuItem[] = [];

    if (permissions?.canBulkAutoFetchMetadata) {
      items.push({
        label: this.t.translate('book.menuService.menu.autoFetchMetadata'),
        icon: 'pi pi-bolt',
        command: autoFetchMetadata
      });
      if (showIsbnDiscovery && isbnDiscovery) {
        items.push({
          label: this.t.translate('book.menuService.menu.isbnDiscovery'),
          icon: 'pi pi-barcode',
          command: isbnDiscovery
        });
      }
    }

    if (permissions?.canBulkCustomFetchMetadata) {
      items.push({
        label: this.t.translate('book.menuService.menu.customFetchMetadata'),
        icon: 'pi pi-sync',
        command: fetchMetadata
      });
    }

    if (permissions?.canBulkEditMetadata) {
      items.push({
        label: this.t.translate('book.menuService.menu.bulkMetadataEditor'),
        icon: 'pi pi-table',
        command: bulkEditMetadata
      });
      items.push({
        label: this.t.translate('book.menuService.menu.multiBookMetadataEditor'),
        icon: 'pi pi-clone',
        command: multiBookEditMetadata
      });
      items.push({
        label: this.t.translate('book.menuService.menu.restoreTitlesFromFilenames'),
        icon: 'pi pi-file-edit',
        command: restoreTitlesFromFilenames
      });
    }

    if (permissions?.canBulkRegenerateCover) {
      items.push({
        label: this.t.translate('book.menuService.menu.regenerateCovers'),
        icon: 'pi pi-image',
        command: regenerateCovers
      });
      items.push({
        label: this.t.translate('book.menuService.menu.generateCustomCovers'),
        icon: 'pi pi-palette',
        command: generateCustomCovers
      });
    }

    return items;
  }

  getMoreActionsMenu(selectedBooks: Set<number>, user: User | null): MenuItem[] {
    const count = selectedBooks.size;
    const permissions = user?.permissions;
    const items: MenuItem[] = [];

    if (permissions?.canBulkResetBookReadStatus) {
      items.push({
        label: this.t.translate('book.menuService.menu.updateReadStatus'),
        icon: 'pi pi-book',
        items: Object.entries(readStatusLabels).map(([status, label]) => ({
          label,
          command: () => {
            this.confirmationService.confirm({
              message: this.t.translate('book.menuService.confirm.readStatusMessage', {count, label}),
              header: this.t.translate('book.menuService.confirm.readStatusHeader'),
              icon: 'pi pi-exclamation-triangle',
              acceptLabel: this.t.translate('common.yes'),
              rejectLabel: this.t.translate('common.no'),
              acceptButtonProps: {
                label: this.t.translate('common.yes'),
                severity: 'success'
              },
              rejectButtonProps: {
                label: this.t.translate('common.no'),
                severity: 'secondary'
              },
              accept: () => {
                this.writeProgressService.show(this.t.translate('book.menuService.loading.updatingReadStatus', {count}));

                this.bookService.updateBookReadStatus(Array.from(selectedBooks), status as ReadStatus)
                  .subscribe({
                    next: () => {
                      this.writeProgressService.complete(this.t.translate('book.menuService.toast.readStatusUpdatedSummary'));
                      this.messageService.add({
                        severity: 'success',
                        summary: this.t.translate('book.menuService.toast.readStatusUpdatedSummary'),
                        detail: this.t.translate('book.menuService.toast.readStatusUpdatedDetail', {label}),
                        life: 2000
                      });
                    },
                    error: (err: HttpErrorResponse) => {
                      const apiError = err.error as APIException;
                      this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.readStatusFailedDetail'));
                    }
                  });
              }
            });
          }
        }))
      });
    }

    if (permissions?.canBulkEditMetadata) {
      items.push({
        label: this.t.translate('book.menuService.menu.setAgeRating'),
        icon: 'pi pi-user',
        items: [
          ...AGE_RATING_OPTIONS.map(option => ({
            label: option.label,
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.menuService.confirm.ageRatingMessage', {label: option.label, count}),
                header: this.t.translate('book.menuService.confirm.ageRatingHeader'),
                icon: 'pi pi-exclamation-triangle',
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.no'),
                accept: () => {
                  this.writeProgressService.show(this.t.translate('book.menuService.loading.settingAgeRating', {count}));
                  this.bookMetadataManageService.updateBooksMetadata({
                    bookIds: Array.from(selectedBooks),
                    ageRating: option.id
                  }).subscribe({
                      next: () => {
                        this.writeProgressService.complete(this.t.translate('book.menuService.toast.ageRatingUpdatedSummary'));
                        this.messageService.add({
                          severity: 'success',
                          summary: this.t.translate('book.menuService.toast.ageRatingUpdatedSummary'),
                          detail: this.t.translate('book.menuService.toast.ageRatingUpdatedDetail', {label: option.label}),
                          life: 2000
                        });
                      },
                      error: (err: HttpErrorResponse) => {
                        const apiError = err.error as APIException;
                        this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.updateFailedSummary'));
                      }
                    });
                }
              });
            }
          })),
          {
            separator: true
          },
          {
            label: this.t.translate('book.menuService.menu.clearAgeRating'),
            icon: 'pi pi-times',
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.menuService.confirm.clearAgeRatingMessage', {count}),
                header: this.t.translate('book.menuService.confirm.clearAgeRatingHeader'),
                icon: 'pi pi-exclamation-triangle',
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.no'),
                accept: () => {
                  this.writeProgressService.show(this.t.translate('book.menuService.loading.clearingAgeRating', {count}));
                  this.bookMetadataManageService.updateBooksMetadata({
                    bookIds: Array.from(selectedBooks),
                    clearAgeRating: true
                  }).subscribe({
                      next: () => {
                        this.writeProgressService.complete(this.t.translate('book.menuService.toast.ageRatingClearedSummary'));
                        this.messageService.add({
                          severity: 'success',
                          summary: this.t.translate('book.menuService.toast.ageRatingClearedSummary'),
                          detail: this.t.translate('book.menuService.toast.ageRatingClearedDetail'),
                          life: 2000
                        });
                      },
                      error: (err: HttpErrorResponse) => {
                        const apiError = err.error as APIException;
                        this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.updateFailedSummary'));
                      }
                    });
                }
              });
            }
          }
        ]
      });

      items.push({
        label: this.t.translate('book.menuService.menu.setContentRating'),
        icon: 'pi pi-shield',
        items: [
          ...Object.entries(CONTENT_RATING_LABELS).map(([value, label]) => ({
            label: label,
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.menuService.confirm.contentRatingMessage', {label, count}),
                header: this.t.translate('book.menuService.confirm.contentRatingHeader'),
                icon: 'pi pi-exclamation-triangle',
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.no'),
                accept: () => {
                  this.writeProgressService.show(this.t.translate('book.menuService.loading.settingContentRating', {count}));
                  this.bookMetadataManageService.updateBooksMetadata({
                    bookIds: Array.from(selectedBooks),
                    contentRating: value
                  }).subscribe({
                      next: () => {
                        this.writeProgressService.complete(this.t.translate('book.menuService.toast.contentRatingUpdatedSummary'));
                        this.messageService.add({
                          severity: 'success',
                          summary: this.t.translate('book.menuService.toast.contentRatingUpdatedSummary'),
                          detail: this.t.translate('book.menuService.toast.contentRatingUpdatedDetail', {label}),
                          life: 2000
                        });
                      },
                      error: (err: HttpErrorResponse) => {
                        const apiError = err.error as APIException;
                        this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.updateFailedSummary'));
                      }
                    });
                }
              });
            }
          })),
          {
            separator: true
          },
          {
            label: this.t.translate('book.menuService.menu.clearContentRating'),
            icon: 'pi pi-times',
            command: () => {
              this.confirmationService.confirm({
                message: this.t.translate('book.menuService.confirm.clearContentRatingMessage', {count}),
                header: this.t.translate('book.menuService.confirm.clearContentRatingHeader'),
                icon: 'pi pi-exclamation-triangle',
                acceptLabel: this.t.translate('common.yes'),
                rejectLabel: this.t.translate('common.no'),
                accept: () => {
                  this.writeProgressService.show(this.t.translate('book.menuService.loading.clearingContentRating', {count}));
                  this.bookMetadataManageService.updateBooksMetadata({
                    bookIds: Array.from(selectedBooks),
                    clearContentRating: true
                  }).subscribe({
                      next: () => {
                        this.writeProgressService.complete(this.t.translate('book.menuService.toast.contentRatingClearedSummary'));
                        this.messageService.add({
                          severity: 'success',
                          summary: this.t.translate('book.menuService.toast.contentRatingClearedSummary'),
                          detail: this.t.translate('book.menuService.toast.contentRatingClearedDetail'),
                          life: 2000
                        });
                      },
                      error: (err: HttpErrorResponse) => {
                        const apiError = err.error as APIException;
                        this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.updateFailedSummary'));
                      }
                    });
                }
              });
            }
          }
        ]
      });
    }

    // Shelf Actions
    if (permissions?.canManageLibrary || permissions?.admin) { // Assuming these permissions cover shelf management for books
       items.push({
         label: this.t.translate('book.menuService.menu.removeFromAllShelves'),
         icon: 'pi pi-bookmark-fill', // Or bookmark-slash
         command: () => {
           this.confirmationService.confirm({
             message: this.t.translate('book.menuService.confirm.unshelveMessage', {count}),
             header: this.t.translate('book.menuService.confirm.unshelveHeader'),
             icon: 'pi pi-exclamation-triangle',
             acceptLabel: this.t.translate('common.yes'),
             rejectLabel: this.t.translate('common.no'),
             accept: () => {
               this.pagedBookBrowserStateService.resolveBooksByIds(Array.from(selectedBooks))
                 .subscribe({
                   next: books => {
                     const allShelfIds = new Set<number>();
                     books.forEach(b => b.shelves?.forEach(shelf => {
                       if (shelf.id) {
                         allShelfIds.add(shelf.id);
                       }
                     }));

                     if (allShelfIds.size === 0) {
                       this.messageService.add({ severity: 'info', summary: this.t.translate('common.info'), detail: this.t.translate('book.menuService.toast.noBooksOnShelvesDetail') });
                       return;
                     }

                     this.writeProgressService.show(this.t.translate('book.menuService.loading.removingFromShelves', {count}));
                     this.bookService.updateBookShelves(selectedBooks, new Set(), allShelfIds)
                       .subscribe({
                         next: () => {
                           this.writeProgressService.complete(this.t.translate('book.menuService.toast.unshelveSuccessDetail'));
                           this.messageService.add({severity: 'success', summary: this.t.translate('common.success'), detail: this.t.translate('book.menuService.toast.unshelveSuccessDetail')});
                         },
                         error: () => {
                           this.writeProgressService.fail(this.t.translate('book.menuService.toast.unshelveFailedDetail'));
                         }
                       });
                   },
                   error: () => {
                     this.writeProgressService.fail(this.t.translate('book.menuService.toast.unshelveFailedDetail'));
                   }
                 });
             }
           });
         }
       });
    }

    if (permissions?.canBulkResetFableReadProgress) {
      items.push({
        label: this.t.translate('book.menuService.menu.resetFableProgress'),
        icon: 'pi pi-undo',
        command: () => {
          this.confirmationService.confirm({
            message: this.t.translate('book.menuService.confirm.resetFableMessage', {count}),
            header: this.t.translate('book.menuService.confirm.resetHeader'),
            icon: 'pi pi-exclamation-triangle',
            acceptLabel: this.t.translate('common.yes'),
            rejectLabel: this.t.translate('common.no'),
            accept: () => {
              this.writeProgressService.show(this.t.translate('book.menuService.loading.resettingFableProgress', {count}));
              this.bookService.resetProgress(Array.from(selectedBooks), ResetProgressTypes.FABLE)
                .subscribe({
                  next: () => {
                    this.writeProgressService.complete(this.t.translate('book.menuService.toast.progressResetSummary'));
                    this.messageService.add({
                      severity: 'success',
                      summary: this.t.translate('book.menuService.toast.progressResetSummary'),
                      detail: this.t.translate('book.menuService.toast.fableProgressResetDetail'),
                      life: 1500
                    });
                  },
                  error: (err: HttpErrorResponse) => {
                    const apiError = err.error as APIException;
                    this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.failedSummary'));
                  }
                });
            }
          });
        }
      });
    }

    if (permissions?.canBulkResetKoReaderReadProgress) {
      items.push({
        label: this.t.translate('book.menuService.menu.resetKOReaderProgress'),
        icon: 'pi pi-undo',
        command: () => {
          this.confirmationService.confirm({
            message: this.t.translate('book.menuService.confirm.resetKOReaderMessage', {count}),
            header: this.t.translate('book.menuService.confirm.resetHeader'),
            icon: 'pi pi-exclamation-triangle',
            acceptLabel: this.t.translate('common.yes'),
            rejectLabel: this.t.translate('common.no'),
            accept: () => {
              this.writeProgressService.show(this.t.translate('book.menuService.loading.resettingKOReaderProgress', {count}));
              this.bookService.resetProgress(Array.from(selectedBooks), ResetProgressTypes.KOREADER)
                .subscribe({
                  next: () => {
                    this.writeProgressService.complete(this.t.translate('book.menuService.toast.progressResetSummary'));
                    this.messageService.add({
                      severity: 'success',
                      summary: this.t.translate('book.menuService.toast.progressResetSummary'),
                      detail: this.t.translate('book.menuService.toast.koreaderProgressResetDetail'),
                      life: 1500
                    });
                  },
                  error: (err: HttpErrorResponse) => {
                    const apiError = err.error as APIException;
                    this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.failedSummary'));
                  }
                });
            }
          });
        }
      });
    }

    if (permissions?.canEditMetadata || permissions?.admin) {
      const books = this.bookService.getCurrentBookState().books ?? [];
      const selectedList = books.filter(b => selectedBooks.has(b.id));
      const hasUnstagedSelection = selectedList.length === 0 || selectedList.some(b => !b.staged);
      if (hasUnstagedSelection) {
        items.push({
          label: this.t.translate('book.menuService.menu.sendToStaging'),
          icon: 'pi pi-inbox',
          command: () => {
            const idsToStage = new Set(selectedBooks);
            this.confirmationService.confirm({
              message: this.t.translate('book.menuService.confirm.sendToStagingMessage', {count: idsToStage.size}),
              header: this.t.translate('book.menuService.confirm.sendToStagingHeader'),
              icon: 'pi pi-inbox',
              acceptLabel: this.t.translate('common.confirm'),
              rejectLabel: this.t.translate('common.cancel'),
              acceptButtonProps: {
                label: this.t.translate('common.confirm'),
                severity: 'info'
              },
              rejectButtonProps: {
                label: this.t.translate('common.cancel'),
                severity: 'secondary'
              },
              accept: () => {
                this.writeProgressService.show(this.t.translate('book.menuService.loading.sendingToStaging', {count: idsToStage.size}));
                this.bookService.stageForReview(idsToStage)
                  .subscribe({
                    next: () => {
                      this.writeProgressService.complete(this.t.translate('book.menuService.toast.sendToStagingSuccessDetail'));
                      this.messageService.add({
                        severity: 'success',
                        summary: this.t.translate('common.success'),
                        detail: this.t.translate('book.menuService.toast.sendToStagingSuccessDetail'),
                        life: 2000
                      });
                    },
                    error: (err: HttpErrorResponse) => {
                      const apiError = err.error as APIException;
                      this.writeProgressService.fail(apiError?.message || this.t.translate('book.menuService.toast.sendToStagingFailedDetail'));
                    }
                  });
              }
            });
          }
        });
      }
    }

    return items;
  }
}
