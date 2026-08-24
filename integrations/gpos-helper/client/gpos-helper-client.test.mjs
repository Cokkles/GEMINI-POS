import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';
import { GposHelperClient } from './gpos-helper-client.mjs';

globalThis.btoa ??= value => Buffer.from(value, 'binary').toString('base64');
const memoryStorage = () => { const values = new Map(); return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)), removeItem: key => values.delete(key) }; };
const responses = [];
const fetchImpl = async (url, options) => {
  responses.push({ url, options });
  if (url.endsWith('/health')) return json(200, { status: 'AVAILABLE' });
  if (url.endsWith('/client/start')) return json(200, { authorization_url: '/api/v1/auth/callback?code=development&state=development' });
  if (url.endsWith('/client/exchange')) return json(200, { session_token: 'helper-session-token-value-with-length', expires_at: new Date(Date.now() + 60000).toISOString(), identity: { email: 'developer@localhost' } });
  if (url.endsWith('/aegis/dashboard')) return json(200, { ready: true });
  return json(404, { error: 'not_found' });
};
const json = (status, body) => ({ ok: status < 400, status, json: async () => body });
const storage = memoryStorage(); const replaced = [];
const client = new GposHelperClient({ fetchImpl, storage, cryptoImpl: webcrypto, location: { href: 'https://cokkles.github.io/aegis-itinerary-project/', hash: '', pathname: '/aegis-itinerary-project/', search: '', assign() {} }, history: { replaceState: (...args) => replaced.push(args) } });

assert.equal((await client.detect()).status, 'AVAILABLE');
await client.beginLogin(undefined, { navigate: false });
assert.match(JSON.parse(responses.at(-1).options.body).codeChallenge, /^[A-Za-z0-9_-]{43}$/);
const session = await client.completeLoginFromFragment('#view=dashboard&gpos_code=one-time-code');
assert.equal(session.identity.email, 'developer@localhost');
assert.ok(client.sessionToken());
assert.equal(replaced.at(-1)[2], '/aegis-itinerary-project/#view=dashboard');
assert.deepEqual(await client.dashboard(), { ready: true });
assert.equal(responses.at(-1).options.headers['X-GPOS-Session'], 'helper-session-token-value-with-length');
assert.throws(() => new GposHelperClient({ baseUrl: 'https://remote.example', fetchImpl, storage, cryptoImpl: webcrypto }), /loopback/);
console.log('RESULT: 7/7 passed');
