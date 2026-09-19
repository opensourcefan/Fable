# Fable Codebase Reference

The workspace root is `/home/michael/fable`. When working with file paths, remember they are relative to this root unless stated otherwise.

## Workspace Layout

| Path | Purpose |
|---|---|
| `fable-api/` | Spring Boot 4.0 backend (Java 25) |
| `fable-ui/` | Angular 21 frontend |
| `assets/` | Screenshots and static assets |
| `bookdrop/` | Bookdrop inbox directory (mounted in Docker) |
| `books/` | Book files directory (mounted in Docker) |
| `data/` | Runtime data: covers, thumbnails, AI models, app-data |
| `docker/` | Docker build context |
| `docs/` | User documentation (PDF, HTML) |
| `example-chart/`, `example-docker/`, `example-podman/` | Deployment examples |
| `patches/` | Patch files for upstream fixes |
| `scripts/` | Utility scripts |
| `shared/` | Shared resources across containers |
| `mariadb/` | MariaDB data volume (Docker mount) |
| `.github/workflows/` | CI/CD pipelines |
| `.continue/rules/` | Continue.dev agent rules (this file lives here) |
| `.continue/agents/` | Continue.dev agent configs (Deepseek4 Flash, Deepseek4 Pro) |
| `.ghcprules/` | Agent rules, preferences, and memory bank |
| `.vscode/` | VS Code workspace settings |

## Git Integration

| Detail | Value |
|---|---|
| Primary remote | `origin` → `https://github.com/opensourcefan/Fable.git` |
| Alias remote | `fable3` → same URL |
| Scratch remote | `scratch_repo` (local scratch remote, when configured) |
| Default branch | `develop` |
| Current branch | `develop` |
| Other local branches | `Experimental`, `ai-panel-test`, `sandbox-phase4`, `testing` |
| Latest documented tag | `v4.16.51` |
| GitHub owner | `opensourcefan` |

### Branch naming conventions

- `develop` — main development branch
- `Experimental` — experimental features
- `ai-panel-test` — AI panel detection testing
- `sandbox-phase4` — sandboxed work
- `testing` — testing branch
- Dependabot branches use pattern: `dependabot/<ecosystem>/<package-name>`

### CI/CD Workflows

- `develop-pipeline.yml` — builds and pushes on develop commits (AI Panel/Search publish only when their `docker/` contexts change)
- `master-pipeline.yml` — production pipeline (same AI image path gating)
- `tag-pipeline.yml` — tagged releases (same AI image path gating)
- `migrations-check.yml` — validates Flyway migrations
- `dependabot-auto-merge.yml` — auto-merges safe dependency bumps

## Application Architecture

### Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Backend | Spring Boot | 4.0.6 |
| Java | JDK | 25 (preview enabled) |
| Database | MariaDB | 11 |
| ORM | Hibernate | 7.3.2 |
| Migrations | Flyway | 12.5.0 (MySQL variant) |
| Auth | Spring Security + JJWT | 0.13.0 |
| Frontend | Angular | 21.2.11 |
| UI Kit | PrimeNG | 21.1.5 |
| Test (API) | JUnit 5 + JaCoCo | Gradle test task |
| Test (UI) | Vitest + jsdom | 4.1.4 |
| Lint (UI) | Angular ESLint | 21.3.1 |
| Container | Docker + Docker Compose | — |

### Key Backend Dependencies

- Jackson — JSON serialization (via Spring Boot)
- Lombok 1.18.46 — boilerplate reduction
- MapStruct 1.6.3 — DTO/entity mapping
- PDFBox 3.0.7 — PDF processing
- TwelveMonkeys ImageIO 3.13.1 — image format support (JPEG, TIFF, WebP, BMP)
- epub4j-core 4.2.3 — EPUB reading
- jaudiotagger 2.0.19 — audiobook metadata
- junrar 7.5.10 — RAR extraction
- jsoup 1.22.2 — HTML scraping
- OWASP Dependency-Check 12.2.1 — CVE scanning (fails on CVSS ≥ 9.0)
- Reactor Core — reactive streams

### Key Frontend Dependencies

- @stomp/rx-stomp 2.3.0 — WebSocket client
- @jsverse/transloco 8.3.0 — i18n
- chart.js 4.5.1 — charts
- ngx-extended-pdf-viewer 25.6.4 — PDF reader; primary toolbar uses wide Contents/Thumbs nav, rotate beside zoom, Annotate + More labeled menus (pan/select/print/links/theme); layout modes still use ngx secondary Tools overflow on tablet; Phone Mode (≤768) uses a non-overlapping flex toolbar with compact nav buttons so clusters do not mash; Phone Mode also gets tap-zone/swipe page navigation in single-page, Book, and horizontal modes; Phone zoom cluster is − / ⋮ / + with a page-size fit menu (fit page/width/auto/actual) and true-centered middle chrome (margin-auto, no transform so fixed menus are not clipped); Phone More (⋯) holds bookmark (first) + annotate actions so they do not crowd zoom, and turns bookmark-red when the current page is bookmarked; Phone toolbar dropdowns match the hamburger Contents sidebar panel colors
- ng-lazyload-image 9.1.3 — lazy loading
- dompurify 3.4.2 — XSS sanitization
- primeicons 7.0.0 — icon library
- date-fns 4.1.0 — date utilities

### Backend Package Structure

```
org.fable
├── app/              — App-layer controllers, DTOs, services, specs
├── config/           — Spring config (security, filters, OIDC)
├── context/          — Application context helpers
├── controller/       — REST controllers
├── convertor/        — JPA attribute converters
├── crons/            — Scheduled tasks
├── exception/        — Custom exceptions
├── interceptor/      — HTTP interceptors
├── mapper/           — MapStruct mappers (custom, komga, v2)
├── model/
│   ├── dto/          — Data transfer objects (request, response, settings, kobo, komga, ai, sidecar)
│   ├── entity/       — JPA entities
│   └── enums/        — Enumerations
├── repository/       — Spring Data JPA repositories
├── service/          — Business logic services
│   ├── ai/           — AI panel detection
│   ├── appsettings/  — Application settings management
│   ├── audit/        — Audit logging
│   ├── book/         — Book operations
│   ├── bookdrop/     — Bookdrop inbox
│   ├── customfont/   — Custom font management
│   ├── email/        — Email notifications
│   ├── event/        — Event system
│   ├── file/         — File operations
│   ├── fileprocessor/— File processing pipeline
│   ├── hardcover/    — Hardcover.app integration
│   ├── kobo/         — Kobo device proxy & sync
│   ├── komga/        — Komga API compatibility
│   ├── koreader/     — KOReader integration
│   ├── library/      — Library management
│   ├── metadata/     — Metadata fetch/write/extract (parsers, writers, sidecar)
│   ├── migration/    — Data migration framework
│   ├── monitoring/   — File system monitoring
│   ├── oidc/         — OpenID Connect
│   ├── opds/         — OPDS feed generation
│   ├── progress/     — Reading progress tracking
│   ├── reader/       — Reader services
│   ├── recommender/  — Book recommendations
│   ├── restriction/  — Content restrictions
│   ├── security/     — Security utilities
│   ├── task/         — Background task management
│   ├── upload/       — File upload handling
│   ├── user/         — User management
│   └── watcher/      — File system watchers
├── task/             — Task definitions and execution
└── util/             — Utility classes (epub, kobo, koreader)
```

### Frontend Package Structure

```
fable-ui/src/app/
├── core/             — App initialization, security guards, core services
│   ├── config/       — App configuration
│   ├── security/     — Auth guards, interceptors, secure-src directive
│   ├── services/     — Core singleton services
│   └── testing/      — Test helpers
├── features/         — Feature modules
│   ├── author-browser/   — Author browsing and editing
│   ├── book/             — Book views, filters, sorts
│   ├── bookdrop/         — Bookdrop upload UI
│   ├── dashboard/        — Dashboard/home page
│   ├── library-creator/  — Library creation wizard
│   ├── magic-shelf/      — Magic shelf rules editor
│   ├── metadata/         — Metadata center (viewer, editor, picker, searcher)
│   ├── notebook/         — Notebook feature
│   ├── readers/          — EPUB, PDF, CBX, audiobook readers (shared bookmark left-panel pattern except CBX chrome)
│   ├── series-browser/   — Series browsing
│   ├── settings/         — Settings pages
│   └── stats/            — Reading statistics
└── shared/           — Shared modules
    ├── components/   — Reusable UI components
    ├── constants/    — App constants
    ├── directives/   — Shared directives
    ├── helpers/      — Helper functions
    ├── layout/       — App shell, sidebar, toolbar, theme
    ├── metadata/     — Shared metadata components
    ├── model/        — Data models/interfaces
    ├── service/      — Shared services (API, auth, app settings, WebSocket)
    ├── styles/       — Global styles
    ├── util/         — Utility functions
    └── websocket/    — WebSocket services
```

## Build & Run Commands

### Backend (fable-api/)

```bash
./gradlew build              # Full build with tests
./gradlew bootRun            # Run locally
./gradlew test               # Run tests only
./gradlew jacocoTestReport   # Coverage report
./gradlew dependencyCheckAnalyze  # OWASP CVE scan
```

### Frontend (fable-ui/)

```bash
npm run dev          # Development server with hot reload
npm run build        # Production build
npm run test         # Run Vitest tests
npm run lint         # Run ESLint
```

### Docker (root)

```bash
docker compose -f dev.docker-compose.yml up -d   # Development stack
docker compose -f docker-compose.yml up -d       # Production stack
docker compose -f testing-docker-compose.yml up -d  # Testing stack
```

### Version Numbers

- Backend/frontend version: `4.16.44` (in `fable-api/build.gradle` and `fable-ui/package.json`)
- Latest documented git tag: `v4.16.51`

### Documentation Parity

- Canonical chapter sources: `fable-ui/public/docs/guide/sec1.html` through `sec30.html`
- Canonical Guide Home and sidebar navigation: `fable-ui/public/docs/guide/index.html`
- Generated single-page guide: `fable-ui/public/docs/Fable-Familiarization-Guide.html`
- Synchronize with `python3 scripts/validate-familiarization-guide.py --sync`, then validate again without `--sync` (local/manual; the GitHub Actions guide-validation workflow was removed).
- Update the guide cover and newest Section 30 maintenance entry together. The validator also checks backend/frontend app-version agreement and local README links.
- Review README guidance whenever setup, delivery, backup/recovery, authentication, or deployment paths change.

## Code Conventions

### Java

- Lombok is used extensively — `@Slf4j`, `@RequiredArgsConstructor`, `@Builder` on entities
- Constructor injection preferred over field injection (use `@RequiredArgsConstructor`)
- Rate limiting for external API calls via `AtomicLong lastRequestTime` + `Thread.sleep()`
- SSRF defense via DNS resolution + internal-IP blocking in `FileService.downloadImageFromUrlInternal()`
- Path sanitization via `StringUtils.cleanPath()`, `Path.getFileName()`, and regex-based filename filters
- Metadata parsers follow a pattern: fixed `BASE_URL`, user input in query params, ObjectMapper for response parsing
- Services use interfaces (e.g., `BookParser`, `MetadataWriter`, `AuthorParser`)
- XML processing uses `SecureXmlUtils.createSecureDocumentBuilder()` to prevent XXE

### TypeScript/Angular

- Standalone components — no NgModules (Angular 19+ pattern)
- Signals for reactive state (Angular 19+)
- PrimeNG for UI components (p-table, p-dialog, p-image, etc.)
- Transloco for i18n (`t()` function in templates, `TranslocoService` in components)
- Vitest + jsdom for unit tests (not Jasmine/Karma)
- ESLint flat config (eslint 10.x with typescript-eslint 8.x)

### UI/Scrollbar Conventions

- Global scrollbar styles live in `fable-ui/src/styles.scss` — all per-component scrollbar overrides have been removed
- Uses PrimeNG theme CSS variables for theme-aware colors:
  - Thumb: `var(--p-surface-600)`, Hover: `var(--p-surface-500)`
  - Track: `transparent`, Size: 8px (both width and height for horizontal support)
  - Firefox: `scrollbar-color: var(--p-surface-600) transparent` / `scrollbar-width: thin`
- Some components intentionally hide scrollbars with `scrollbar-width: none` (horizontal scrollers in book-browser, tag-scroller in metadata-viewer, mobile sidebar popover, mobile layout menu)

### Responsive/Mobile UX

The app has a dedicated mobile UX system built around three services:

**MobileUxService** (`core/services/mobile-ux.service.ts`)
- Tracks device breakpoint via `BehaviorSubject<DeviceBreakpoint>`: `'mobile' | 'mobile-tablet' | 'desktop'`
- Breakpoints: mobile ≤ 767px, tablet 768-1024px, desktop ≥ 1025px (overridable via UiPreferences; `auto-shape` uses portrait→tablet / landscape→desktop above phone width)
- Exposes `breakpoint$` observable for reactive component adjustments
- Syncs `body.layout-phone` / `layout-tablet` / `layout-desktop` and optional `body.header-bottom`
- Syncs `body.touch-digitizer` when `hasTouchInput && !isPhone` (tablet + desktop-touch only; never under Phone Mode)
- Header bottom: phone uses `bl-header-position`; tablet uses independent `bl-tablet-header-position`; never on desktop
- Has stub `registerBackNavigation()` for hardware back button support

**Touch press feedback** (`styles.scss` + `body.touch-digitizer`)
- Tablet / desktop-touch controls use a theme-primary inset wash + light ring on `:active` (replaces inconsistent darken-only / missing press styles)
- Phone Mode keeps the older global scale + brightness press rule; drag/resize handles are excluded from the wash
- Do not gate this on `(pointer: coarse)` — fine-pointer touch tablets (e.g. Duet) still get `touch-digitizer`

**MobileBackNavigationService** (`shared/service/mobile-back-navigation.service.ts`)
- Manages a popstate-based back stack for mobile overlay/panel navigation
- `register(close: () => void): MobileBackHandle` — pushes a history state and returns a handle with `release()` to unwind
- Uses `MobileBackHandle.release(removeHistoryEntry?: boolean)` for cleanup
- Detection: shortEdge < 768px AND longEdge ≤ 1200px
- Used by topbar sidebar, directory panel, and overflow menus

**Layout integration**
- `app.layout.component.ts` applies CSS class `layout-mobile-active` when `staticMenuMobileActive` is true
- `< 992px` — sidebar slides off-screen, mask overlay shown when active
- `< 768px` — main container padding collapses to `4.4rem 0 0` (no side padding)
- `_responsive.scss` handles all layout breakpoints (992px, 768px, 1960px)
- `_topbar.scss` hides the logo on `≤ 991px` and styles `.mobile-sidebar-popover` popover content (16rem wide, scrollbar hidden, touch-optimized)
- Mobile left sidebar (`app.menuitem`): dropdown section headers (`.root-item-with-dropdown`) toggle expand/collapse on the full header tap — including Story Arcs, which uses a plain heading label like Libraries/Shelves; section destinations (Story Arcs → `/story-arcs`) use a sibling `.sidebar-heading-nav` control so navigation does not block collapse; create (+) does not toggle
- Sidebar reorder mode uses handle-only CDK drag (`cdkDragHandle` on `.sidebar-drag-handle`) plus up/down buttons so mobile vertical scroll remains usable; section/row order and media-type row order persist in device-browser localStorage (`sidebarSectionOrder`, `sidebarNestedOrder_*`, `sidebarBookTypeOrder`)
- Left sidebar section heading expand/collapse state persists in device-browser localStorage (`sidebarSectionExpanded`, keyed by menu section such as `library` / `shelf`)
- `.mobile-right-dir-trigger` in `_menu.scss` for mobile directory panel

**Desktop-touch overlay dismiss** (`shared/util/overlay-dismiss.util.ts`)
- `GhostClickGuard` + `shouldDismissOverlay()` ignore synthetic follow-up pointer/click events for ~400ms after a reader overlay opens
- Used by CBX/ebook quick settings, header menus, sidebars, note/shortcuts/settings dialogs, PDF bookmark dialog, and selection popup — prevents flash-closed menus on Linux/Chromium tablets that emulate a mouse under touch

**Desktop-touch book-card TieredMenu** (`book-card.component`)
- On tablet/desktop touch digitizers (`isTouchDigitizerChrome`), `autoDisplay` is false so Delete/Metadata/More Actions open on click
- Avoids PrimeNG hover-open + synthetic-click collapse race; Phone Mode keeps hover `autoDisplay` (≤960px accordion path unchanged)

**Reader browser page-zoom recovery** (`shared/util/visual-viewport.util.ts`)
- Desktop-touch / tablet readers acquire a temporary `maximum-scale=1` viewport lock so Chromium pinch cannot leave `visualViewport.scale > 1` (hides fixed chrome / “expanded” library)
- Fullscreen enter/exit and 3-finger kiosk fullscreen call `resetBrowserPageZoom` via `clearFullscreenTransientPointerUi`
- Phone Mode does not acquire the lock

### Key DTOs

**AppBookSummary** (`app/dto/AppBookSummary.java`) — lightweight book summary DTO for list/grid views, mapped by `AppBookMapper.toSummary()`. Contains 29 core fields plus 28 extended metadata fields:
- Primary file info: `primaryFileId`, `primaryFileName`, `fileSizeKb`
- Publication: `publisher`, `publishedDate`, `pageCount`, `language`, `narrator`
- IDs: `isbn13`, `isbn10`
- Ratings/counts: `amazonRating/ReviewCount`, `goodreadsRating/ReviewCount`, `hardcoverRating/ReviewCount`, `ranobedbRating`, `lubimyczytacRating`, `audibleRating/ReviewCount`
- Taxonomies: `categories`, `tags`, `moods` (alphabetically sorted lists of names)
- Metadata state: `allMetadataLocked`, `ageRating`, `contentRating`, `metadataMatchScore`

### Testing

- API tests in `fable-api/src/test/java/org/fable/` — JUnit 5, focused on mappers, converters, utilities
- UI tests in `fable-ui/src/app/**/*.spec.ts` — Vitest + jsdom, component-level with DOM assertions
- Test exclusions — `fable-api/src/test/java/**` excluded from VS Code problem reporting

## Security Architecture

### Authentication Flow

1. JWT tokens for main API (`/api/**`) — handled by `JwtAuthenticationFilter`
2. Basic Auth for OPDS (`/api/v1/opds/**`) and Komga (`/komga/api/**`) endpoints
3. Custom auth filters for Kobo (`KoboAuthFilter`), KOReader (`KoreaderAuthFilter`)
4. Cover/Media JWT for image endpoints — short-lived tokens via URL params
5. OIDC support via Spring Security OAuth2 client

### CSRF

Disabled on all security filter chains — by design. All API endpoints are stateless (JWT/Basic Auth). This is consistent with Spring Security best practices for non-cookie-based auth.

### Path Traversal Defenses

- `IconService.normalizeFilename()` — regex `[^a-zA-Z0-9._-]` → `_`
- `FileUploadService.getValidatedFileName()` — `StringUtils.cleanPath()` + `getFilename()`, rejects `..`
- `PathService` — blocklist of sensitive OS paths + symlink resolution
- `CbxConversionService.extractFileName()` — `Path.of(entryPath).getFileName()` strips directory components

### SSRF Defenses

- `FileService.downloadImageFromUrlInternal()` — full DNS resolution + internal-IP blocking (loopback, site-local, link-local, IPv4-mapped IPv6, ULA)
- All metadata parsers use hardcoded API base URLs

## ISBN-First Metadata Import

| Resource | Path |
|---|---|
| Cursor rule (locked decisions + mitigations) | `.cursor/rules/isbn-first-metadata-import.mdc` |
| Feasibility report | `~/Desktop/2026-07-20-AI_Search_Metadata_Import_Feasibility_Report.html` |
| Regression / Phone Mode investigation | `~/Desktop/2026-07-20-ISBN_Metadata_Regression_and_Mode_Design.html` |

**Status (v4.16.5+):** Core discovery + multi-pass fill shipped.
- `ParserUtils` checksum validation + candidate extraction
- `IsbnDiscoveryService` front-matter scan (PDF/EPUB; CBX OCR soft-fail)
- `IsbnMetadataFillService` multi-pass merge + scoped auto-apply + write-back gate
- Verified ISBN with empty providers: clear unlocked fields and apply ISBN (no phantom review)
- Broader ISBN provider chain (all configured field providers + Google fallback)
- `IsbnDiscoveryTask` live progress from start, proposal review queue, auto-stage for review
- Staging header triage tabs: **Staged / Review / Completed** (`GET /api/metadata/tasks/staging-triage`)
- Settings keys + Metadata Settings UI section
- Bookdrop hook when `isbnDiscoveryEnabled` + `isbnDiscoveryOnBookdrop`
- Tablet/desktop card + bulk menu: “ISBN Discovery & Fill” (hidden in Phone Mode)
- `isbn_verified` / `isbn_written_to_file` columns (V163)
- Persistent Staged-inbox ISBN exception state on `book` (V164): amber `NOT_FOUND`, red `ERROR`, checked time/detail tooltip; successful retry and full metadata wipe clear prior status. Badge rendering stays Staging-only and is hard-disabled in Phone Mode.
- `clearUnlockedMetadata` (does **not** call `wipeBookMetadata`)

**Still later:** OpenLibrary provider, LLM OCR assist, unify staging UIs, library-scan auto on by default.

**Summary:** Discover ISBN from front-matter text (checksum + verify vs title/author), multi-pass provider fill, auto-apply when verified, minimize reviews. No embeddings. Phone Mode frozen — tablet/desktop chrome only. Never reuse `wipeBookMetadata` for multi-pass; scope auto-apply to ISBN path only.

## Editable Files

Always safe to edit:
- Source files in `fable-api/src/main/java/` and `fable-ui/src/app/`
- Test files in `fable-api/src/test/` and `fable-ui/src/**/*.spec.ts`
- Config files: `build.gradle`, `package.json`, `angular.json`, `.env`, docker compose files
- CI files in `.github/workflows/`
- Documentation in `docs/`

Do not edit without explicit instruction:
- `.git/` — Git internals
- `node_modules/` — npm dependencies
- `fable-v3/`, `fable-v3b/`, `fable-v3c/` — legacy snapshots
- `data/`, `books/`, `bookdrop/` — runtime data directories
- Generated files: `build/`, `dist/`, `.gradle/`

## Repository Metadata

- Source: fork of [adityachandelgit/Fable](https://github.com/adityachandelgit/Fable)
- License: MIT (`LICENSE` file)
- Security policy: `SECURITY.md`
- Docker images: Published to `ghcr.io/opensourcefan/fable` and `ghcr.io/opensourcefan/fable-panel-ai`
- AI Panel Detection: Opt-in via `COMPOSE_PROFILES=ai`; uses bundled YOLO model
