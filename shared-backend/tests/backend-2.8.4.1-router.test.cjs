const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const code = fs.readFileSync(path.join(root, "Code.gs"), "utf8");
const nutrition281 = fs.readFileSync(
  path.join(root, "patches", "2.8.1-nutrition-reliability.gs"),
  "utf8",
);
const nutrition282 = fs.readFileSync(
  path.join(root, "patches", "2.8.2-durable-nutrition-queue.gs"),
  "utf8",
);
const sessions283 = fs.readFileSync(
  path.join(root, "patches", "2.8.3-device-sessions.gs"),
  "utf8",
);
const nutrition284 = fs.readFileSync(
  path.join(root, "patches", "2.8.4-nutrition-provider-hardening.gs"),
  "utf8",
);
const nutrition290 = fs.readFileSync(
  path.join(root, "patches", "2.9.0-nightly-nutrition-reconciliation.gs"),
  "utf8",
);
const nutrition2100 = fs.readFileSync(
  path.join(root, "patches", "2.10.0-nutrition-identity-serving.gs"),
  "utf8",
);

for (const [name, source] of Object.entries({
  code, nutrition281, nutrition282, sessions283, nutrition284, nutrition290,
  nutrition2100,
})) {
  assert.doesNotThrow(() => new vm.Script(source, { filename: name }));
}

const functionNames = [
  code, nutrition281, nutrition282, sessions283, nutrition284, nutrition290,
  nutrition2100,
]
  .flatMap((source) => [...source.matchAll(/^function\s+([\w$]+)\s*\(/gm)].map((match) => match[1]));
assert.equal(new Set(functionNames).size, functionNames.length, "Apps Script function names must be unique");

assert.match(code, /const AEGIS_BACKEND_VERSION = "2\.10\.0";/);
for (const action of [
  "enqueue_nutrition_capture",
  "get_nutrition_capture_status",
  "retry_nutrition_capture",
]) {
  assert.match(code, new RegExp(`action === "${action}"`));
}
for (const capability of [
  "nutrition_capture_v2",
  "capture_reliability_v1",
  "nutrition_capture_async_v1",
  "nutrition_capture_status_v1",
  "nutrition_result_cache_v1",
  "nutrition_provider_routing_v1",
  "nutrition_quota_diagnostics_v1",
  "nutrition_circuit_breaker_v1",
  "nutrition_multi_item_integrity_v1",
  "nutrition_provisional_logging_v1",
  "nutrition_nightly_reconciliation_v1",
  "nutrition_evidence_catalog_v1",
  "nutrition_revision_audit_v1",
  "nutrition_identity_guard_v1",
  "nutrition_trusted_food_catalog_v2",
  "nutrition_portion_conversion_v1",
  "device_session_v1",
  "interactive_auth_background_forbidden_v1",
]) {
  assert.match(code, new RegExp(`${capability}: true`));
}

const properties = new Map([
  ["AEGIS_AUTH_REQUIRED", "true"],
  ["AEGIS_GOOGLE_CLIENT_ID", "test-client"],
  ["AEGIS_AUTH_ALLOWED_EMAILS", "test@example.com"],
  ["AEGIS_AUTH_SCOPES", "dashboard.read,kinetic.read"],
]);
const propertyApi = {
  getProperty: (key) => properties.get(key) || null,
  setProperty: (key, value) => properties.set(key, String(value)),
};
const toWebSafe = (bytes) => Buffer.from(bytes).toString("base64url");
const sandbox = {
  Array,
  Boolean,
  Date,
  Error,
  JSON,
  Math,
  Number,
  Object,
  RegExp,
  String,
  console,
  Logger: { log() {} },
  PropertiesService: { getScriptProperties: () => propertyApi },
  LockService: {
    getScriptLock: () => ({ tryLock: () => true, releaseLock() {} }),
  },
  Utilities: {
    Charset: { UTF_8: "UTF_8" },
    DigestAlgorithm: { SHA_256: "SHA_256" },
    getUuid: () => crypto.randomUUID(),
    computeDigest: (_algorithm, value) => crypto.createHash("sha256").update(String(value)).digest(),
    computeHmacSha256Signature: (value, key) =>
      crypto.createHmac("sha256", String(key)).update(String(value)).digest(),
    base64EncodeWebSafe: toWebSafe,
    base64DecodeWebSafe: (value) => Buffer.from(String(value), "base64url"),
    newBlob: (bytes) => ({ getDataAsString: () => Buffer.from(bytes).toString("utf8") }),
  },
  UrlFetchApp: {
    fetch: () => ({
      getResponseCode: () => 200,
      getContentText: () => JSON.stringify({
        aud: "test-client",
        email: "test@example.com",
        email_verified: "true",
        exp: Math.floor(Date.now() / 1000) + 3600,
        iss: "https://accounts.google.com",
        name: "Test User",
        picture: "https://example.invalid/user.png",
        sub: "subject-1",
      }),
    }),
  },
};
vm.createContext(sandbox);
vm.runInContext(code, sandbox, { filename: "Code.gs" });
vm.runInContext(sessions283, sandbox, { filename: "DeviceSessions283.gs" });

const legacy = sandbox.handleAegisAuthAction_("auth_login", { auth_token: "google-token" });
assert.equal(legacy.authenticated, true);
assert.equal(legacy.session.provider, "google");
assert.equal(legacy.session.token, undefined);

const login = sandbox.handleAegisAuthAction_("auth_login", {
  auth_token: "google-token",
  client_id: "android",
  client_version: "0.7.6.1",
  device_id: "android-device-1234",
});
assert.equal(login.authenticated, true);
assert.equal(login.session.type, "AEGIS_DEVICE_SESSION");
assert.match(login.session.token, /^aegis_ds1\./);

const restored = sandbox.handleAegisAuthAction_("auth_session", { auth_token: login.session.token });
assert.equal(restored.authenticated, true);
assert.equal(restored.user.email, "test@example.com");

const authorized = sandbox.authorizeAegisPayload_(
  { auth_token: login.session.token },
  "kinetic.read",
);
assert.equal(authorized.user.email, "test@example.com");

const logout = sandbox.handleAegisAuthAction_("auth_logout", { auth_token: login.session.token });
assert.equal(logout.authenticated, false);
assert.equal(logout.revoked, true);

const revoked = sandbox.handleAegisAuthAction_("auth_session", { auth_token: login.session.token });
assert.equal(revoked.authenticated, false);
assert.equal(revoked.code, "AEGIS_AUTH_FAILED");
assert.equal(revoked.diagnostic_code, "AEGIS_DEVICE_SESSION_REVOKED");

console.log("PASS backend 2.10.0 router/auth static and mocked-runtime validation");
