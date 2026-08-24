const DEFAULT_BASE_URL = 'http://127.0.0.1:47831';
const SESSION_KEY = 'gpos_helper_session';
const VERIFIER_KEY = 'gpos_helper_pkce_verifier';

export class GposHelperClient {
  constructor({ baseUrl = DEFAULT_BASE_URL, timeoutMs = 5000, fetchImpl = globalThis.fetch, storage = globalThis.sessionStorage, location = globalThis.location, history = globalThis.history, cryptoImpl = globalThis.crypto } = {}) {
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(parsed.hostname)) throw new Error('gpos-helper must use an HTTP loopback URL');
    if (typeof fetchImpl !== 'function' || !storage || !cryptoImpl?.subtle) throw new Error('Required browser APIs are unavailable');
    this.baseUrl = parsed.origin;
    this.timeoutMs = Math.max(500, Math.min(Number(timeoutMs) || 5000, 30000));
    this.fetchImpl = fetchImpl; this.storage = storage; this.location = location; this.history = history; this.crypto = cryptoImpl;
  }

  async detect() {
    try { return await this.request('/api/v1/health', { authenticated: false }); }
    catch (error) { return { status: 'UNAVAILABLE', error: this.errorCategory(error) }; }
  }

  async beginLogin(returnUrl = this.location?.href, { navigate = true } = {}) {
    const target = new URL(returnUrl); target.hash = '';
    const verifier = this.randomToken(64); const challenge = await this.challenge(verifier);
    this.storage.setItem(VERIFIER_KEY, verifier);
    try {
      const result = await this.request('/api/v1/auth/client/start', { method: 'POST', authenticated: false, body: { returnUrl: target.href, codeChallenge: challenge } });
      if (navigate && this.location?.assign) this.location.assign(result.authorization_url);
      return result;
    } catch (error) { this.storage.removeItem(VERIFIER_KEY); throw error; }
  }

  async completeLoginFromFragment(fragment = this.location?.hash || '') {
    const params = new URLSearchParams(String(fragment).replace(/^#/, '')); const code = params.get('gpos_code');
    if (!code) return null;
    const verifier = this.storage.getItem(VERIFIER_KEY); this.storage.removeItem(VERIFIER_KEY);
    try {
      if (!verifier) throw new Error('PKCE verifier is missing or expired');
      const session = await this.request('/api/v1/auth/client/exchange', { method: 'POST', authenticated: false, body: { code, codeVerifier: verifier } });
      this.storage.setItem(SESSION_KEY, JSON.stringify({ token: session.session_token, expiresAt: session.expires_at, identity: session.identity }));
      return session;
    } finally { this.clearHelperFragment(params); }
  }

  async authStatus() { return this.request('/api/v1/auth/status'); }
  async dashboard() { return this.request('/api/v1/aegis/dashboard'); }
  async calendarQuery(question, history = []) { return this.request('/api/v1/aegis/calendar/query', { method: 'POST', body: { question, history } }); }

  async logout() {
    try { if (this.sessionToken()) await this.request('/api/v1/auth/logout', { method: 'POST' }); }
    finally { this.storage.removeItem(SESSION_KEY); this.storage.removeItem(VERIFIER_KEY); }
  }

  async request(path, { method = 'GET', body, authenticated = true } = {}) {
    const controller = new AbortController(); const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    const headers = { Accept: 'application/json' }; const token = authenticated ? this.sessionToken() : null;
    if (authenticated && !token) { clearTimeout(timer); throw new Error('Helper authentication is required'); }
    if (token) headers['X-GPOS-Session'] = token;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    try {
      const response = await this.fetchImpl(this.baseUrl + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: controller.signal, cache: 'no-store' });
      const payload = await response.json().catch(() => ({ error: 'malformed_response' }));
      if (!response.ok) throw new Error(payload.message || payload.error || `HTTP ${response.status}`);
      return payload;
    } finally { clearTimeout(timer); }
  }

  sessionToken() {
    try { const value = JSON.parse(this.storage.getItem(SESSION_KEY) || 'null'); if (!value?.token || (value.expiresAt && Date.parse(value.expiresAt) <= Date.now())) { this.storage.removeItem(SESSION_KEY); return null; } return value.token; }
    catch { this.storage.removeItem(SESSION_KEY); return null; }
  }

  randomToken(bytes) { const value = new Uint8Array(bytes); this.crypto.getRandomValues(value); return this.base64Url(value); }
  async challenge(verifier) { return this.base64Url(new Uint8Array(await this.crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)))); }
  base64Url(bytes) { let binary = ''; bytes.forEach(value => { binary += String.fromCharCode(value); }); return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, ''); }
  errorCategory(error) { return error?.name === 'AbortError' ? 'timeout' : String(error?.message || 'unavailable'); }
  clearHelperFragment(params) { params.delete('gpos_code'); const hash = params.toString(); if (this.history?.replaceState && this.location) this.history.replaceState(null, '', `${this.location.pathname || '/'}${this.location.search || ''}${hash ? '#' + hash : ''}`); }
}

export const GPOS_HELPER_DEFAULT_URL = DEFAULT_BASE_URL;
