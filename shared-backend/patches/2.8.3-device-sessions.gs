/**
 * AEGIS shared backend 2.8.3 -- durable device sessions.
 *
 * Install beside Code.gs, NutritionReliability281.gs, and
 * NutritionQueue282.gs. This module never replaces Google verification:
 * a device session may only be issued after AUTH-1 has verified the Google
 * ID token, allowlist, audience, issuer, expiry, and server-derived scopes.
 */

var AEGIS_DEVICE_SESSION_CONTRACT_V283 = "AEGIS_DEVICE_SESSION_V1";
var AEGIS_DEVICE_SESSION_PREFIX_V283 = "aegis_ds1";
var AEGIS_DEVICE_SESSION_SECRET_PROPERTY_V283 = "AEGIS_DEVICE_SESSION_SECRET_V1";
var AEGIS_DEVICE_SESSION_REGISTRY_PROPERTY_V283 = "AEGIS_DEVICE_SESSIONS_V1";
var AEGIS_DEVICE_SESSION_TTL_MS_V283 = 30 * 24 * 60 * 60 * 1000;
var AEGIS_DEVICE_SESSION_RENEW_WINDOW_MS_V283 = 7 * 24 * 60 * 60 * 1000;
var AEGIS_DEVICE_SESSION_MAX_RECORDS_V283 = 50;
var AEGIS_DEVICE_SESSION_MAX_PER_EMAIL_V283 = 8;

function installAegisDeviceSessionsV283() {
  var properties = PropertiesService.getScriptProperties();
  if (!properties.getProperty(AEGIS_DEVICE_SESSION_SECRET_PROPERTY_V283)) {
    var seed = [
      Utilities.getUuid(),
      Utilities.getUuid(),
      Utilities.getUuid(),
      String(new Date().getTime())
    ].join("|");
    var digest = Utilities.computeDigest(
      Utilities.DigestAlgorithm.SHA_256,
      seed,
      Utilities.Charset.UTF_8
    );
    properties.setProperty(
      AEGIS_DEVICE_SESSION_SECRET_PROPERTY_V283,
      Utilities.base64EncodeWebSafe(digest).replace(/=+$/g, "")
    );
  }
  if (!properties.getProperty(AEGIS_DEVICE_SESSION_REGISTRY_PROPERTY_V283)) {
    properties.setProperty(AEGIS_DEVICE_SESSION_REGISTRY_PROPERTY_V283, "{}");
  }
  return {
    status: "success",
    contract: AEGIS_DEVICE_SESSION_CONTRACT_V283,
    installed: true,
    ttl_days: 30,
    rolling_renewal_days: 7
  };
}

/**
 * Call only after the existing AUTH-1 Google verification succeeds.
 * verifiedIdentity and verifiedScopes must be derived by the backend, never
 * copied from client payload fields.
 */
function attachAegisDeviceSessionV283_(
  existingLoginResponse,
  verifiedIdentity,
  verifiedScopes,
  contents
) {
  var response = existingLoginResponse || {};
  if (!response.authenticated) return response;
  var issued = issueAegisDeviceSessionV283_(
    verifiedIdentity,
    verifiedScopes,
    contents || {}
  );
  response.session = issued.session;
  response.user = response.user || issued.user;
  response.contract = AEGIS_DEVICE_SESSION_CONTRACT_V283;
  return response;
}

function issueAegisDeviceSessionV283_(identity, verifiedScopes, contents) {
  installAegisDeviceSessionsV283();
  var email = normalizeAegisDeviceEmailV283_(
    identity && (identity.email || identity.user && identity.user.email)
  );
  if (!email) throw new Error("AEGIS_DEVICE_SESSION_IDENTITY_REQUIRED");

  var scopes = normalizeAegisDeviceScopesV283_(verifiedScopes);
  if (!scopes.length) throw new Error("AEGIS_DEVICE_SESSION_SCOPES_REQUIRED");

  var now = new Date().getTime();
  var expiresAt = now + AEGIS_DEVICE_SESSION_TTL_MS_V283;
  var sid = Utilities.getUuid();
  var deviceId = normalizeAegisDeviceIdV283_(contents && contents.device_id);
  var claims = {
    v: 1,
    sid: sid,
    sub: email,
    name: cleanAegisDeviceTextV283_(
      identity && (identity.name || identity.user && identity.user.name),
      160
    ),
    picture: cleanAegisDeviceTextV283_(
      identity && (identity.picture || identity.user && identity.user.picture),
      800
    ),
    scopes: scopes,
    device_id: deviceId,
    client_id: cleanAegisDeviceTextV283_(contents && contents.client_id, 80),
    client_version: cleanAegisDeviceTextV283_(contents && contents.client_version, 40),
    iat: now,
    exp: expiresAt
  };
  var token = signAegisDeviceClaimsV283_(claims);

  withAegisDeviceSessionRegistryV283_(function(registry) {
    registry[sid] = {
      sid: sid,
      email: email,
      device_id: deviceId,
      issued_at: now,
      expires_at: expiresAt,
      last_seen_at: now,
      revoked_at: null
    };
    pruneAegisDeviceSessionsV283_(registry, now);
  });

  return deviceSessionResponseV283_(claims, token, false);
}

/**
 * Returns null for non-device tokens so the existing Google AUTH-1 path can
 * continue unchanged. Throws a coded error for malformed/expired/revoked
 * device tokens; callers must not fall back to Google verification in that
 * case.
 */
function tryAuthorizeAegisDeviceSessionV283_(contents, requiredScope) {
  var token = String(contents && contents.auth_token || "").trim();
  if (token.indexOf(AEGIS_DEVICE_SESSION_PREFIX_V283 + ".") !== 0) return null;
  return validateAegisDeviceSessionV283_(token, requiredScope, true);
}

function validateAegisDeviceSessionV283_(token, requiredScope, allowRenewal) {
  var claims = verifyAegisDeviceTokenV283_(token);
  var now = new Date().getTime();
  if (Number(claims.exp || 0) <= now) {
    throw new Error("AEGIS_DEVICE_SESSION_EXPIRED");
  }
  if (
    requiredScope &&
    normalizeAegisDeviceScopesV283_(claims.scopes).indexOf(String(requiredScope)) < 0
  ) {
    throw new Error("AEGIS_DEVICE_SESSION_SCOPE_DENIED");
  }

  var record;
  withAegisDeviceSessionRegistryV283_(function(registry) {
    record = registry[claims.sid];
    if (!record) throw new Error("AEGIS_DEVICE_SESSION_UNKNOWN");
    if (record.revoked_at) throw new Error("AEGIS_DEVICE_SESSION_REVOKED");
    if (Number(record.expires_at || 0) <= now) {
      delete registry[claims.sid];
      throw new Error("AEGIS_DEVICE_SESSION_EXPIRED");
    }
    if (
      record.email !== claims.sub ||
      String(record.device_id || "") !== String(claims.device_id || "")
    ) {
      throw new Error("AEGIS_DEVICE_SESSION_MISMATCH");
    }
    record.last_seen_at = now;
  });

  var renewed = false;
  var resultToken = token;
  if (allowRenewal && Number(claims.exp) - now <= AEGIS_DEVICE_SESSION_RENEW_WINDOW_MS_V283) {
    claims.iat = now;
    claims.exp = now + AEGIS_DEVICE_SESSION_TTL_MS_V283;
    resultToken = signAegisDeviceClaimsV283_(claims);
    renewed = true;
    withAegisDeviceSessionRegistryV283_(function(registry) {
      if (registry[claims.sid]) registry[claims.sid].expires_at = claims.exp;
    });
  }
  return deviceSessionResponseV283_(claims, resultToken, renewed);
}

function handleAegisDeviceSessionValidationV283_(contents) {
  try {
    var result = tryAuthorizeAegisDeviceSessionV283_(contents || {}, null);
    if (!result) {
      return deviceSessionErrorV283_(
        "AEGIS_DEVICE_SESSION_REQUIRED",
        "A device-session token is required."
      );
    }
    return result;
  } catch (error) {
    return deviceSessionErrorV283_(
      String(error && error.message || "AEGIS_DEVICE_SESSION_FAILED"),
      "The AEGIS device session is invalid or expired."
    );
  }
}

function handleAegisDeviceSessionLogoutV283_(contents) {
  var token = String(contents && contents.auth_token || "").trim();
  if (token.indexOf(AEGIS_DEVICE_SESSION_PREFIX_V283 + ".") !== 0) return null;
  try {
    var claims = verifyAegisDeviceTokenV283_(token);
    withAegisDeviceSessionRegistryV283_(function(registry) {
      if (registry[claims.sid]) registry[claims.sid].revoked_at = new Date().getTime();
    });
    return {
      status: "success",
      authenticated: false,
      revoked: true,
      contract: AEGIS_DEVICE_SESSION_CONTRACT_V283
    };
  } catch (error) {
    return deviceSessionErrorV283_(
      "AEGIS_DEVICE_SESSION_INVALID",
      "The device session could not be revoked because it was invalid."
    );
  }
}

function revokeAllAegisDeviceSessionsForEmailV283_(email) {
  var normalized = normalizeAegisDeviceEmailV283_(email);
  var revoked = 0;
  withAegisDeviceSessionRegistryV283_(function(registry) {
    Object.keys(registry).forEach(function(sid) {
      if (registry[sid].email === normalized && !registry[sid].revoked_at) {
        registry[sid].revoked_at = new Date().getTime();
        revoked++;
      }
    });
  });
  return { status: "success", email: normalized, revoked: revoked };
}

function deviceSessionResponseV283_(claims, token, renewed) {
  return {
    status: "success",
    authenticated: true,
    contract: AEGIS_DEVICE_SESSION_CONTRACT_V283,
    user: {
      email: claims.sub,
      name: claims.name || null,
      picture: claims.picture || null
    },
    scopes: claims.scopes,
    session: {
      type: "AEGIS_DEVICE_SESSION",
      token: token,
      session_id: claims.sid,
      device_id: claims.device_id || null,
      issued_at: new Date(Number(claims.iat)).toISOString(),
      expires_at: new Date(Number(claims.exp)).toISOString(),
      rolling: true,
      renewed: Boolean(renewed)
    }
  };
}

function deviceSessionErrorV283_(code, message) {
  return {
    status: "error",
    authenticated: false,
    code: code,
    error: message,
    contract: AEGIS_DEVICE_SESSION_CONTRACT_V283,
    reconnect_required: true
  };
}

function signAegisDeviceClaimsV283_(claims) {
  var secret = requireAegisDeviceSecretV283_();
  var payload = Utilities.base64EncodeWebSafe(
    JSON.stringify(claims),
    Utilities.Charset.UTF_8
  ).replace(/=+$/g, "");
  var signature = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(
      payload,
      secret,
      Utilities.Charset.UTF_8
    )
  ).replace(/=+$/g, "");
  return [AEGIS_DEVICE_SESSION_PREFIX_V283, payload, signature].join(".");
}

function verifyAegisDeviceTokenV283_(token) {
  var parts = String(token || "").split(".");
  if (parts.length !== 3 || parts[0] !== AEGIS_DEVICE_SESSION_PREFIX_V283) {
    throw new Error("AEGIS_DEVICE_SESSION_INVALID");
  }
  var expected = Utilities.base64EncodeWebSafe(
    Utilities.computeHmacSha256Signature(
      parts[1],
      requireAegisDeviceSecretV283_(),
      Utilities.Charset.UTF_8
    )
  ).replace(/=+$/g, "");
  if (!constantTimeAegisDeviceEqualsV283_(expected, parts[2])) {
    throw new Error("AEGIS_DEVICE_SESSION_SIGNATURE_INVALID");
  }
  try {
    var decoded = Utilities.newBlob(
      Utilities.base64DecodeWebSafe(padAegisDeviceBase64V283_(parts[1]))
    ).getDataAsString();
    var claims = JSON.parse(decoded);
    if (
      Number(claims.v) !== 1 ||
      !claims.sid ||
      !normalizeAegisDeviceEmailV283_(claims.sub) ||
      !Array.isArray(claims.scopes)
    ) throw new Error("claims");
    return claims;
  } catch (error) {
    throw new Error("AEGIS_DEVICE_SESSION_CLAIMS_INVALID");
  }
}

function requireAegisDeviceSecretV283_() {
  var secret = PropertiesService.getScriptProperties()
    .getProperty(AEGIS_DEVICE_SESSION_SECRET_PROPERTY_V283);
  if (!secret) throw new Error("AEGIS_DEVICE_SESSION_NOT_INSTALLED");
  return secret;
}

function withAegisDeviceSessionRegistryV283_(mutator) {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) throw new Error("AEGIS_DEVICE_SESSION_BUSY");
  try {
    var properties = PropertiesService.getScriptProperties();
    var raw = properties.getProperty(AEGIS_DEVICE_SESSION_REGISTRY_PROPERTY_V283) || "{}";
    var registry;
    try {
      registry = JSON.parse(raw);
    } catch (error) {
      throw new Error("AEGIS_DEVICE_SESSION_REGISTRY_CORRUPT");
    }
    mutator(registry);
    properties.setProperty(
      AEGIS_DEVICE_SESSION_REGISTRY_PROPERTY_V283,
      JSON.stringify(registry)
    );
  } finally {
    lock.releaseLock();
  }
}

function pruneAegisDeviceSessionsV283_(registry, now) {
  Object.keys(registry).forEach(function(sid) {
    if (
      Number(registry[sid].expires_at || 0) <= now ||
      Number(registry[sid].revoked_at || 0) > 0
    ) delete registry[sid];
  });

  var byEmail = {};
  Object.keys(registry).forEach(function(sid) {
    var email = registry[sid].email;
    byEmail[email] = byEmail[email] || [];
    byEmail[email].push(registry[sid]);
  });
  Object.keys(byEmail).forEach(function(email) {
    byEmail[email].sort(function(a, b) {
      return Number(b.last_seen_at || 0) - Number(a.last_seen_at || 0);
    });
    byEmail[email].slice(AEGIS_DEVICE_SESSION_MAX_PER_EMAIL_V283)
      .forEach(function(record) { delete registry[record.sid]; });
  });

  var all = Object.keys(registry).map(function(sid) { return registry[sid]; });
  all.sort(function(a, b) {
    return Number(b.last_seen_at || 0) - Number(a.last_seen_at || 0);
  });
  all.slice(AEGIS_DEVICE_SESSION_MAX_RECORDS_V283)
    .forEach(function(record) { delete registry[record.sid]; });
}

function normalizeAegisDeviceScopesV283_(scopes) {
  var values = Array.isArray(scopes) ? scopes : [];
  var seen = {};
  return values.map(function(value) { return String(value || "").trim(); })
    .filter(function(value) {
      if (!value || value.length > 100 || seen[value]) return false;
      seen[value] = true;
      return true;
    })
    .sort();
}

function normalizeAegisDeviceEmailV283_(email) {
  var value = String(email || "").trim().toLowerCase();
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value) ? value : "";
}

function normalizeAegisDeviceIdV283_(deviceId) {
  var value = String(deviceId || "").trim();
  if (!/^[A-Za-z0-9._:-]{8,128}$/.test(value)) {
    throw new Error("AEGIS_DEVICE_ID_INVALID");
  }
  return value;
}

function cleanAegisDeviceTextV283_(value, limit) {
  var text = String(value || "").trim();
  return text ? text.slice(0, limit) : "";
}

function constantTimeAegisDeviceEqualsV283_(left, right) {
  left = String(left || "");
  right = String(right || "");
  var mismatch = left.length ^ right.length;
  var length = Math.max(left.length, right.length);
  for (var index = 0; index < length; index++) {
    mismatch |= (left.charCodeAt(index % Math.max(left.length, 1)) || 0) ^
      (right.charCodeAt(index % Math.max(right.length, 1)) || 0);
  }
  return mismatch === 0;
}

function padAegisDeviceBase64V283_(value) {
  var remainder = value.length % 4;
  return remainder ? value + "====".slice(remainder) : value;
}

function testAegisDeviceSessionsV283() {
  installAegisDeviceSessionsV283();
  var deviceId = "V283-TEST-" + Utilities.getUuid();
  var issued = issueAegisDeviceSessionV283_(
    { email: "v283-test@example.invalid", name: "V283 Test" },
    ["dashboard.read", "kinetic.read"],
    { device_id: deviceId, client_id: "test", client_version: "2.8.3" }
  );
  var validated = validateAegisDeviceSessionV283_(
    issued.session.token,
    "kinetic.read",
    false
  );
  if (!validated.authenticated || validated.user.email !== "v283-test@example.invalid") {
    throw new Error("V283 session validation failed.");
  }
  var denied = false;
  try {
    validateAegisDeviceSessionV283_(issued.session.token, "finance.read", false);
  } catch (error) {
    denied = String(error && error.message) === "AEGIS_DEVICE_SESSION_SCOPE_DENIED";
  }
  if (!denied) throw new Error("V283 scope denial failed.");
  var logout = handleAegisDeviceSessionLogoutV283_({ auth_token: issued.session.token });
  if (!logout || !logout.revoked) throw new Error("V283 logout failed.");
  var revoked = false;
  try {
    validateAegisDeviceSessionV283_(issued.session.token, "kinetic.read", false);
  } catch (error) {
    revoked = String(error && error.message) === "AEGIS_DEVICE_SESSION_REVOKED";
  }
  if (!revoked) throw new Error("V283 revocation validation failed.");

  withAegisDeviceSessionRegistryV283_(function(registry) {
    delete registry[issued.session.session_id];
  });
  return {
    status: "success",
    test: "AEGIS_DEVICE_SESSION_V283",
    issue_validate_scope_revoke: true
  };
}
