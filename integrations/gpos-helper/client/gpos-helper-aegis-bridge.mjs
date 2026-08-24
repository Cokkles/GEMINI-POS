import { GposHelperClient } from './gpos-helper-client.mjs';

export class GposHelperAegisBridge {
  constructor({ client = new GposHelperClient(), onState = () => {} } = {}) {
    this.client = client; this.onState = onState; this.state = { mode: 'unchecked', helper: null, authenticated: false, detail: '' };
  }

  async initialize() {
    const health = await this.client.detect();
    if (health.status !== 'AVAILABLE') return this.update('apps_script', health, false, health.error || 'helper_unavailable');
    if (!this.client.sessionToken()) return this.update('helper_available', health, false, 'authentication_required');
    try {
      const auth = await this.client.authStatus();
      return this.update(auth.authenticated ? 'helper' : 'helper_available', health, Boolean(auth.authenticated), auth.authenticated ? '' : 'authentication_required');
    } catch (error) { return this.update('apps_script', health, false, error.message); }
  }

  async login(returnUrl, options) { return this.client.beginLogin(returnUrl, options); }
  async completeLogin(fragment) { const session = await this.client.completeLoginFromFragment(fragment); if (session) await this.initialize(); return session; }
  async logout() { await this.client.logout(); return this.update('helper_available', this.state.helper, false, 'authentication_required'); }

  async dashboard(appsScriptFallback) { return this.readThrough(() => this.client.dashboard(), appsScriptFallback, 'dashboard'); }
  async calendarQuery(question, history, appsScriptFallback) { return this.readThrough(() => this.client.calendarQuery(question, history), appsScriptFallback, 'calendar'); }

  async readThrough(helperOperation, fallback, operation) {
    if (typeof fallback !== 'function') throw new Error(`${operation} fallback is required`);
    if (this.state.mode !== 'helper' || !this.state.authenticated) return fallback();
    try { return await helperOperation(); }
    catch (error) {
      this.update('apps_script', this.state.helper, false, `${operation}:${error.message}`);
      return fallback();
    }
  }

  update(mode, helper, authenticated, detail) {
    this.state = Object.freeze({ mode, helper, authenticated, detail }); this.onState(this.state); return this.state;
  }
}

export function createAegisHelperBridge(options) { return new GposHelperAegisBridge(options); }
