import {Routes} from '@angular/router';
import {BookBrowserComponent} from './features/book/components/book-browser/book-browser.component';
import {AppLayoutComponent} from './shared/layout/component/layout-main/app.layout.component';
import {LoginComponent} from './shared/components/login/login.component';
import {AuthGuard} from './core/security/auth.guard';
import {ChangePasswordComponent} from './shared/components/change-password/change-password.component';
import {SetupComponent} from './shared/components/setup/setup.component';
import {SetupGuard} from './shared/components/setup/setup.guard';
import {SetupRedirectGuard} from './shared/components/setup/setup-redirect.guard';
import {EmptyComponent} from './shared/components/empty/empty.component';
import {OidcCallbackComponent} from './core/security/oidc-callback/oidc-callback.component';
import {MainDashboardComponent} from './features/dashboard/components/main-dashboard/main-dashboard.component';
import {LoginGuard} from './shared/components/setup/login.guard';
import {BookdropGuard} from './core/security/guards/bookdrop.guard';

import {EditMetadataGuard} from './core/security/guards/edit-metdata.guard';
import {PhysicalBooksPageComponent} from './features/book/components/physical-books-page/physical-books-page.component';
import {SeriesBrowserComponent} from './features/series-browser/components/series-browser/series-browser.component';
import {SeriesPageComponent} from './features/book/components/series-page/series-page.component';
import {AuthorBrowserComponent} from './features/author-browser/components/author-browser/author-browser.component';
import {AuthorDetailComponent} from './features/author-browser/components/author-detail/author-detail.component';
export const routes: Routes = [
  {
    path: '',
    canActivate: [SetupRedirectGuard],
    pathMatch: 'full',
    component: EmptyComponent
  },
  {
    path: 'setup',
    component: SetupComponent,
    canActivate: [SetupGuard]
  },
  {path: 'oauth2-callback', component: OidcCallbackComponent},
  {
    path: '',
    component: AppLayoutComponent,
    children: [
      {path: 'dashboard', component: MainDashboardComponent, canActivate: [AuthGuard]},
      {path: 'all-books', component: BookBrowserComponent, canActivate: [AuthGuard]},
      {path: 'staging', component: BookBrowserComponent, canActivate: [AuthGuard, EditMetadataGuard]},
      {path: 'physical-books', component: PhysicalBooksPageComponent, canActivate: [AuthGuard]},
      {path: 'settings', loadComponent: () => import('./features/settings/settings.component').then(m => m.SettingsComponent), canActivate: [AuthGuard]},
      {path: 'library/:libraryId/books', component: BookBrowserComponent, canActivate: [AuthGuard]},
      {path: 'shelf/:shelfId/books', component: BookBrowserComponent, canActivate: [AuthGuard]},
      {path: 'not-shelfed', component: BookBrowserComponent, canActivate: [AuthGuard]},
      {path: 'series', component: SeriesBrowserComponent, canActivate: [AuthGuard]},
      {path: 'series/:seriesName', component: SeriesPageComponent, canActivate: [AuthGuard]},
      {path: 'story-arcs', loadComponent: () => import('./features/story-arc/components/story-arc-browser/story-arc-browser.component').then(m => m.StoryArcBrowserComponent), canActivate: [AuthGuard]},
      {path: 'story-arc/:arcName', loadComponent: () => import('./features/story-arc/components/story-arc-page/story-arc-page.component').then(m => m.StoryArcPageComponent), canActivate: [AuthGuard]},
      {path: 'authors', component: AuthorBrowserComponent, canActivate: [AuthGuard]},
      {path: 'author/:authorId', component: AuthorDetailComponent, canActivate: [AuthGuard]},
      {path: 'magic-shelf/:magicShelfId/books', component: BookBrowserComponent, canActivate: [AuthGuard]},
      {path: 'book/:bookId', loadComponent: () => import('./features/metadata/component/book-metadata-center/book-metadata-center.component').then(m => m.BookMetadataCenterComponent), canActivate: [AuthGuard]},
      {path: 'bookdrop', loadComponent: () => import('./features/bookdrop/component/bookdrop-file-review/bookdrop-file-review.component').then(m => m.BookdropFileReviewComponent), canActivate: [BookdropGuard]},
      {path: 'metadata-manager', loadComponent: () => import('./features/metadata/component/metadata-manager/metadata-manager.component').then(m => m.MetadataManagerComponent), canActivate: [EditMetadataGuard]},
      {path: 'library-stats', loadChildren: () => import('./features/stats/stats.routes').then(m => m.LIBRARY_STATS_ROUTES)},
      {path: 'reading-stats', loadChildren: () => import('./features/stats/stats.routes').then(m => m.USER_STATS_ROUTES)},
      {path: 'notebook', loadComponent: () => import('./features/notebook/components/notebook/notebook.component').then(m => m.NotebookComponent), canActivate: [AuthGuard]},
    ]
  },
  {
    path: 'pdf-reader/book/:bookId',
    loadComponent: () => import('./features/readers/pdf-reader/pdf-reader.component').then(m => m.PdfReaderComponent),
    canActivate: [AuthGuard]
  },
  {
    path: 'ebook-reader/book/:bookId',
    loadComponent: () => import('./features/readers/ebook-reader/ebook-reader.component').then(m => m.EbookReaderComponent),
    canActivate: [AuthGuard]
  },
  {
    path: 'cbx-reader/book/:bookId',
    loadComponent: () => import('./features/readers/cbx-reader/cbx-reader.component').then(m => m.CbxReaderComponent),
    canActivate: [AuthGuard]
  },
  {
    path: 'audiobook-player/book/:bookId',
    loadComponent: () => import('./features/readers/audiobook-player/audiobook-player.component').then(m => m.AudiobookPlayerComponent),
    canActivate: [AuthGuard]
  },
  {
    path: 'login',
    component: LoginComponent,
    canActivate: [LoginGuard]
  },
  {
    path: 'change-password',
    component: ChangePasswordComponent
  },
  {
    path: '**',
    redirectTo: 'login',
    pathMatch: 'full'
  }
];
