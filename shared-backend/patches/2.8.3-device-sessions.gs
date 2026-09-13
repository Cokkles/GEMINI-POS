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
// Script Properties have a small per-value ceiling. Keep the single compact
// registry comfortably below it even when several clients are registered.
var AEGIS_DEVICE_SESSION_MAX_RECORDS_V283 = 24;
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

function deviceSessionRes