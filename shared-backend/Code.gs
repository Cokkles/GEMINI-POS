/**
 * AEGIS Master Webhook & Ingestion Engine (Option A)
 *
 * Complete Workspace Router & HORIZON Integration:
 * 1. /calories   -> Gemini AI Macro Extraction -> Nutrition Sheet
 * 2. /journal    -> Dedicated Journal Document
 * 3. /receipts   -> Expense Intake -> Finance Sheet
 * 4. /groceries  -> Google Tasks
 * 5. /note       -> Notes & Ideas Document
 * 6. mark_done   -> Google Tasks Complete + Notes tombstone (no legacy JSON)
 * 7. /horizon    -> Trigger contract-bounded HORIZON -> overwrite canonical Doc
 * 8. horizon_sync -> Same contract-bounded on-demand HORIZON pipeline
 * 9. GET getLatestHorizonBriefing -> Return canonical Doc as structured JSON
 * 10. GET getHorizonData/getSummary -> Build current AEGIS runtime state directly (no legacy JSON)
 */

function getGeminiConfig() {
  const props = PropertiesService.getScriptProperties();
  const apiKey = props.getProperty("GEMINI_API_KEY");
  const model = props.getProperty("GEMINI_MODEL") || "gemini-3.6-flash";
  if (!apiKey) throw new Error("GEMINI_API_KEY not found in Script Properties.");
  return { apiKey: apiKey, model: model };
}

function callGemini(prompt) {
  const cfg = getGeminiConfig();
  const url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(cfg.model) + ":generateContent";
  const response = UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    headers: { "x-goog-api-key": cfg.apiKey },
    payload: JSON.stringify({
      contents: [{ role: "user", parts: [{ text: prompt }] }]
    }),
    muteHttpExceptions: true
  });
  const code = response.getResponseCode();
  const text = response.getContentText();
  if (code < 200 || code >= 300) {
    throw new Error("Gemini API HTTP " + code + " using model " + cfg.model + ": " + text);
  }
  const json = JSON.parse(text);
  const parts = json.candidates &&
    json.candidates[0] &&
    json.candidates[0].content &&
    json.candidates[0].content.parts;
  if (!parts || !parts.length) {
    throw new Error("Gemini returned no usable content using model " + cfg.model + ".");
  }
  return parts.map(function(part) { return part.text || ""; }).join("").trim();
}

function testGeminiConnection() {
  const cfg = getGeminiConfig();
  const reply = callGemini("Reply with exactly: AEGIS GEMINI OK");
  Logger.log("Model: " + cfg.model);
  Logger.log("Reply: " + reply);
  return { status: "ok", model: cfg.model, reply: reply };
}

const AEGIS_BACKEND_VERSION = "2.8.3";

const CONFIG = {
  CALORIES_SHEET_ID:
    "10SzZC5aQi2R_r7ulcukpozQ4Ws0Pbo5KqI32os_idlk",

  FINANCE_SHEET_ID:
    "1Oc2X4CyS9C8Uj58WvsJaOyj1MIdXEKoZ0P7lEsGfP2g",

  GROCERY_SHEET_ID:
    "15UyNwGfBSwXUnEdIaonT-vF2ynxz5dp1JR0r1Rp06BM",

  JOURNAL_DOC_ID:
    "1lAnHLHPG6v9lnm4ExQmAU4Q9LPo97pUTua04G__nNd8",

  NOTES_DOC_ID:
    "1XuPuZkyzCoFk1vWt4kdU-0daoiscaLIaSuoiqKLWsvc",

  LATEST_HORIZON_BRIEFING_DOC_ID:
    "1Id8HjrUGK8HL8pv5lOcKwK0A7fNY8mQ1Fg4HerM7HJ4",

  KINETIC_CONFIG_DOC_ID:
    "1y1yplxE8FijsiqHCDWpxWa6Tw9owkv2lwx_dQpDc3_o",

  KINETIC_TRACKER_URL:
    "https://docs.google.com/spreadsheets/d/10SzZC5aQi2R_r7ulcukpozQ4Ws0Pbo5KqI32os_idlk/edit",

  TIMEZONE: "America/New_York",
  HORIZON_VERSION: "2.6.0",
  SPARK_FRESH_HOURS: 48
};


/* ============================================================
   AEGIS AUTH-1 — GOOGLE IDENTITY / AUTHORIZATION BOUNDARY
   ============================================================ */

function isAegisAuthRequired_() {
  var value = String(
    PropertiesService.getScriptProperties().getProperty("AEGIS_AUTH_REQUIRED") || "false"
  ).trim().toLowerCase();
  return value === "true" || value === "1" || value === "yes" || value === "on";
}

function parseAegisGoogleClientIds_(value) {
  var text = String(value || "").trim();
  if (!text) return [];

  if (text.charAt(0) === "[") {
    try {
      var parsed = JSON.parse(text);
      if (Array.isArray(parsed)) {
        return parsed.map(function(x) { return String(x || "").trim(); }).filter(Boolean);
      }
    } catch (ignored) {
      // Fall through so a malformed JSON-like value is still parsed as a delimiter list.
    }
  }

  return text
    .split(/[\s,;]+/)
    .map(function(x) { return x.trim(); })
    .filter(Boolean);
}

function getAegisAllowedGoogleClientIds_(props) {
  var clientIds = parseAegisGoogleClientIds_(props.getProperty("AEGIS_GOOGLE_CLIENT_ID"))
    .concat(parseAegisGoogleClientIds_(props.getProperty("AEGIS_GOOGLE_CLIENT_IDS")));
  return clientIds.filter(function(value, index, values) {
    return values.indexOf(value) === index;
  });
}

function getAegisAuthSettings_() {
  var props = PropertiesService.getScriptProperties();
  var clientIds = getAegisAllowedGoogleClientIds_(props);
  var allowed = String(props.getProperty("AEGIS_AUTH_ALLOWED_EMAILS") || "")
    .split(",")
    .map(function(x) { return x.trim().toLowerCase(); })
    .filter(Boolean);
  var scopes = String(
    props.getProperty("AEGIS_AUTH_SCOPES") ||
    "dashboard.read,horizon.generate,calendar.read,calendar.write,tasks.read,tasks.write,gmail.read,kinetic.read,sentinel.read,spark.write,ai.query"
  )
    .split(",")
    .map(function(x) { return x.trim(); })
    .filter(Boolean);

  if (!clientIds.length) throw new Error("No trusted Google OAuth client audience is configured.");
  if (!allowed.length) throw new Error("AEGIS_AUTH_ALLOWED_EMAILS is not configured.");

  return {
    clientId: clientIds[0],
    clientIds: clientIds,
    allowedEmails: allowed,
    scopes: scopes
  };
}

function getAegisPublicAuthConfig_() {
  var props = PropertiesService.getScriptProperties();
  var clientId = String(props.getProperty("AEGIS_GOOGLE_CLIENT_ID") || "").trim();
  var clientIds = getAegisAllowedGoogleClientIds_(props);
  return {
    status: "success",
    provider: "google",
    configured: !!clientId,
    client_id: clientId,
    trusted_audience_count: clientIds.length,
    additional_audiences_configured: clientIds.length > (clientId ? 1 : 0),
    allowlist_configured: !!String(props.getProperty("AEGIS_AUTH_ALLOWED_EMAILS") || "").trim(),
    enforcement_required: isAegisAuthRequired_(),
    auth_version: "AUTH-1",
    backend_version: AEGIS_BACKEND_VERSION
  };
}

function verifyAegisGoogleToken_(idToken) {
  if (!idToken) throw new Error("Authentication token missing.");

  var settings = getAegisAuthSettings_();
  var response = UrlFetchApp.fetch(
    "https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken),
    { method: "get", muteHttpExceptions: true }
  );

  var code = response.getResponseCode();
  if (code !== 200) {
    throw new Error("Google identity token rejected (HTTP " + code + ").");
  }

  var claims = JSON.parse(response.getContentText());
  var now = Math.floor(Date.now() / 1000);

  if (settings.clientIds.indexOf(String(claims.aud || "")) === -1) {
    throw new Error("Google identity token audience mismatch.");
  }

  if (String(claims.email_verified) !== "true") {
    throw new Error("Google account email is not verified.");
  }

  if (!claims.exp || Number(claims.exp) <= now) {
    throw new Error("Google identity token expired.");
  }

  var issuer = String(claims.iss || "");
  if (
    issuer &&
    issuer !== "accounts.google.com" &&
    issuer !== "https://accounts.google.com"
  ) {
    throw new Error("Google identity token issuer mismatch.");
  }

  var email = String(claims.email || "").toLowerCase();
  if (!email || settings.allowedEmails.indexOf(email) === -1) {
    logAegisAuthEvent_("AUTHORIZATION_DENIED", email || "unknown", {
      reason: "EMAIL_NOT_ALLOWLISTED"
    });
    throw new Error("This Google account is not authorized for AEGIS.");
  }

  return {
    claims: claims,
    user: {
      email: email,
      name: claims.name || email,
      picture: claims.picture || null,
      subject: claims.sub || null
    },
    scopes: settings.scopes,
    expiresAt: new Date(Number(claims.exp) * 1000).toISOString()
  };
}

function handleAegisAuthAction_(action, contents) {
  if (
    action !== "auth_login" &&
    action !== "auth_session" &&
    action !== "auth_logout"
  ) {
    return null;
  }

  try {
    // Backend-issued device sessions are handl