import {Component, ElementRef, HostBinding, Input, OnDestroy, OnInit, ViewChild, inject} from '@angular/core';
import {NavigationEnd, Router, RouterLink} from '@angular/router';
import {animate, state, style, transition, trigger} from '@angular/animations';
import {Subscription, Observable} from 'rxjs';
import {filter} from 'rxjs/operators';
import {MenuService} from './service/app.menu.service';
import {AsyncPipe, NgClass} from '@angular/common';
import {Ripple} from 'primeng/ripple';
import {Button} from 'primeng/button';
import {Menu} from 'primeng/menu';
import {UserService} from '../../../../features/settings/user-management/user.service';
import {DialogLauncherService} from '../../../services/dialog-launcher.service';
import {BookDialogHelperService} from '../../../../features/book/components/book-browser/book-dialog-helper.service';
import {IconDisplayComponent} from '../../../components/icon-display/icon-display.component';
import {Tooltip} from 'primeng/tooltip';
import {IconSelection} from '../../../service/icon-picker.service';
import {TranslocoPipe, TranslocoService} from '@jsverse/transloco';
import {MenuItem, MessageService} from 'primeng/api';
import {CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray} from '@angular/cdk/drag-drop';
import {LocalStorageService} from '../../../service/local-storage.service';
import {ThumbnailPrefetchService} from '../../../../features/book/service/thumbnail-prefetch.service';
import {DirectoryFilterService} from '../../../../features/book/service/directory-filter.service';
import {BookService} from '../../../../features/book/service/book.service';
import {WriteProgressService} from '../../../../shared/service/write-progress.service';

export interface AppMenuItem extends MenuItem {
  type?: string;
  visibilityKey?: string;
  class?: string;
  badgeClass?: string;
  hasDropDown?: boolean;
  hasCreate?: boolean;
  iconType?: 'PRIME_NG' | 'CUSTOM_SVG';
  menu?: AppMenuItem[];
  bookCount$?: Observable<number>;
  unhealthy$?: Observable<boolean>;
  unhealthyTooltip$?: Observable<string>;
  items?: AppMenuItem[];
  prefetchLibraryId?: number;
  showBookCount?: boolean;
  endActionIcon?: string;
  endActionTooltip?: string;
  endActionAriaLabel?: string;
  endActionCommand?: () => void;
  endActionClass?: string;
  activeMatch?: (url: string, queryParams: Record<string, unknown>) => boolean;
  onItemsReorder?: (items: AppMenuItem[]) => boolean | void;
  onCreate?: () => void;
  isPublicShelf?: boolean;
  isKoboShelf?: boolean;
  shelfId?: number;
}

@Component({
  // eslint-disable-next-line @angular-eslint/component-selector
  selector: '[app-menuitem]',
  templateUrl: './app.menuitem.component.html',
  styleUrls: ['./app.menuitem.component.scss'],
  imports: [
    RouterLink,
    NgClass,
    Ripple,
    AsyncPipe,
    Button,
    Menu,
    IconDisplayComponent,
    Tooltip,
    TranslocoPipe,
    CdkDropList,
    CdkDrag,
    CdkDragHandle
  ],
  animations: [
    trigger('children', [
      state('collapsed', style({
        height: '0'
      })),
      state('expanded', style({
        height: '*'
      })),
      transition('collapsed <=> expanded', animate('400ms cubic-bezier(0.86, 0, 0.07, 1)'))
    ])
  ]
})
export class AppMenuitemComponent implements OnInit, OnDestroy {
  @Input() item!: AppMenuItem;
  @Input() index!: number;
  @Input() @HostBinding('class.layout-root-menuitem') root!: boolean;
  @Input() parentKey!: string;
  @Input() menuKey!: string;
  @Input() reorderMode = false;
  @Input() childIndex = -1;
  @Input() childCount = 0;
  @Input() onMoveChild?: (index: number, direction: 'up' | 'down') => void;
  @ViewChild('linkRef') linkRef!: ElementRef<HTMLAnchorElement>;

  hovered = false;
  active = false;
  key = "";
  canManipulateLibrary = false;
  admin = false;
  expandedItems = new Set<string>();
  private readonly sectionExpandedKey = 'sidebarSectionExpanded';
  private touchStartX: number | null = null;
  private touchStartY: number | null = null;
  private suppressTapUntil = 0;

  get isRouteActive(): boolean {
    const queryParams = this.router.parseUrl(this.router.url).queryParams as Record<string, unknown>;
    if (this.item?.activeMatch) {
      return this.item.activeMatch(this.router.url, queryParams);
    }

    if (!this.item?.routerLink?.[0]) return false;
    if (this.router.url.split('?')[0] !== this.item.routerLink[0]) return false;
    // Don't highlight (e.g.) "All Books" when a Media Type filter is active —
    // those rows carry their own active state and should own the highlight.
    const filterParam = this.router.parseUrl(this.router.url).queryParams['filter'];
    if (typeof filterParam === 'string' &&
        (filterParam.includes('customMediaType:') || filterParam.includes('customBookType:'))) {
      return false;
    }
    return true;
  }

  private userStateSubscription: Subscription;
  menuSourceSubscription: Subscription;
  menuResetSubscription: Subscription;
  private routerSubscription: Subscription;

  public router = inject(Router);
  private menuService = inject(MenuService);
  private userService = inject(UserService);
  private dialogLauncher = inject(DialogLauncherService);
  private bookDialogHelperService = inject(BookDialogHelperService);
  private localStorageService = inject(LocalStorageService);
  private thumbnailPrefetchService = inject(ThumbnailPrefetchService);
  private directoryFilterService = inject(DirectoryFilterService);
  private bookService = inject(BookService);
  private writeProgressService = inject(WriteProgressService);
  private messageService = inject(MessageService);
  private translocoService = inject(TranslocoService);

  isShelfDragOver = false;

  constructor() {
    this.userStateSubscription = this.userService.userState$.subscribe(userState => {
      if (userState?.user) {
        this.canManipulateLibrary = userState.user.permissions.canManageLibrary;
        this.admin = userState.user.permissions.admin;
      }
    });

    this.menuSourceSubscription = this.menuService.menuSource$.subscribe(value => {
      Promise.resolve(null).then(() => {
        if (value.routeEvent) {
          this.active = (value.key === this.key || value.key.startsWith(this.key + '-')) ? true : false;
        } else {
          if (value.key !== this.key && !value.key.startsWith(this.key + '-')) {
            this.active = false;
          }
        }
      });
    });

    this.menuResetSubscription = this.menuService.resetSource$.subscribe(() => {
      this.active = false;
    });

    this.routerSubscription = this.router.events.pipe(filter(event => event instanceof NavigationEnd))
      .subscribe(() => {
        if (this.item.routerLink || this.item.activeMatch) {
          this.updateActiveStateFromRoute();
        }
      });
  }

  ngOnInit() {
    const rootKey = this.menuKey ? this.menuKey + '-' : '';
    this.key = this.parentKey ? this.parentKey + '-' + this.index : rootKey + String(this.index);
    if (this.shouldRestoreSectionExpanded() && this.readPersistedSectionExpanded() === false) {
      this.expandedItems.delete(this.key);
    } else {
      this.expandedItems.add(this.key);
    }
    if (this.item.routerLink) {
      this.updateActiveStateFromRoute();
    }
  }

  ngOnDestroy() {
    this.userStateSubscription?.unsubscribe();
    this.menuSourceSubscription?.unsubscribe();
    this.menuResetSubscription?.unsubscribe();
    this.routerSubscription?.unsubscribe();
  }

  toggleExpand(key: string) {
    if (this.reorderMode) {
      return;
    }

    if (this.expandedItems.has(key)) {
      this.expandedItems.delete(key);
    } else {
      this.expandedItems.add(key);
    }

    this.persistSectionExpanded(key);
  }

  isExpanded(key: string): boolean {
    return this.reorderMode || this.expandedItems.has(key);
  }

  onChildDrop(event: CdkDragDrop<AppMenuItem[]>): void {
    if (!this.reorderMode || !this.root || !this.item?.items || event.previousIndex === event.currentIndex) {
      return;
    }

    moveItemInArray(this.item.items, event.previousIndex, event.currentIndex);
    this.persistChildOrder();
  }

  moveChildOrder(index: number, direction: 'up' | 'down'): void {
    if (!this.reorderMode || !this.root || !this.item?.items) {
      return;
    }

    const targetIndex = direction === 'up' ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= this.item.items.length) {
      return;
    }

    moveItemInArray(this.item.items, index, targetIndex);
    this.persistChildOrder();
  }

  readonly moveChildOrderFn = (index: number, direction: 'up' | 'down'): void => {
    this.moveChildOrder(index, direction);
  };

  onChildMoveClick(direction: 'up' | 'down', event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.onMoveChild?.(this.childIndex, direction);
  }

  translateMenuKey(key: string): string {
    return this.translocoService.translate(`layout.menu.${key}`);
  }

  isChildSortable(): boolean {
    return this.reorderMode && this.root && !!this.item?.items?.length;
  }

  private shouldRestoreSectionExpanded(): boolean {
    return this.root && !!this.item?.hasDropDown && !!this.menuKey;
  }

  private readPersistedSectionExpanded(): boolean | null {
    if (!this.menuKey) {
      return null;
    }
    const saved = this.localStorageService.get<Record<string, boolean>>(this.sectionExpandedKey);
    if (!saved || typeof saved[this.menuKey] !== 'boolean') {
      return null;
    }
    return saved[this.menuKey];
  }

  private persistSectionExpanded(key: string): void {
    if (!this.shouldRestoreSectionExpanded() || key !== this.key) {
      return;
    }

    const saved = this.localStorageService.get<Record<string, boolean>>(this.sectionExpandedKey) ?? {};
    this.localStorageService.trySet(this.sectionExpandedKey, {
      ...saved,
      [this.menuKey]: this.expandedItems.has(key),
    });
  }

  private persistChildOrder(): void {
    if (this.item.onItemsReorder) {
      const result = this.item.onItemsReorder(this.item.items!);
      this.notifyReorderPersistence(result !== false);
      return;
    }

    this.notifyReorderPersistence(this.saveNestedOrder());
  }

  private notifyReorderPersistence(saved: boolean): void {
    if (saved) {
      this.messageService.add({
        severity: 'success',
        summary: this.translocoService.translate('layout.menu.reorderSaved'),
        detail: this.translocoService.translate('layout.menu.reorderSavedDetail'),
        life: 1600,
      });
      return;
    }

    this.messageService.add({
      severity: 'warn',
      summary: this.translocoService.translate('layout.menu.reorderSaveFailed'),
      detail: this.translocoService.translate('layout.menu.reorderSaveFailedDetail'),
      life: 4000,
    });
  }

  updateActiveStateFromRoute() {
    if (this.item.activeMatch?.(this.router.url, this.router.parseUrl(this.router.url).queryParams as Record<string, unknown>)) {
      this.menuService.onMenuStateChange({key: this.key, routeEvent: true});
      return;
    }

    const activeRoute = this.router.isActive(this.item.routerLink[0], {
      paths: 'exact',
      queryParams: 'ignored',
      matrixParams: 'ignored',
      fragment: 'ignored'
    });
    if (activeRoute) {
      this.menuService.onMenuStateChange({key: this.key, routeEvent: true});
    }
  }

  itemClick(event: Event) {
    if (this.reorderMode) {
      event.preventDefault();
      event.stopPropagation();
      return;
    }

    if (this.shouldSuppressTap()) {
      event.preventDefault();
      event.stopPropagation();
      return;
    }

    if (this.item.disabled) {
      event.preventDefault();
      return;
    }

    this.clearScopedDirectoryFilterIfReclickingActiveRoute();

    if (this.item.command) {
      this.item.command({originalEvent: event, item: this.item});
    }
    if (this.item.items) {
      this.active = !this.active;
    } else {
      this.active = true;
    }
    this.menuService.onMenuStateChange({key: this.key});
  }

  openDialog(item: AppMenuItem) {
    if (this.reorderMode) {
      return;
    }

    if (item.onCreate) {
      item.onCreate();
      return;
    }

    if (item.type === 'library' && this.canManipulateLibrary) {
      this.dialogLauncher.openLibraryCreateDialog();
    }
    if (item.type === 'magicShelf') {
      this.dialogLauncher.openMagicShelfCreateDialog();
    }
    if (item.type === 'shelf') {
      this.bookDialogHelperService.openShelfCreatorDialog();
    }
  }

  triggerLink(event?: Event) {
    if (this.reorderMode) {
      event?.preventDefault();
      event?.stopPropagation();
      return;
    }

    if (this.shouldSuppressTap()) {
      event?.preventDefault();
      event?.stopPropagation();
      return;
    }

    if (this.item.routerLink && !this.item.items && this.linkRef) {
      this.linkRef.nativeElement.click();
    }
  }

  shouldShowEntityMenu(): boolean {
    if (this.isUnshelvedItem()) {
      return false;
    }

    if (!this.item.menu?.length) {
      return false;
    }

    return this.item.type === 'Library'
      || this.item.type === 'Shelf'
      || this.item.type === 'magicShelfItem'
      || this.item.type === 'MediaType';
  }

  shouldShowCount(): boolean {
    if (this.item.showBookCount) {
      return true;
    }

    return (this.item.type === 'Library' && (!this.admin && !this.canManipulateLibrary))
      || this.item.type === 'All Books'
      || this.item.type === 'Physical Books'
      || this.item.type === 'Series'
      || this.item.type === 'Authors'
      || this.item.type === 'Notebook'
      || this.item.type === 'StoryArc'
      || this.isUnshelvedItem()
      || this.item.label === 'Kobo';
  }

  hasEndAction(): boolean {
    return !!this.item.endActionCommand && !!this.item.endActionIcon;
  }

  onEndActionClick(event: Event): void {
    event.preventDefault();
    event.stopPropagation();

    if (this.reorderMode || !this.item.endActionCommand) {
      return;
    }

    this.item.endActionCommand();
  }

  isUnshelvedItem(): boolean {
    const routerLink = Array.isArray(this.item.routerLink) ? this.item.routerLink[0] : this.item.routerLink;
    return routerLink === '/not-shelfed';
  }

  canShowEntityMenu(): boolean {
    if (!this.item.menu?.length) {
      return false;
    }
    if (this.isUnshelvedItem() || this.item.label === 'Kobo') {
      return false;
    }
    if (this.item.type === 'Library' && !this.admin && !this.canManipulateLibrary) {
      return false;
    }
    return this.shouldShowEntityMenu();
  }

  onTouchStart(event: TouchEvent): void {
    this.prefetchItemThumbnails();

    const touch = event.touches[0];
    if (!touch) {
      return;
    }
    this.touchStartX = touch.clientX;
    this.touchStartY = touch.clientY;
  }

  onTouchEnd(event: TouchEvent): void {
    const touch = event.changedTouches[0];
    if (touch && this.touchStartX != null && this.touchStartY != null) {
      const deltaX = Math.abs(touch.clientX - this.touchStartX);
      const deltaY = Math.abs(touch.clientY - this.touchStartY);
      if (deltaX > 8 || deltaY > 8) {
        this.suppressTapUntil = Date.now() + 250;
      }
    }
    this.touchStartX = null;
    this.touchStartY = null;
  }

  onTouchCancel(): void {
    this.touchStartX = null;
    this.touchStartY = null;
    this.suppressTapUntil = Date.now() + 250;
  }

  formatCount(count: number | null | undefined): string {
    if (count == null) return '0';
    if (count >= 1000) return Math.floor(count / 1000) + 'K';
    return count.toString();
  }

  getIconSelection(): IconSelection | null {
    if (!this.item.icon) return null;

    return {
      type: this.item.iconType || 'PRIME_NG',
      value: this.item.icon
    };
  }

  private saveNestedOrder(): boolean {
    if (!this.menuKey || !Array.isArray(this.item?.items)) {
      return false;
    }

    const order = this.item.items!.map((child: AppMenuItem) => this.getItemOrderId(child));
    return this.localStorageService.trySet(`sidebarNestedOrder_${this.menuKey}`, order);
  }

  private getItemOrderId(item: AppMenuItem): string {
    const link = Array.isArray(item?.routerLink) ? item.routerLink[0] : item?.routerLink;
    return String(link ?? item?.label ?? '');
  }

  prefetchItemThumbnails(): void {
    if (this.item.prefetchLibraryId == null) {
      return;
    }

    this.thumbnailPrefetchService.prefetchLibrary(this.item.prefetchLibraryId);
  }

  private shouldSuppressTap(): boolean {
    return Date.now() < this.suppressTapUntil;
  }

  private clearScopedDirectoryFilterIfReclickingActiveRoute(): void {
    const routerLink = Array.isArray(this.item.routerLink) ? this.item.routerLink[0] : this.item.routerLink;
    if (!routerLink || this.item.items) {
      return;
    }

    const currentPath = this.router.url.split('?')[0].split('#')[0];
    if (currentPath !== routerLink) {
      return;
    }

    this.directoryFilterService.clearScope(this.directoryFilterService.getScopeKeyFromUrl(currentPath));
  }

  onDragOver(event: DragEvent): void {
    if (this.reorderMode || (this.item.type !== 'Shelf' && !this.item.shelfId && this.item.type !== 'MediaType')) {
      return;
    }
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'move';
    }
    this.isShelfDragOver = true;
  }

  onDragLeave(_event: DragEvent): void {
    this.isShelfDragOver = false;
  }

  onDrop(event: DragEvent): void {
    if (this.reorderMode || (this.item.type !== 'Shelf' && !this.item.shelfId && this.item.type !== 'MediaType')) {
      return;
    }
    event.preventDefault();
    this.isShelfDragOver = false;

    const rawData = event.dataTransfer?.getData('text/plain');
    if (!rawData) {
      return;
    }

    try {
      const data = JSON.parse(rawData);
      const rawBookIds = data.bookIds;
      const sourceShelfId = data.sourceShelfId ? Number(data.sourceShelfId) : null;
      
      console.log('[OnDrop Menuitem] Raw drag data parsed:', data);
      
      if (Array.isArray(rawBookIds) && rawBookIds.length > 0) {
        const bookIds = rawBookIds.map(id => Number(id));
        console.log('[OnDrop Menuitem] Coerced bookIds to drop:', bookIds);

        if (this.item.type === 'Shelf' || this.item.shelfId) {
          const shelfId = this.item.shelfId ? Number(this.item.shelfId) : null;
          if (shelfId) {
            if (shelfId === sourceShelfId) {
              console.log('[OnDrop Menuitem] Source and target shelf are the same, ignoring.');
              return;
            }
            this.assignBooksToShelf(bookIds, shelfId, sourceShelfId);
          }
        } else if (this.item.type === 'MediaType' && this.item.label) {
          this.assignBooksToMediaType(bookIds, this.item.label);
        }
      }
    } catch (e) {
      console.error('Failed to parse drag data', e);
    }
  }

  private assignBooksToShelf(bookIds: number[], shelfId: number, sourceShelfId?: number | null): void {
    const idsToAssign = new Set([shelfId]);
    const idsToUnassign = sourceShelfId ? new Set([sourceShelfId]) : new Set<number>();

    this.writeProgressService.show(this.translocoService.translate('book.shelfAssigner.loading.updatingShelves', { count: bookIds.length }));
    this.bookService.updateBookShelves(new Set(bookIds), idsToAssign, idsToUnassign)
      .subscribe({
        next: () => {
          this.bookService.refreshBooks().subscribe();
          this.writeProgressService.complete(this.translocoService.translate('book.shelfAssigner.toast.updateSuccessDetail'));
          this.messageService.add({severity: 'info', summary: this.translocoService.translate('common.success'), detail: this.translocoService.translate('book.shelfAssigner.toast.updateSuccessDetail')});
        },
        error: () => {
          this.writeProgressService.fail(this.translocoService.translate('book.shelfAssigner.toast.updateFailedDetail'));
        }
      });
  }

  private assignBooksToMediaType(bookIds: number[], mediaType: string): void {
    this.writeProgressService.show('Updating Media Type...');
    this.bookService.updateFileType(new Set(bookIds), mediaType)
      .subscribe({
        next: () => {
          this.bookService.refreshBooks().subscribe();
          this.writeProgressService.complete('Media Type updated successfully.');
          this.messageService.add({severity: 'info', summary: this.translocoService.translate('common.success'), detail: 'Media Type updated.'});
        },
        error: () => {
          this.writeProgressService.fail('Failed to update Media Type.');
        }
      });
  }

}
