import assert from 'node:assert/strict';
import { GposHelperAegisBridge } from './gpos-helper-aegis-bridge.mjs';

const fake = overrides => ({
  detect: async () => ({ status: 'AVAILABLE' }), sessionToken: () => 'session', authStatus: async () => ({ authenticated: true }),
  dashboard: async () => ({ source: 'helper' }), calendarQuery: async () => ({ source: 'helper' }), logout: async () => {},
  beginLogin: async () => ({}), completeLoginFromFragment: async () => null, ...overrides
});

let fallbackCalls = 0; const fallback = async () => { fallbackCalls++; return { source: 'apps_script' }; };
const unavailable = new GposHelperAegisBridge({ client: fake({ detect: async () => ({ status: 'UNAVAILABLE', error: 'offline' }) }) });
assert.equal((await unavailable.initialize()).mode, 'apps_script');
assert.equal((await unavailable.dashboard(fallback)).source, 'apps_script');

const signedOut = new GposHelperAegisBridge({ client: fake({ sessionToken: () => null }) });
assert.equal((await signedOut.initialize()).mode, 'helper_available');
assert.equal((await signedOut.dashboard(fallback)).source, 'apps_script');

const ready = new GposHelperAegisBridge({ client: fake({}) });
assert.equal((await ready.initialize()).mode, 'helper'); const before = fallbackCalls;
assert.equal((await ready.dashboard(fallback)).source, 'helper'); assert.equal(fallbackCalls, before);

const degraded = new GposHelperAegisBridge({ client: fake({ dashboard: async () => { throw new Error('timeout'); } }) });
await degraded.initialize(); const result = await degraded.dashboard(fallback);
assert.equal(result.source, 'apps_script'); assert.equal(degraded.state.mode, 'apps_script');
assert.equal(fallbackCalls, before + 1);
console.log('RESULT: 10/10 passed');
