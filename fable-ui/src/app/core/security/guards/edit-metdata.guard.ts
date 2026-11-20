import {inject} from '@angular/core';
import {CanActivateFn, Router} from '@angular/router';
import {UserService} from '../../../features/settings/user-management/user.service';
import {filter, map, take} from 'rxjs/operators';

export const EditMetadataGuard: CanActivateFn = () => {
  const userService = inject(UserService);
  const router = inject(Router);

  return userService.userState$.pipe(
    filter(state => state.loaded),
    take(1),
    map(state => {
      const user = state.user;
      if (user && (user.permissions.admin || user.permissions.canEditMetadata)) {
        return true;
      }
      return router.createUrlTree(['/dashboard']);
    })
  );
};
