import { onBeforeUnmount, watch } from 'vue';
import store from '@/store';
import { captureResourceSession, isResourceSessionCurrent } from '@/utils/authResource';
import type { ResourceSession } from '@/utils/authResource';

// Clear resident-specific UI when this tab or another tab changes the account.
export function useCarePageSession(reset: () => void) {
  let alive = true;
  let session = captureResourceSession();
  const changed = () => {
    if (isResourceSessionCurrent(session)) return;
    reset();
    try { session = captureResourceSession(); } catch { /* A logged-out page cannot start another request. */ }
  };
  const stop = watch(() => [store.state.app.token, store.state.app.userPeofile?.userid], changed, { flush: 'sync' });
  window.addEventListener('storage', changed);
  onBeforeUnmount(() => { alive = false; stop(); window.removeEventListener('storage', changed); });
  return {
    capture: captureResourceSession,
    current: (request: ResourceSession) => alive && isResourceSessionCurrent(request)
  };
}
