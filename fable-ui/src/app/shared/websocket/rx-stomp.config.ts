import {ReconnectionTimeMode, RxStompConfig} from '@stomp/rx-stomp';
import {AuthService} from '../service/auth.service';
import {API_CONFIG} from '../../core/config/api-config';

export function createRxStompConfig(authService: AuthService): RxStompConfig {
  return {
    brokerURL: API_CONFIG.BROKER_URL,
    heartbeatIncoming: 10000,
    heartbeatToleranceMultiplier: 2.5,
    heartbeatOutgoing: 20000,
    reconnectDelay: 2000,
    maxReconnectDelay: 30000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    beforeConnect: async (stomp) => {
      const token = typeof authService.ensureFreshAccessToken === 'function'
        ? await authService.ensureFreshAccessToken()
        : authService.getInternalAccessToken();
      if (token) {
        stomp.stompClient.connectHeaders = {
          'Authorization': `Bearer ${token}`,
        };
      }
    },
    debug: (_msg: string): void => {
      // console.log(new Date(), msg);
    },
  };
}
