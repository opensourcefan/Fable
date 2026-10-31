import {inject, Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {BehaviorSubject, combineLatest, Observable} from 'rxjs';
import {distinctUntilChanged, map} from 'rxjs/operators';
import {RxStompService} from '../../../shared/websocket/rx-stomp.service';
import {API_CONFIG} from '../../../core/config/api-config';

@Injectable({providedIn: 'root'})
export class LibraryHealthService {
  private readonly url = `${API_CONFIG.BASE_URL}/api/v1/libraries/health`;
  private http = inject(HttpClient);
  private rxStompService = inject(RxStompService);

  private healthSubject = new BehaviorSubject<Record<number, boolean>>({});
  private inaccessiblePathsSubject = new BehaviorSubject<Record<number, string[]>>({});

  initialize(): void {
    this.http.get<Record<number, boolean>>(this.url).subscribe(health => {
      this.healthSubject.next(health);
    });

    this.rxStompService.watch('/topic/library-health').subscribe(msg => {
      const payload = JSON.parse(msg.body);
      this.healthSubject.next(payload.libraryHealth ?? {});
      if (payload.inaccessiblePaths) {
        this.inaccessiblePathsSubject.next(payload.inaccessiblePaths);
      }
    });
  }

  isUnhealthy$(libraryId: number): Observable<boolean> {
    return this.healthSubject.pipe(
      map(health => health[libraryId] === false),
      distinctUntilChanged()
    );
  }

  getInaccessiblePaths$(libraryId: number, fallbackPaths: string[] = []): Observable<string[]> {
    return combineLatest([this.healthSubject, this.inaccessiblePathsSubject]).pipe(
      map(([health, pathsMap]) => {
        if (health[libraryId] !== false) {
          return [];
        }
        const fromWs = pathsMap[libraryId];
        return (fromWs && fromWs.length > 0) ? fromWs : fallbackPaths;
      })
    );
  }

  getInaccessibleTooltip$(libraryId: number, fallbackPaths: string[], baseLabel: string): Observable<string> {
    return this.getInaccessiblePaths$(libraryId, fallbackPaths).pipe(
      map(paths => paths.length > 0 ? `${baseLabel}: ${paths.join(', ')}` : baseLabel),
      distinctUntilChanged()
    );
  }
}
