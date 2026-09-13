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

const AEGIS_BACKEND_VERSION = "2.8.4.1";

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
    // Backend-issued device sessions are handled before Google token verification.
    // A prefixed token must never fall through to Google as a compatibility fallback.
    if (action === "auth_logout") {
      var deviceLogout = handleAegisDeviceSessionLogoutV283_(contents || {});
      if (deviceLogout) return deviceLogout;
    }
    if (
      action === "auth_session" &&
      String(contents && contents.auth_token || "")
        .indexOf(AEGIS_DEVICE_SESSION_PREFIX_V283 + ".") === 0
    ) {
      return handleAegisDeviceSessionValidationV283_(contents || {});
    }

    var verified = verifyAegisGoogleToken_(contents.auth_token || "");

    if (action === "auth_logout") {
      logAegisAuthEvent_("LOGOUT", verified.user.email, {});
      return {
        status: "success",
        authenticated: false
      };
    }

    logAegisAuthEvent_(
      action === "auth_login" ? "LOGIN_SUCCESS" : "SESSION_VALIDATED",
      verified.user.email,
      {}
    );

    var response = {
      status: "success",
      authenticated: true,
      user: verified.user,
      session: {
        provider: "google",
        expires_at: verified.expiresAt
      },
      scopes: verified.scopes
    };

    // Legacy clients that do not identify an installation continue receiving the
    // exact Google session response. Android 0.7.6.1 supplies a stable device_id.
    if (action === "auth_login" && String(contents.device_id || "").trim()) {
      response = attachAegisDeviceSessionV283_(
        response,
        verified.user,
        verified.scopes,
        contents
      );
    }
    return response;
  } catch (err) {
    if (action === "auth_login") {
      logAegisAuthEvent_("LOGIN_FAILURE", "unknown", {
        error: err.message
      });
    }
    return {
      status: "error",
      authenticated: false,
      code: "AEGIS_AUTH_FAILED",
      error: err.message
    };
  }
}

function authorizeAegisPayload_(contents, requiredScope) {
  if (!isAegisAuthRequired_()) {
    return {
      bypassed: true,
      scopes: [],
      user: { email: "AUTH_NOT_ENFORCED" }
    };
  }

  var deviceSession = tryAuthorizeAegisDeviceSessionV283_(
    contents || {},
    requiredScope
  );
  if (deviceSession) return deviceSession;

  var verified = verifyAegisGoogleToken_(contents && contents.auth_token);

  if (
    requiredScope &&
    verified.scopes.indexOf(requiredScope) === -1
  ) {
    logAegisAuthEvent_(
      "AUTHORIZATION_DENIED",
      verified.user.email,
      { scope: requiredScope }
    );
    throw new Error("Authorization denied for scope: " + requiredScope);
  }

  return verified;
}

function aegisScopeForAction_(action, message) {
  if (action === "ai_query") return "ai.query";
  if (action === "calendar_ai") return "calendar.read";
  if (action === "calendar_prepare") return "calendar.write";
  if (action === "get_calendar_range") return "calendar.read";
  if (action === "calendar_confirm") return "calendar.write";
  if (action === "create_calendar_event") return "calendar.write";
  if (action === "resolve_calendar_event") return "calendar.read";
  if (action === "mark_done") return "tasks.write";
  if (action === "create_task") return "tasks.write";
  if (action === "update_task" || action === "delete_task") return "tasks.write";
  if (action === "create_task_list" || action === "rename_task_list") return "tasks.write";
  if (action === "restore_task") return "tasks.write";
  if (action === "promote_followup_task") return "tasks.write";
  if (action === "resolve_followup" || action === "dismiss_followup") return "spark.write";
  if (
    action === "horizon_sync" ||
    action === "refresh_briefing" ||
    String(message || "").indexOf("/horizon") === 0
  ) return "horizon.generate";

  if (action === "get_recent_finance") return "sentinel.read";
  if (action === "get_dashboard") return "dashboard.read";
  if (action === "get_latest_horizon") return "dashboard.read";
  if (action === "get_notifications") return "dashboard.read";
  if (action === "get_followups") return "dashboard.read";
  if (action === "get_task_workspace" || action === "get_task_history") return "tasks.read";
  if (action === "get_intelligence") return "dashboard.read";
  if (action === "get_rss_config" || action === "test_rss_source") return "dashboard.read";
  if (action === "upsert_rss_source" || action === "delete_rss_source") return "dashboard.read";
  if (action === "upsert_rss_category" || action === "rename_rss_category") return "dashboard.read";
  if (
    action === "capture_nutrition" ||
    action === "enqueue_nutrition_capture" ||
    action === "get_nutrition_capture_status" ||
    action === "retry_nutrition_capture"
  ) return "kinetic.read";
  if (action === "get_health") return "dashboard.read";
  if (action === "get_capabilities") return "dashboard.read";

  if (String(message || "").match(/^\/(journal|vent|note|reflect|assess)/i)) {
    return "spark.write";
  }
  if (String(message || "").match(/^\/calories/i)) {
    return "kinetic.read";
  }
  if (String(message || "").match(/^\/(receipts|finance)/i)) {
    return "sentinel.read";
  }
  if (String(message || "").match(/^\/groceries/i)) {
    return "tasks.write";
  }

  if (
    action === "ack_notification" ||
    action === "refresh_intelligence"
  ) return "dashboard.read";

  if (action === "install_automation_triggers") return "dashboard.read";

  return "dashboard.read";
}

function aegisAuthRequiredResponse_(action) {
  return {
    status: "error",
    authenticated: false,
    code: "AEGIS_AUTH_REQUIRED",
    action: action || "",
    error: "Authentication is required for this AEGIS operation."
  };
}

function logAegisAuthEvent_(eventType, email, details) {
  var event = {
    timestamp: new Date().toISOString(),
    event_type: eventType,
    email: email || "unknown",
    details: details || {}
  };

  Logger.log("AEGIS_AUTH_EVENT " + JSON.stringify(event));

  var sheetId =
    PropertiesService.getScriptProperties().getProperty("AEGIS_AUTH_AUDIT_SHEET_ID");

  if (!sheetId) return;

  try {
    var ss = SpreadsheetApp.openById(sheetId);
    var sheet = ss.getSheetByName("Auth Events") || ss.insertSheet("Auth Events");

    if (sheet.getLastRow() === 0) {
      sheet.appendRow(["Timestamp", "Event Type", "Email", "Details"]);
    }

    sheet.appendRow([
      event.timestamp,
      event.event_type,
      event.email,
      JSON.stringify(event.details)
    ]);
  } catch (err) {
    Logger.log("AEGIS auth audit sheet write failed: " + err.message);
  }
}

function requireAegisGetAuth_(action) {
  if (!isAegisAuthRequired_()) return null;
  return jsonOutput(aegisAuthRequiredResponse_(action));
}


/* ============================================================
   GET ROUTER
   ============================================================ */

function doGet(e) {
  var action =
    (e && e.parameter && e.parameter.action)
      ? e.parameter.action
      : "";

  // Public bootstrap endpoint. Client ID is public by design; allowlist remains server-side.
  if (action === "auth_config") {
    return jsonOutput(getAegisPublicAuthConfig_());
  }

  // Reverse geocoding contains no private Workspace data and remains public.
  if (action === "reverseGeocode") {
    var lat = Number(e.parameter.lat);
    var lon = Number(e.parameter.lon);
    return ContentService
      .createTextOutput(JSON.stringify(reverseGeocodeLocation(lat, lon)))
      .setMimeType(ContentService.MimeType.JSON);
  }

  // Once AUTH-1 enforcement is enabled, all Workspace-backed reads are POST-only
  // so identity credentials never appear in URLs, browser history, or proxy logs.
  if (isAegisAuthRequired_()) {
    return jsonOutput(aegisAuthRequiredResponse_(action || "GET"));
  }

  // Compatibility path while AUTH-1 is staged but enforcement is false.
  if (
    action === "getHorizonData" ||
    action === "getSummary"
  ) {
    return jsonOutput(getAegisRuntimeStateV25());
  }

  if (action === "getIntelligence") {
    var forceIntel = String(e.parameter.force || "") === "1";
    return jsonOutput(getIntelligenceFeedV24(forceIntel));
  }

  if (action === "capabilities") {
    return jsonOutput(getAegisCapabilities());
  }

  if (action === "getNotifications") {
    ensureAppointmentReminders();
    return jsonOutput({
      status: "success",
      notifications: getServerNotifications(false)
    });
  }

  if (action === "getRecentFinance") {
    var hours = Math.max(1, Math.min(168, Number(e.parameter.hours) || 72));
    return jsonOutput(getRecentFinanceActivity(hours));
  }

  if (action === "health") {
    return jsonOutput(getAegisHealth());
  }

  if (action === "getLatestHorizonBriefing") {
    return jsonOutput(getLatestHorizonBriefing());
  }

  return jsonOutput({
    status: "online",
    backend_version: AEGIS_BACKEND_VERSION,
    auth: getAegisPublicAuthConfig_(),
    totalCalories: getTodayCaloriesFromSheet()
  });
}

/* ============================================================
   POST ROUTER
   ============================================================ */

function doPost(e) {
  try {
    var contents = JSON.parse(e.postData.contents);

    var message =
      (contents.message || "").trim();

    var action =
      contents.action || "";

    var completedTasks =
      contents.completedTasks || [];

    var taskId =
      contents.task_id || null;

    if (
      taskId &&
      completedTasks.indexOf(taskId) === -1
    ) {
      completedTasks.push(taskId);
    }


    var authActionResult = handleAegisAuthAction_(action, contents);
    if (authActionResult) {
      return jsonOutput(authActionResult);
    }

    // Every non-auth POST crosses the server-side authorization boundary.
    // While AEGIS_AUTH_REQUIRED=false this is a controlled compatibility bypass.
    var requiredScope = aegisScopeForAction_(action, message);
    var authContext;
    try {
      authContext = authorizeAegisPayload_(contents, requiredScope);
    } catch (authError) {
      return jsonOutput({
        status: "error",
        authenticated: false,
        code: "AEGIS_AUTH_FAILED",
        diagnostic_code: String(authError && authError.message || "AEGIS_AUTH_FAILED"),
        error: "AEGIS authentication was rejected. Reconnect explicitly to continue."
      });
    }

    // AUTH-1 protected read endpoints. These replace private GET requests once
    // frontend enforcement is enabled, preventing identity tokens from entering URLs.
    if (action === "get_dashboard") {
      return jsonOutput(getAegisRuntimeStateV25());
    }

    if (action === "get_intelligence") {
      return jsonOutput(getIntelligenceFeedV24(contents.force === true || String(contents.force || "") === "1"));
    }

    if (action === "get_rss_config") {
      return jsonOutput(getAegisRssConfigV1_());
    }

    if (action === "test_rss_source") {
      return jsonOutput(testAegisRssSourceV1_(contents));
    }

    if (action === "upsert_rss_source") {
      return jsonOutput(upsertAegisRssSourceV1_(contents));
    }

    if (action === "delete_rss_source") {
      return jsonOutput(deleteAegisRssSourceV1_(contents));
    }

    if (action === "upsert_rss_category") {
      return jsonOutput(upsertAegisRssCategoryV1_(contents));
    }

    if (action === "rename_rss_category") {
      return jsonOutput(renameAegisRssCategoryV1_(contents));
    }

    if (action === "get_capabilities") {
      return jsonOutput(getAegisCapabilities());
    }

    if (action === "get_notifications") {
      ensureAppointmentReminders();
      return jsonOutput({
        status: "success",
        notifications: getServerNotifications(false)
      });
    }

    if (action === "get_recent_finance") {
      var secureFinanceHours = Math.max(
        1,
        Math.min(168, Number(contents.hours) || 72)
      );
      return jsonOutput(getRecentFinanceActivity(secureFinanceHours));
    }

    if (action === "get_health") {
      return jsonOutput(getAegisHealth());
    }

    if (action === "get_latest_horizon") {
      return jsonOutput(getLatestHorizonBriefing());
    }

    if (action === "get_calendar_range") {
      return jsonOutput(getAegisCalendarRangeV265_(contents.start_date, contents.end_date));
    }

    if (action === "get_followups") {
      return jsonOutput(getAegisFollowupsV265_());
    }

    if (action === "create_task") {
      return jsonOutput(createAegisTaskV265_(contents));
    }

    if (action === "get_task_workspace") {
      return jsonOutput(getAegisTaskWorkspaceV1_());
    }

    if (action === "get_task_history") {
      return jsonOutput(getAegisTaskHistoryV1_(contents));
    }

    if (action === "update_task") {
      return jsonOutput(updateAegisTaskV1_(contents));
    }

    if (action === "delete_task") {
      return jsonOutput(deleteAegisTaskV1_(contents));
    }

    if (action === "restore_task") {
      return jsonOutput(restoreAegisTaskV1_(contents));
    }

    if (action === "create_task_list") {
      return jsonOutput(createAegisTaskListV1_(contents));
    }

    if (action === "rename_task_list") {
      return jsonOutput(renameAegisTaskListV1_(contents));
    }

    if (action === "resolve_followup") {
      return jsonOutput(setAegisFollowupLifecycleV265_(
        contents.followup_id,
        "RESOLVED",
        contents.title || ""
      ));
    }

    if (action === "dismiss_followup") {
      return jsonOutput(setAegisFollowupLifecycleV265_(
        contents.followup_id,
        "DISMISSED",
        contents.title || ""
      ));
    }

    if (action === "promote_followup_task") {
      return jsonOutput(promoteAegisFollowupTaskV265_(contents));
    }

    if (action === "ai_query") {
      return jsonOutput(handleAegisAiQueryV1_(contents));
    }

    if (action === "calendar_ai") {
      return jsonOutput(handleAegisCalendarAiV2_(contents, authContext));
    }

    if (action === "calendar_prepare") {
      return jsonOutput(handleAegisCalendarPrepareV1_(contents, authContext));
    }

    if (action === "calendar_confirm") {
      return jsonOutput(confirmAegisCalendarMutationV2_(contents, authContext));
    }

    if (action === "ack_notification") {
      return jsonOutput(ackServerNotification(contents.notificationId));
    }

    if (action === "resolve_calendar_event") {
      return jsonOutput({ status: "success", event: resolveCalendarEventText(contents.text || "") });
    }

    if (action === "create_calendar_event") {
      return jsonOutput(createCalendarEventFromResolved(contents.event || {}));
    }

    if (action === "refresh_intelligence") {
      return jsonOutput(getIntelligenceFeedV24(true));
    }

    if (action === "install_automation_triggers") {
      return jsonOutput({ status: "success", triggers: installAegisAutomationTriggers() });
    }

    if (action === "enqueue_nutrition_capture") {
      return jsonOutput(enqueueAegisNutritionCaptureV282_(contents));
    }

    if (action === "get_nutrition_capture_status") {
      return jsonOutput(handleAegisNutritionCaptureStatusV282_(contents));
    }

    if (action === "retry_nutrition_capture") {
      return jsonOutput(handleAegisNutritionCaptureRetryV282_(contents));
    }

    if (action === "capture_nutrition") {
      return jsonOutput(handleAegisNutritionCaptureV281_(contents));
    }

    /* --------------------------------------------------------
       1. TASK COMPLETION
       -------------------------------------------------------- */

    if (
      action === "mark_done" ||
      message.indexOf("mark_done:") === 0 ||
      message.indexOf("/note mark_done:") === 0
    ) {

      for (
        var i = 0;
        i < completedTasks.length;
        i++
      ) {
        try {
          Tasks.Tasks.patch(
            { status: "completed" },
            String(contents.task_list_id || "@default"),
            completedTasks[i]
          );
        } catch (err) {
          Logger.log(
            "Tasks patch error: " +
            err.message
          );
        }
      }

      var titlesStr =
        message
          .replace("/note mark_done:", "")
          .replace("mark_done:", "")
          .trim();

      var itemsToMark =
        titlesStr
          .split("|")
          .map(function(s) {
            return s.trim();
          })
          .filter(Boolean);

      if (itemsToMark.length > 0) {
        var doc =
          DocumentApp.openById(
            CONFIG.NOTES_DOC_ID
          );

        var timeStamp =
          Utilities.formatDate(
            new Date(),
            CONFIG.TIMEZONE,
            "M/d/yyyy, h:mm:ss a"
          );

        doc
          .getBody()
          .appendParagraph(
            "[" +
            timeStamp +
            "] mark_done: " +
            itemsToMark.join(" | ")
          );

        doc.saveAndClose();
      }

      return ContentService
        .createTextOutput(
          JSON.stringify({
            result:
              "✅ SYNC COMPLETE! Updated Google Tasks/Notes; retired HORIZON JSON path was not touched.",
            totalCalories:
              getTodayCaloriesFromSheet(),
            legacy_horizon_json:
              "RETIRED_HORIZON_PATH_BLOCKED"
          })
        )
        .setMimeType(ContentService.MimeType.JSON);
    }

    /* --------------------------------------------------------
       2. HORIZON GENERATION — CONTRACT-BOUNDED V2.5
       -------------------------------------------------------- */

    if (
      message.indexOf("/horizon") === 0 ||
      action === "refresh_briefing" ||
      action === "horizon_sync" ||
      contents.command === "/horizon"
    ) {
      var generationResult = runHorizonPipeline();
      var runtimeState = getAegisRuntimeStateV25();

      return ContentService
        .createTextOutput(
          JSON.stringify({
            result: "✅ HORIZON V2.5 GENERATED AND WRITTEN TO CANONICAL GOOGLE DOC.",
            generation: generationResult,
            totalCalories: runtimeState.health_nutrition && runtimeState.health_nutrition.total_calories,
            runtimeState: runtimeState,
            legacy_horizon_json: "RETIRED_HORIZON_PATH_BLOCKED"
          })
        )
        .setMimeType(ContentService.MimeType.JSON);
    }

    /* --------------------------------------------------------
       3. CALORIE / NUTRITION LOGGING
       -------------------------------------------------------- */

    if (
      message.indexOf("/calories") === 0
    ) {

      var foodInput =
        message
          .replace("/calories", "")
          .trim();

      var calorieResult =
        handleCalorieLogging(foodInput);

      return ContentService
        .createTextOutput(
          JSON.stringify({
            result:
              calorieResult,

            totalCalories:
              getTodayCaloriesFromSheet()
          })
        )
        .setMimeType(
          ContentService.MimeType.JSON
        );
    }

    /* --------------------------------------------------------
       4. JOURNAL
       -------------------------------------------------------- */

    if (
      message.indexOf("/journal") === 0 ||
      message.indexOf("/vent") === 0
    ) {

      var journalText =
        message
          .replace(
            /^(\/journal|\/vent)\s*/i,
            ""
          )
          .trim();

      var journalResult =
        handleJournalLogging(
          journalText
        );

      return ContentService
        .createTextOutput(
          JSON.stringify({
            result:
              journalResult,

            totalCalories:
              getTodayCaloriesFromSheet()
          })
        )
        .setMimeType(
          ContentService.MimeType.JSON
        );
    }

    /* --------------------------------------------------------
       5. FINANCE / RECEIPTS
       -------------------------------------------------------- */

    if (
      message.indexOf("/receipts") === 0 ||
      message.indexOf("/finance") === 0
    ) {

      var financeText =
        message
          .replace(
            /^(\/receipts|\/finance)\s*/i,
            ""
          )
          .trim();

      var financeResult =
        handleFinanceLogging(
          financeText
        );

      return ContentService
        .createTextOutput(
          JSON.stringify({
            result:
              financeResult,

            totalCalories:
              getTodayCaloriesFromSheet()
          })
        )
        .setMimeType(
          ContentService.MimeType.JSON
        );
    }

    /* --------------------------------------------------------
       6. GROCERIES
       -------------------------------------------------------- */

    if (
      message.indexOf("/groceries") === 0
    ) {

      var groceryInput =
        message
          .replace("/groceries", "")
          .trim();

      var groceryResult =
        handleGroceryDispatch(
          groceryInput
        );

      return ContentService
        .createTextOutput(
          JSON.stringify({
            result:
              groceryResult,

            totalCalories:
              getTodayCaloriesFromSheet()
          })
        )
        .setMimeType(
          ContentService.MimeType.JSON
        );
    }

    /* --------------------------------------------------------
       7. STANDARD NOTE
       -------------------------------------------------------- */

    var noteText =
      message
        .replace(/^\/note\s*/i, "")
        .trim();

    var timeStamp =
      Utilities.formatDate(
        new Date(),
        CONFIG.TIMEZONE,
        "M/d/yyyy, h:mm:ss a"
      );

    var doc =
      DocumentApp.openById(
        CONFIG.NOTES_DOC_ID
      );

    doc
      .getBody()
      .appendParagraph(
        "[" +
        timeStamp +
        "] " +
        noteText
      );

    doc.saveAndClose();

    return ContentService
      .createTextOutput(
        JSON.stringify({
          result:
            "✅ Logged entry to Notes & Ideas Log: " +
            noteText,

          totalCalories:
            getTodayCaloriesFromSheet()
        })
      )
      .setMimeType(
        ContentService.MimeType.JSON
      );

  } catch (err) {

    return ContentService
      .createTextOutput(
        JSON.stringify({
          status: "error",
          error: err.message
        })
      )
      .setMimeType(
        ContentService.MimeType.JSON
      );
  }
}

/* ============================================================
   NUTRITION
   ============================================================ */

function handleCalorieLogging(foodText) {

  if (!foodText) {
    return "⚠️ Please provide food details to log.";
  }

  var parsedItems =
    callGeminiForMacros(foodText);

  var sheet =
    SpreadsheetApp
      .openById(
        CONFIG.CALORIES_SHEET_ID
      )
      .getActiveSheet();

  var dateStr =
    Utilities.formatDate(
      new Date(),
      CONFIG.TIMEZONE,
      "M/d/yyyy"
    );

  var timeStr =
    Utilities.formatDate(
      new Date(),
      CONFIG.TIMEZONE,
      "h:mm:ss a"
    );

  if (
    !parsedItems ||
    parsedItems.length === 0
  ) {

    sheet.appendRow([
      dateStr,
      timeStr,
      foodText,
      "1 serving",
      0,
      0,
      0,
      0,
      0,
      "Logged via AEGIS Dashboard"
    ]);

    return (
      "⚠️ Logged '" +
      foodText +
      "' to Sheet " +
      "(Macros pending - verify GEMINI_API_KEY in Script Properties)."
    );
  }

  var summaryLines = [];

  for (
    var i = 0;
    i < parsedItems.length;
    i++
  ) {

    var item =
      parsedItems[i];

    var cals =
      Number(item.calories) || 0;

    var prot =
      Number(item.protein) || 0;

    var carbs =
      Number(item.carbs) || 0;

    var fat =
      Number(item.fat) || 0;

    var sod =
      Number(item.sodium) || 0;

    sheet.appendRow([
      dateStr,
      timeStr,
      item.item || foodText,
      item.portion || "1 serving",
      cals,
      prot,
      carbs,
      fat,
      sod,
      "Logged via AEGIS AI"
    ]);

    summaryLines.push(
      "• " +
      item.item +
      " (" +
      (item.portion || "1 serv") +
      "): " +
      cals +
      " kcal | " +
      prot +
      "g P | " +
      carbs +
      "g C | " +
      fat +
      "g F"
    );
  }

  var dailyTotal =
    getTodayCaloriesFromSheet();

  return (
    "✅ LOGGED NUTRITION VIA GEMINI AI:\n" +
    summaryLines.join("\n") +
    "\n\n📊 Daily Total: " +
    dailyTotal +
    " kcal"
  );
}

function callGeminiForMacros(foodText) {
  try {
    const prompt =
      'You are a clinical nutritionist. Extract or accurately estimate the nutritional breakdown ' +
      'for this food description: "' + foodText + '".\n' +
      'Return ONLY a raw JSON array of objects with the exact schema:\n' +
      '[{"item":"Full specific name","portion":"Serving size","calories":140,' +
      '"protein":20.0,"carbs":8.0,"fat":2.5,"sodium":150}]\n' +
      'Do NOT include markdown formatting or backticks. Return raw JSON.';
    const raw = callGemini(prompt);
    const clean = raw.replace(/```json/gi, "").replace(/```/g, "").trim();
    return JSON.parse(clean);
  } catch (e) {
    Logger.log("Gemini macro extraction error: " + e.message);
    return null;
  }
}

/* ============================================================
   JOURNAL
   ============================================================ */

function handleJournalLogging(
  journalText
) {

  if (!journalText) {
    return "⚠️ Please provide content for your journal entry.";
  }

  var doc =
    DocumentApp.openById(
      CONFIG.JOURNAL_DOC_ID
    );

  var timeStamp =
    Utilities.formatDate(
      new Date(),
      CONFIG.TIMEZONE,
      "M/d/yyyy, h:mm:ss a"
    );

  var body =
    doc.getBody();

  body.appendParagraph(
    "\n--- JOURNAL [" +
    timeStamp +
    "] ---"
  );

  body.appendParagraph(
    journalText
  );

  doc.saveAndClose();

  return (
    "✅ Logged reflection to Journal Pad:\n\"" +
    (
      journalText.length > 80
        ? journalText.substring(0, 80) + "..."
        : journalText
    ) +
    "\""
  );
}

/* ============================================================
   FINANCE / RECEIPTS
   ============================================================ */

function handleFinanceLogging(
  financeText
) {

  if (!financeText) {
    return (
      "⚠️ Please provide expense details " +
      "(e.g. Target $45.20 Groceries)."
    );
  }

  var sheet =
    SpreadsheetApp
      .openById(
        CONFIG.FINANCE_SHEET_ID
      )
      .getActiveSheet();

  var dateStr =
    Utilities.formatDate(
      new Date(),
      CONFIG.TIMEZONE,
      "yyyy-MM-dd"
    );

  var timeStr =
    Utilities.formatDate(
      new Date(),
      CONFIG.TIMEZONE,
      "h:mm:ss a"
    );

  var amountMatch =
    financeText.match(
      /\$?([0-9]+(?:\.[0-9]{2})?)/
    );

  // FIX:
  // Extract capture group rather than
  // attempting to Number() the match array.
  var amount =
    amountMatch
      ? Number(amountMatch[1])
      : 0;

  var description =
    financeText
      .replace(
        /\$?([0-9]+(?:\.[0-9]{2})?)/,
        ""
      )
      .trim();

  sheet.appendRow([
    dateStr,
    timeStr,
    description ||
      "General Purchase",
    amount,
    "Discretionary / General",
    "Credit Card / Debit",
    "Logged via AEGIS Dashboard"
  ]);

  return (
    "✅ Recorded Expense in Receipts & Expense Intake Log:\n• " +
    (
      description ||
      "Purchase"
    ) +
    ": $" +
    amount.toFixed(2)
  );
}

/* ============================================================
   GROCERIES
   ============================================================ */

function handleGroceryDispatch(
  groceryText
) {

  if (!groceryText) {
    return "⚠️ No grocery items provided.";
  }

  var items =
    groceryText
      .split("|")
      .map(function(s) {
        return s.trim();
      })
      .filter(Boolean);

  var added = [];

  for (
    var i = 0;
    i < items.length;
    i++
  ) {

    var cleanName =
      items[i]
        .replace(/^🛒\s*/, "");

    try {

      Tasks.Tasks.insert(
        {
          title:
            "🛒 " +
            cleanName
        },
        "@default"
      );

      added.push(
        cleanName
      );

    } catch (e) {

      Logger.log(
        "Tasks insert error: " +
        e.message
      );
    }
  }

  if (added.length > 0) {

    return (
      "✅ Added " +
      added.length +
      " item(s) to Google Tasks Grocery List:\n• " +
      added.join("\n• ")
    );
  }

  return (
    "✅ Dispatched: " +
    groceryText
  );
}

/* ============================================================
   HORIZON V2.5 BOUNDED DOMAIN CONTRACTS
   ============================================================ */

function normalizeSheetDateV25(value) {
  if (!value) return null;
  if (value instanceof Date) {
    return Utilities.formatDate(value, CONFIG.TIMEZONE, "yyyy-MM-dd");
  }
  var text = String(value).trim();
  var iso = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (iso) return iso[1] + "-" + ("0" + iso[2]).slice(-2) + "-" + ("0" + iso[3]).slice(-2);
  var us = text.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (us) return us[3] + "-" + ("0" + us[1]).slice(-2) + "-" + ("0" + us[2]).slice(-2);
  return null;
}

function getKineticConfigurationV25() {
  var out = {
    calorie_target: { status: "UNCONFIGURED" },
    protein_target: { status: "UNCONFIGURED" },
    source: CONFIG.KINETIC_CONFIG_DOC_ID,
    verified_at: new Date().toISOString()
  };
  try {
    var text = DocumentApp.openById(CONFIG.KINETIC_CONFIG_DOC_ID).getBody().getText();
    var calorie = text.match(/(?:Caloric|Calorie)\s+(?:Intake\s+)?Target\s*:?\s*([\d,]+)\s*(?:-|–|—|to)\s*([\d,]+)\s*kcal/i);
    var protein = text.match(/Protein\s+Target\s*:?\s*~?\s*([\d,]+)\s*g/i);
    if (calorie) {
      out.calorie_target = {
        status: "CONFIGURED",
        min: Number(calorie[1].replace(/,/g, "")),
        max: Number(calorie[2].replace(/,/g, "")),
        unit: "kcal",
        configuration_source: CONFIG.KINETIC_CONFIG_DOC_ID
      };
    }
    if (protein) {
      out.protein_target = {
        status: "CONFIGURED",
        target: Number(protein[1].replace(/,/g, "")),
        unit: "g",
        configuration_source: CONFIG.KINETIC_CONFIG_DOC_ID
      };
    }
  } catch (err) {
    out.error = err.message;
  }
  return out;
}

function buildKineticToHorizonV2() {
  var today = Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "yyyy-MM-dd");
  var cfg = getKineticConfigurationV25();
  var totals = { calories: null, protein: null, carbs: null, fat: null, sodium: null };
  var rowCount = 0;
  var freshness = null;
  try {
    var sheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID).getActiveSheet();
    var values = sheet.getDataRange().getValues();
    var sum = { calories: 0, protein: 0, carbs: 0, fat: 0, sodium: 0 };
    for (var i = 1; i < values.length; i++) {
      if (normalizeSheetDateV25(values[i][0]) !== today) continue;
      rowCount++;
      sum.calories += Number(values[i][4]) || 0;
      sum.protein += Number(values[i][5]) || 0;
      sum.carbs += Number(values[i][6]) || 0;
      sum.fat += Number(values[i][7]) || 0;
      sum.sodium += Number(values[i][8]) || 0;
    }
    if (rowCount > 0) totals = sum;
    freshness = new Date().toISOString();
  } catch (err) {
    return {
      date: today,
      calories_consumed: null,
      calorie_target_display: "Unconfigured / source unavailable",
      protein_consumed_g: null,
      protein_target_display: "Unconfigured / source unavailable",
      carbs_consumed_g: null,
      fat_consumed_g: null,
      sodium_consumed_mg: null,
      adherence_status: "UNCONFIGURED",
      tracker_link: CONFIG.KINETIC_TRACKER_URL,
      source_status: "UNAVAILABLE",
      source_error: err.message,
      source_freshness: null
    };
  }

  var calorieDisplay = cfg.calorie_target.status === "CONFIGURED"
    ? cfg.calorie_target.min.toLocaleString() + "–" + cfg.calorie_target.max.toLocaleString() + " kcal"
    : "Unconfigured";
  var proteinDisplay = cfg.protein_target.status === "CONFIGURED"
    ? "~" + cfg.protein_target.target.toLocaleString() + "g"
    : "Unconfigured";

  var adherence = "NO_ENTRIES";
  if (rowCount > 0) {
    if (cfg.calorie_target.status !== "CONFIGURED" || cfg.protein_target.status !== "CONFIGURED") adherence = "UNCONFIGURED";
    else if (totals.calories < cfg.calorie_target.min) adherence = "UNDER_TARGET";
    else if (totals.calories > cfg.calorie_target.max) adherence = "EXCEEDED";
    else adherence = "ON_TRACK";
  }

  return {
    date: today,
    calories_consumed: totals.calories,
    calorie_target_display: calorieDisplay,
    protein_consumed_g: totals.protein,
    protein_target_display: proteinDisplay,
    carbs_consumed_g: totals.carbs,
    fat_consumed_g: totals.fat,
    sodium_consumed_mg: totals.sodium,
    adherence_status: adherence,
    tracker_link: CONFIG.KINETIC_TRACKER_URL,
    source_status: "AVAILABLE",
    source_freshness: freshness
  };
}

function parseJournalTimestampV25(text) {
  var m = String(text || "").match(/(\d{1,2})\/(\d{1,2})\/(\d{4}),\s*(\d{1,2}):(\d{2}):(\d{2})\s*(AM|PM)/i);
  if (!m) return null;
  var hour = Number(m[4]);
  var ap = m[7].toUpperCase();
  if (ap === "PM" && hour < 12) hour += 12;
  if (ap === "AM" && hour === 12) hour = 0;
  return new Date(Number(m[3]), Number(m[1]) - 1, Number(m[2]), hour, Number(m[5]), Number(m[6]));
}

function classifyExplicitSparkStateV25(text) {
  var s = String(text || "").toLowerCase();
  var energy = null, sensory = null, affect = null;
  if (/\b(?:i feel|i am|i'm|my energy(?: level)? is|energy is)\b.{0,45}\b(?:exhausted|depleted|drained|very tired)\b/i.test(s)) energy = "DEPLETED";
  else if (/\b(?:i feel|i am|i'm|my energy(?: level)? is|energy is)\b.{0,45}\b(?:low|tired|sluggish)\b/i.test(s)) energy = "LOW";
  else if (/\b(?:i feel|i am|i'm|my energy(?: level)? is|energy is)\b.{0,45}\b(?:high|energized|energetic)\b/i.test(s)) energy = "HIGH";
  else if (/\b(?:i feel|i am|i'm|my energy(?: level)? is|energy is)\b.{0,45}\b(?:moderate|okay|steady)\b/i.test(s)) energy = "MODERATE";

  if (/\b(?:i feel|i am|i'm|sensory load is|i'm feeling)\b.{0,45}\b(?:overloaded|overstimulated|sensory overload)\b/i.test(s)) sensory = "OVERLOADED";
  else if (/\b(?:i feel|i am|i'm|sensory load is)\b.{0,45}\b(?:saturated|sensory saturated)\b/i.test(s)) sensory = "SATURATED";
  else if (/\b(?:i feel|i am|i'm|sensory load is)\b.{0,45}\b(?:calm|settled)\b/i.test(s)) sensory = "CALM";
  else if (/\b(?:i feel|i am|i'm|sensory load is)\b.{0,45}\b(?:moderate|manageable)\b/i.test(s)) sensory = "MODERATE";

  var am = s.match(/\b(?:i feel|i'm feeling|i am feeling)\b.{0,12}\b(anxious|frustrated|angry|sad|happy|content|stressed|overwhelmed|calm)\b/i);
  if (am) affect = am[1];
  return { energy: energy, sensory: sensory, affect: affect };
}

function buildSparkToHorizonV2() {
  var now = new Date();
  var freshMs = CONFIG.SPARK_FRESH_HOURS * 60 * 60 * 1000;
  var sevenDaysMs = 7 * 24 * 60 * 60 * 1000;
  var body = DocumentApp.openById(CONFIG.JOURNAL_DOC_ID).getBody();
  var entries = [], current = null;

  for (var i = 0; i < body.getNumChildren(); i++) {
    var child = body.getChild(i);
    if (child.getType() !== DocumentApp.ElementType.PARAGRAPH) continue;
    var line = child.asParagraph().getText();
    var hm = line.match(/---\s*JOURNAL\s*\[([^\]]+)\]\s*---/i);
    if (hm) {
      if (current) entries.push(current);
      current = { timestamp: parseJournalTimestampV25(hm[1]), lines: [] };
    } else if (current && line.trim()) current.lines.push(line.trim());
  }
  if (current) entries.push(current);
  entries = entries.filter(function(e) { return e.timestamp && now.getTime() - e.timestamp.getTime() <= sevenDaysMs; });
  entries.sort(function(a, b) { return b.timestamp - a.timestamp; });

  var latest = entries.length ? entries[0].timestamp : null;
  var out = {
    generated_at: now.toISOString(),
    state_status: "OMITTED",
    source_window: { start: new Date(now.getTime() - freshMs).toISOString(), end: now.toISOString() },
    current_self_reported_state: null,
    supported_patterns: [],
    strategy_observations: [],
    action_candidates: [],
    provenance_summary: { evidence_items: entries.length, latest_entry_timestamp: latest ? latest.toISOString() : null }
  };
  if (!entries.length) return out;
  if (now.getTime() - latest.getTime() > freshMs) { out.state_status = "STALE"; return out; }

  var recent = entries.filter(function(e) { return now.getTime() - e.timestamp.getTime() <= freshMs; });
  var energy = null, sensory = null, affect = null;
  recent.forEach(function(e) {
    var c = classifyExplicitSparkStateV25(e.lines.join(" "));
    if (!energy && c.energy) energy = c.energy;
    if (!sensory && c.sensory) sensory = c.sensory;
    if (!affect && c.affect) affect = c.affect;
  });

  // No synthetic placeholders: incomplete explicit evidence means no HORIZON SPARK card.
  if (!energy || !sensory || !affect) { out.state_status = "NOT_MATERIAL"; return out; }
  out.state_status = "AVAILABLE";
  out.current_self_reported_state = {
    energy_level: { value: energy, basis: "SELF_REPORTED", confidence: 1.0 },
    sensory_load: { value: sensory, basis: "SELF_REPORTED", confidence: 1.0 },
    affect: { description: affect, basis: "SELF_REPORTED" }
  };
  return out;
}

function stripNotesTimestampV25(text) {
  return String(text || "").replace(/^\s*\[[^\]]+\]\s*/, "").trim();
}

function getActiveNotesV25() {
  var body = DocumentApp.openById(CONFIG.NOTES_DOC_ID).getBody();
  var activeRe = /^(ACTIVE|TODO|OPEN|FOLLOW_UP)\s*:\s*/i;
  var tombstoneRe = /^(DONE|COMPLETED|MARK_DONE|MARK_DOWN|IGNORE|TEST)\s*:\s*/i;
  var active = [], tombstones = 0, archival = 0;
  for (var i = 0; i < body.getNumChildren(); i++) {
    var child = body.getChild(i);
    if (child.getType() !== DocumentApp.ElementType.PARAGRAPH) continue;
    var text = stripNotesTimestampV25(child.asParagraph().getText());
    if (!text) continue;
    if (activeRe.test(text)) active.push(text);
    else if (tombstoneRe.test(text)) tombstones++;
    else archival++;
  }
  return {
    status: "AVAILABLE",
    active_candidates: active.slice(-25),
    active_count: active.length,
    excluded_tombstones_count: tombstones,
    excluded_archival_count: archival,
    filter: "ACTIVE_NOTE_FILTER_V2.0.1"
  };
}


/* ============================================================
   AEGIS v2.6.5 — FOLLOW-UPS / LOCAL TASK PROMOTION / CALENDAR RANGE
   Notes remain durable provenance for AI-derived follow-ups.
   GPOS / finance contracts are intentionally untouched.
   ============================================================ */

function aegisFollowupIdV265_(value) {
  var normalized = String(value || "").trim().toLowerCase().replace(/\s+/g, " ");
  var digest = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256,
    normalized,
    Utilities.Charset.UTF_8
  );
  var hex = digest.slice(0, 8).map(function(b) {
    var v = (b + 256) % 256;
    return ("0" + v.toString(16)).slice(-2);
  }).join("");
  return "FU-" + hex.toUpperCase();
}

function parseAegisFollowupBodyV265_(body) {
  var active = {};
  var lifecycle = {};

  for (var i = 0; i < body.getNumChildren(); i++) {
    var child = body.getChild(i);
    if (child.getType() !== DocumentApp.ElementType.PARAGRAPH) continue;

    var raw = child.asParagraph().getText();
    var text = stripNotesTimestampV25(raw);
    if (!text) continue;

    var activeMatch = text.match(/^FOLLOW_UP\s*:\s*(?:\[([^\]]+)\]\s*)?(.*)$/i);
    if (activeMatch) {
      var payload = String(activeMatch[2] || "").trim();
      var parts = payload.split(/\s+\|\s+/);
      var title = String(parts.shift() || "Follow-up").trim();
      var summary = parts.join(" | ").trim();
      var id = String(activeMatch[1] || "").trim() || aegisFollowupIdV265_(title + "|" + summary);
      active[id] = {
        id: id,
        title: title,
        summary: summary,
        type: "FOLLOW_UP",
        priority: "MEDIUM",
        status: "ACTIVE",
        source_excerpt: payload,
        promoted_task_id: null
      };
      continue;
    }

    var stateMatch = text.match(/^FOLLOW_UP_(RESOLVED|DISMISSED|PROMOTED)\s*:\s*\[([^\]]+)\]\s*(.*)$/i);
    if (stateMatch) {
      var state = String(stateMatch[1]).toUpperCase();
      var stateId = String(stateMatch[2]).trim();
      var tail = String(stateMatch[3] || "");
      var taskMatch = tail.match(/task_id=([^\s|]+)/i);
      lifecycle[stateId] = {
        status: state,
        promoted_task_id: taskMatch ? taskMatch[1] : null
      };
    }
  }

  Object.keys(lifecycle).forEach(function(id) {
    if (!active[id]) return;
    active[id].status = lifecycle[id].status;
    if (lifecycle[id].promoted_task_id) {
      active[id].promoted_task_id = lifecycle[id].promoted_task_id;
    }
  });

  return Object.keys(active).map(function(id) { return active[id]; });
}

function getAegisFollowupsV265_() {
  var body = DocumentApp.openById(CONFIG.NOTES_DOC_ID).getBody();
  var items = parseAegisFollowupBodyV265_(body);
  return {
    status: "success",
    contract: "AEGIS_FOLLOWUPS_V1",
    generated_at: new Date().toISOString(),
    items: items,
    active_count: items.filter(function(x) {
      return x.status !== "RESOLVED" && x.status !== "DISMISSED";
    }).length
  };
}

function appendAegisNoteMarkerV265_(marker) {
  var doc = DocumentApp.openById(CONFIG.NOTES_DOC_ID);
  var stamp = Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "M/d/yyyy, h:mm:ss a");
  doc.getBody().appendParagraph("[" + stamp + "] " + marker);
  doc.saveAndClose();
}

function setAegisFollowupLifecycleV265_(followupId, state, title) {
  var id = String(followupId || "").trim();
  if (!id) throw new Error("followup_id is required.");

  var normalized = String(state || "").toUpperCase();
  if (["RESOLVED", "DISMISSED"].indexOf(normalized) === -1) {
    throw new Error("Unsupported follow-up lifecycle state.");
  }

  appendAegisNoteMarkerV265_(
    "FOLLOW_UP_" + normalized + ": [" + id + "]" +
    (title ? " | " + String(title).trim() : "")
  );

  return {
    status: "success",
    contract: "AEGIS_FOLLOWUP_ACTION_V1",
    followup_id: id,
    lifecycle: normalized
  };
}

function createAegisTaskV265_(contents) {
  var title = String(contents && contents.title || "").trim();
  if (!title) throw new Error("Task title is required.");
  if (title.length > 1024) throw new Error("Task title is too long.");
  var taskListId = String(contents && contents.task_list_id || "@default").trim() || "@default";

  var resource = {
    title: title,
    notes: String(contents && contents.notes || "").trim()
  };

  if (contents && contents.due) {
    var due = new Date(contents.due);
    if (!isNaN(due.getTime())) resource.due = due.toISOString();
  }

  var task = Tasks.Tasks.insert(resource, taskListId);

  return {
    status: "success",
    contract: "AEGIS_TASK_ACTION_V1",
    task: {
      id: task.id,
      task_list_id: taskListId,
      title: task.title,
      status: task.status,
      due: task.due || null
    },
    local_id: contents && contents.local_id || null
  };
}

function listAegisTaskListsV1_() {
  var items = [];
  var pageToken = null;
  var pages = 0;
  do {
    var options = { maxResults: 100 };
    if (pageToken) options.pageToken = pageToken;
    var page = Tasks.Tasklists.list(options);
    (page.items || []).forEach(function(item) {
      if (items.length < 25) items.push({ id: item.id, title: item.title || "Untitled list" });
    });
    pageToken = page.nextPageToken || null;
    pages++;
  } while (pageToken && pages < 3 && items.length < 25);
  return items;
}

function serializeAegisTaskV1_(task, list) {
  return {
    id: task.id,
    task_list_id: list.id,
    task_list_title: list.title,
    title: task.title || "Untitled Task",
    notes: task.notes || "",
    due: task.due || null,
    updated: task.updated || null,
    completed: task.completed || null,
    status: task.status || "needsAction"
  };
}

function getAegisTaskWorkspaceV1_() {
  var lists = listAegisTaskListsV1_();
  var tasks = [];
  lists.forEach(function(list) {
    var page = Tasks.Tasks.list(list.id, {
      maxResults: 100,
      showCompleted: false,
      showHidden: false
    });
    (page.items || []).forEach(function(task) {
      if (!task.deleted && task.status !== "completed") tasks.push(serializeAegisTaskV1_(task, list));
    });
  });
  return {
    status: "success",
    contract: "AEGIS_TASK_WORKSPACE_V1",
    generated_at: new Date().toISOString(),
    lists: lists,
    tasks: tasks,
    active_count: tasks.length
  };
}

function getAegisTaskHistoryV1_(contents) {
  var days = Math.max(1, Math.min(30, Number(contents && contents.days) || 7));
  var requestedListId = String(contents && contents.task_list_id || "").trim();
  var cutoff = new Date(Date.now() - days * 86400000);
  var lists = listAegisTaskListsV1_().filter(function(list) {
    return !requestedListId || list.id === requestedListId;
  });
  var items = [];
  lists.forEach(function(list) {
    var page = Tasks.Tasks.list(list.id, {
      maxResults: 100,
      showCompleted: true,
      showHidden: true,
      completedMin: cutoff.toISOString()
    });
    (page.items || []).forEach(function(task) {
      if (!task.deleted && task.status === "completed" && task.completed && new Date(task.completed) >= cutoff) {
        items.push(serializeAegisTaskV1_(task, list));
      }
    });
  });
  items.sort(function(a, b) { return String(b.completed).localeCompare(String(a.completed)); });
  return {
    status: "success",
    contract: "AEGIS_TASK_HISTORY_V1",
    days: days,
    generated_at: new Date().toISOString(),
    items: items.slice(0, 200)
  };
}

function updateAegisTaskV1_(contents) {
  var taskId = String(contents && contents.task_id || "").trim();
  var taskListId = String(contents && contents.task_list_id || "").trim();
  var title = String(contents && contents.title || "").trim();
  if (!taskId || !taskListId || !title) throw new Error("Task list, task ID, and title are required.");
  if (title.length > 1024) throw new Error("Task title is too long.");
  var resource = { title: title, notes: String(contents.notes || "").trim() };
  if (contents.clear_due === true) resource.due = null;
  else if (contents.due) {
    var due = new Date(contents.due);
    if (isNaN(due.getTime())) throw new Error("Task due date is invalid.");
    resource.due = due.toISOString();
  }
  var task = Tasks.Tasks.patch(resource, taskListId, taskId);
  return { status: "success", contract: "AEGIS_TASK_ACTION_V1", operation: "UPDATE", task: serializeAegisTaskV1_(task, { id: taskListId, title: "" }) };
}

function deleteAegisTaskV1_(contents) {
  var taskId = String(contents && contents.task_id || "").trim();
  var taskListId = String(contents && contents.task_list_id || "").trim();
  if (!taskId || !taskListId) throw new Error("Task list and task ID are required.");
  Tasks.Tasks.remove(taskListId, taskId);
  return { status: "success", contract: "AEGIS_TASK_ACTION_V1", operation: "DELETE", task_id: taskId, task_list_id: taskListId };
}

function restoreAegisTaskV1_(contents) {
  var taskId = String(contents && contents.task_id || "").trim();
  var taskListId = String(contents && contents.task_list_id || "").trim();
  if (!taskId || !taskListId) throw new Error("Task list and task ID are required.");
  var task = Tasks.Tasks.patch({ status: "needsAction" }, taskListId, taskId);
  return { status: "success", contract: "AEGIS_TASK_ACTION_V1", operation: "RESTORE", task: serializeAegisTaskV1_(task, { id: taskListId, title: "" }) };
}

function createAegisTaskListV1_(contents) {
  var title = String(contents && contents.title || "").trim();
  if (!title || title.length > 100) throw new Error("A task-list title between 1 and 100 characters is required.");
  var list = Tasks.Tasklists.insert({ title: title });
  return { status: "success", contract: "AEGIS_TASK_LISTS_V1", operation: "CREATE", task_list: { id: list.id, title: list.title } };
}

function renameAegisTaskListV1_(contents) {
  var taskListId = String(contents && contents.task_list_id || "").trim();
  var title = String(contents && contents.title || "").trim();
  if (!taskListId || !title || title.length > 100) throw new Error("Task-list ID and a title between 1 and 100 characters are required.");
  var list = Tasks.Tasklists.patch({ title: title }, taskListId);
  return { status: "success", contract: "AEGIS_TASK_LISTS_V1", operation: "RENAME", task_list: { id: list.id, title: list.title } };
}

function promoteAegisFollowupTaskV265_(contents) {
  var id = String(contents && contents.followup_id || "").trim();
  if (!id) throw new Error("followup_id is required.");

  var created = createAegisTaskV265_({
    title: contents.title || "AEGIS Follow-up",
    notes: contents.notes || "",
    due: contents.due || null,
    task_list_id: contents.task_list_id || "@default",
    local_id: null
  });

  appendAegisNoteMarkerV265_(
    "FOLLOW_UP_PROMOTED: [" + id + "] task_id=" + created.task.id +
    " | " + String(contents.title || created.task.title || "").trim()
  );

  return {
    status: "success",
    contract: "AEGIS_FOLLOWUP_ACTION_V1",
    followup_id: id,
    lifecycle: "PROMOTED",
    task: created.task
  };
}

function aegisLocalDateFromIsoV265_(iso) {
  return Utilities.formatDate(new Date(iso), CONFIG.TIMEZONE, "yyyy-MM-dd");
}

function getAegisCalendarRangeV265_(startDate, endDate) {
  var startText = String(startDate || "").trim();
  var endText = String(endDate || "").trim();

  if (!/^\d{4}-\d{2}-\d{2}$/.test(startText) ||
      !/^\d{4}-\d{2}-\d{2}$/.test(endText)) {
    throw new Error("start_date and end_date must use YYYY-MM-DD.");
  }

  var start = new Date(startText + "T12:00:00Z");
  var end = new Date(endText + "T12:00:00Z");
  if (isNaN(start.getTime()) || isNaN(end.getTime()) || end <= start) {
    throw new Error("Invalid calendar range.");
  }

  var spanDays = Math.round((end.getTime() - start.getTime()) / 86400000);
  if (spanDays > 45) throw new Error("Calendar range exceeds 45 days.");

  // Construct local-midnight instants using the same timezone-safe strategy used
  // elsewhere in AEGIS instead of relying on the Apps Script process timezone.
  function localMidnight_(dateText) {
    var probe = new Date(dateText + "T12:00:00Z");
    var offset = Utilities.formatDate(probe, CONFIG.TIMEZONE, "XXX");
    return new Date(dateText + "T00:00:00" + offset);
  }

  var from = localMidnight_(startText);
  var until = localMidnight_(endText);
  var events = CalendarApp.getDefaultCalendar().getEvents(from, until).map(function(ev) {
    var startTime = ev.getStartTime();
    return {
      id: ev.getId(),
      title: ev.getTitle(),
      start: startTime.toISOString(),
      end: ev.getEndTime().toISOString(),
      all_day: ev.isAllDayEvent(),
      local_date: Utilities.formatDate(startTime, CONFIG.TIMEZONE, "yyyy-MM-dd"),
      local_time: ev.isAllDayEvent()
        ? "All day"
        : Utilities.formatDate(startTime, CONFIG.TIMEZONE, "h:mm a"),
      location: ev.getLocation() || "",
      description: ev.getDescription() || ""
    };
  });

  return {
    status: "success",
    contract: "AEGIS_CALENDAR_RANGE_V1",
    timezone: CONFIG.TIMEZONE,
    start_date: startText,
    end_date: endText,
    event_count: events.length,
    events: events
  };
}

function getCurrentWeatherContextV25() {
  try {
    var url = "https://api.open-meteo.com/v1/forecast?latitude=35.9954&longitude=-78.8965" +
      "&current=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
      "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&temperature_unit=fahrenheit" +
      "&wind_speed_unit=mph&timezone=America%2FNew_York&forecast_days=2";
    var response = UrlFetchApp.fetch(url, { muteHttpExceptions: true });
    if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) throw new Error("Open-Meteo HTTP " + response.getResponseCode());
    return { status: "AVAILABLE", source: "Open-Meteo", fetched_at: new Date().toISOString(), data: JSON.parse(response.getContentText()) };
  } catch (err) {
    return { status: "UNAVAILABLE", source: "Open-Meteo", error: err.message, fetched_at: new Date().toISOString() };
  }
}

function getCurrentGmailContextV25() {
  try {
    // Intentionally not unread-only. Freshness is time-bounded, not read-state-bounded.
    var threads = GmailApp.search("newer_than:7d", 0, 30);
    var items = threads.map(function(thread) {
      var messages = thread.getMessages();
      var msg = messages[messages.length - 1];
      return {
        subject: thread.getFirstMessageSubject(),
        from: msg.getFrom(),
        date: msg.getDate().toISOString(),
        message_count: messages.length,
        important: thread.isImportant(),
        starred: thread.hasStarredMessages()
      };
    }).sort(function(a, b) { return new Date(b.date) - new Date(a.date); });
    return { status: "AVAILABLE", query: "newer_than:7d", unread_only: false, items: items.slice(0, 25) };
  } catch (err) {
    return { status: "UNAVAILABLE", query: "newer_than:7d", unread_only: false, error: err.message, items: [] };
  }
}

function getCurrentIntelligenceContextV25() {
  try {
    var feed = getIntelligenceFeedV24(false);
    var cutoff = Date.now() - 72 * 60 * 60 * 1000;
    var items = (feed.items || []).filter(function(item) {
      if (!item.published) return false;
      var t = new Date(item.published).getTime();
      return isFinite(t) && t >= cutoff;
    }).slice(0, 30);
    return {
      status: feed.status || "AVAILABLE",
      updated: feed.updated || null,
      items: items,
      source_health: feed.source_health || [],
      source_errors: feed.source_errors || []
    };
  } catch (err) {
    return { status: "UNAVAILABLE", error: err.message, items: [] };
  }
}

function serializeCalendarDayV25(date) {
  return CalendarApp.getDefaultCalendar().getEventsForDay(date).map(function(ev) {
    return {
      title: ev.getTitle(),
      time: ev.isAllDayEvent() ? "All day" : Utilities.formatDate(ev.getStartTime(), CONFIG.TIMEZONE, "h:mm a z"),
      start: ev.getStartTime().toISOString(),
      end: ev.getEndTime().toISOString(),
      all_day: ev.isAllDayEvent()
    };
  });
}

function getCurrentTasksV25() {
  try {
    var taskList = Tasks.Tasks.list("@default", { showCompleted: false });
    return {
      status: "AVAILABLE",
      items: (taskList.items || []).map(function(t) {
        return { id: t.id, title: t.title || "Untitled Task", due: t.due || null };
      }),
      error: null
    };
  } catch (err) {
    return {
      status: "UNAVAILABLE",
      items: [],
      error: err.message
    };
  }
}

function getAegisRuntimeStateV25() {
  var now = new Date();
  var tomorrow = new Date(now); tomorrow.setDate(tomorrow.getDate() + 1);
  var kinetic = buildKineticToHorizonV2();
  var tasksState = getCurrentTasksV25();
  var tasks = tasksState.items || [];
  return {
    status: "success",
    briefing: getLatestHorizonBriefing(),
    health_nutrition: {
      total_calories: kinetic.calories_consumed,
      contract: "KINETIC_TO_HORIZON_V2",
      adherence_status: kinetic.adherence_status
    },
    calendar: { today: serializeCalendarDayV25(now), tomorrow: serializeCalendarDayV25(tomorrow) },
    tasks: tasks.map(function(t) {
      return { id: t.id, title: t.title, time: t.due ? Utilities.formatDate(new Date(t.due), CONFIG.TIMEZONE, "h:mm a z") : "Google Task" };
    }),
    tasks_status: {
      status: tasksState.status,
      error: tasksState.error || null
    },
    system_metadata: {
      last_updated: new Date().toISOString(),
      briefing_source: "google_doc",
      briefing_document_id: CONFIG.LATEST_HORIZON_BRIEFING_DOC_ID,
      backend_version: AEGIS_BACKEND_VERSION,
      horizon_generation: getHorizonGenerationStatus(),
      horizon_json_status: "RETIRED_HORIZON_PATH_BLOCKED"
    }
  };
}



function testAegisUxV265Contracts() {
  var results = {
    backend_version: AEGIS_BACKEND_VERSION,
    timezone: CONFIG.TIMEZONE,
    followups: getAegisFollowupsV265_(),
    rss_threshold_examples: [
      { attempted: 20, failed: 2, expected: "ready" },
      { attempted: 20, failed: 8, expected: "ready" },
      { attempted: 20, failed: 9, expected: "partial" }
    ]
  };
  Logger.log(JSON.stringify(results, null, 2));
  return results;
}

function testV25PostDeployGates() {
  var gmail = getCurrentGmailContextV25();
  var tasks = getCurrentTasksV25();
  var kinetic = buildKineticToHorizonV2();
  var sentinel = buildSentinelFinToHorizonV25();
  var result = {
    version: CONFIG.HORIZON_VERSION,
    gmail: { status: gmail.status, query: gmail.query, unread_only: gmail.unread_only, item_count: (gmail.items || []).length, error: gmail.error || null },
    tasks: { status: tasks.status, item_count: (tasks.items || []).length, error: tasks.error || null },
    kinetic: { status: kinetic.source_status || "AVAILABLE", adherence_status: kinetic.adherence_status, leaks_rows_evaluated_today: Object.prototype.hasOwnProperty.call(kinetic, "rows_evaluated_today") },
    sentinel: { status: sentinel.status, contract: sentinel.contract, activity_count: sentinel.summary ? sentinel.summary.activityCount : null, prism_consumed: sentinel.provenance ? sentinel.provenance.prism_consumed : null, error: sentinel.error || null }
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}


/* ============================================================
   AEGIS AQ-1 — AUTHENTICATED AI QUERY GATEWAY
   Read-only advisory surface. No canonical state mutation.
   ============================================================ */

function normalizeAegisAiModeV1_(mode) {
  var value = String(mode || "general").trim().toLowerCase();
  var allowed = ["general", "career", "finance", "logistics", "system"];
  return allowed.indexOf(value) >= 0 ? value : "general";
}

function clipAegisAiTextV1_(value, maxChars) {
  var text = String(value == null ? "" : value).replace(/\s+/g, " ").trim();
  return text.length > maxChars ? text.slice(0, maxChars) + "…" : text;
}

function sanitizeAegisAiHistoryV1_(history) {
  if (!Array.isArray(history)) return [];
  return history.slice(-8).map(function(item) {
    var role = String(item && item.role || "").toLowerCase();
    if (role !== "user" && role !== "assistant") role = "user";
    return {
      role: role,
      text: clipAegisAiTextV1_(item && item.text, 1800)
    };
  }).filter(function(item) { return !!item.text; });
}

function safeAegisAiSourceV1_(name, fn) {
  try {
    return { status: "AVAILABLE", source: name, data: fn() };
  } catch (err) {
    return { status: "UNAVAILABLE", source: name, error: err.message };
  }
}

function buildAegisAiContextV1_(mode) {
  var now = new Date();
  var tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);

  var context = {
    generated_at: now.toISOString(),
    timezone: CONFIG.TIMEZONE,
    mode: mode,
    mutation_policy: "READ_ONLY",
    evidence_policy: "Use only supplied bounded/current context. Do not invent missing personal facts."
  };

  if (mode === "general" || mode === "career" || mode === "logistics") {
    context.calendar = safeAegisAiSourceV1_("GOOGLE_CALENDAR", function() {
      return {
        today: serializeCalendarDayV25(now),
        tomorrow: serializeCalendarDayV25(tomorrow)
      };
    });
    context.tasks = safeAegisAiSourceV1_("GOOGLE_TASKS", function() {
      var state = getCurrentTasksV25();
      return {
        status: state.status,
        items: (state.items || []).slice(0, 20).map(function(t) {
          return { id: t.id, title: clipAegisAiTextV1_(t.title, 300), due: t.due || null };
        })
      };
    });
  }

  if (mode === "general" || mode === "career") {
    context.active_notes = safeAegisAiSourceV1_("ACTIVE_NOTE_FILTER", function() {
      var notes = getActiveNotesV25();
      return {
        filter: notes.filter,
        active_candidates: (notes.active_candidates || []).slice(-12).map(function(x) {
          return clipAegisAiTextV1_(x, 600);
        })
      };
    });
    context.spark = safeAegisAiSourceV1_("SPARK_TO_HORIZON_V2", function() {
      return buildSparkToHorizonV2();
    });
  }

  if (mode === "general") {
    context.kinetic = safeAegisAiSourceV1_("KINETIC_TO_HORIZON_V2", function() {
      return buildKineticToHorizonV2();
    });
  }

  if (mode === "finance") {
    context.sentinel_fin = safeAegisAiSourceV1_("SENTINEL_FIN_TO_HORIZON_V25", function() {
      return buildSentinelFinToHorizonV25();
    });
  }

  if (mode === "logistics") {
    context.gmail = safeAegisAiSourceV1_("GMAIL_METADATA_7D", function() {
      var gmail = getCurrentGmailContextV25();
      return {
        status: gmail.status,
        query: gmail.query,
        unread_only: gmail.unread_only,
        items: (gmail.items || []).slice(0, 12).map(function(x) {
          return {
            subject: clipAegisAiTextV1_(x.subject, 280),
            from: clipAegisAiTextV1_(x.from, 220),
            date: x.date,
            important: !!x.important,
            starred: !!x.starred
          };
        })
      };
    });
  }

  if (mode === "system") {
    context.capabilities = safeAegisAiSourceV1_("AEGIS_CAPABILITIES", function() {
      return getAegisCapabilities();
    });
    context.health = safeAegisAiSourceV1_("AEGIS_HEALTH", function() {
      return getAegisHealth();
    });
  }

  return context;
}

function getAegisAiModeInstructionV1_(mode) {
  if (mode === "career") {
    return "Act as a career advisor and technical-career mentor. Separate verified context, user-stated goals/preferences, inferences, and recommendations. Do not invent employment history, credentials, strengths, or goals that are absent from the supplied context.";
  }
  if (mode === "finance") {
    return "Act as a financial planning and analysis assistant using the bounded SENTINEL-FIN summary. Distinguish recorded financial facts from analysis and recommendations. Do not claim fiduciary status and do not execute transactions.";
  }
  if (mode === "logistics") {
    return "Act as an executive logistics assistant. Use only the supplied Calendar, Tasks, and Gmail metadata. Do not claim to have read email bodies when only metadata is provided.";
  }
  if (mode === "system") {
    return "Act as a technical operator for GEMINI-POS / AEGIS. Explain current system health and capabilities from the supplied system telemetry. Do not fabricate runtime state.";
  }
  return "Act as the authenticated GEMINI-POS assistant. Help the user reason across the supplied current Calendar, Tasks, active notes, SPARK bounded state, and KINETIC display state. Be clear when a requested fact is not present.";
}

function handleAegisAiQueryV1_(contents) {
  var question = clipAegisAiTextV1_(contents && contents.question, 4000);
  if (!question) {
    return {
      status: "error",
      code: "AI_QUERY_EMPTY",
      error: "A question is required."
    };
  }

  var mode = normalizeAegisAiModeV1_(contents && contents.mode);
  var history = sanitizeAegisAiHistoryV1_(contents && contents.history);
  var context = buildAegisAiContextV1_(mode);
  var cfg = getGeminiConfig();

  var transcript = history.length
    ? history.map(function(item) {
        return item.role.toUpperCase() + ": " + item.text;
      }).join("\n")
    : "(no prior messages in this browser session)";

  var prompt =
    "You are the AEGIS AQ-1 authenticated conversational gateway for GEMINI-POS.\n" +
    "This request is READ-ONLY. Never claim to have created, edited, deleted, sent, purchased, invested, scheduled, or mutated anything.\n" +
    "Do not use hidden memory or prior briefings as factual evidence. Use only the CURRENT_CONTEXT below plus the explicit short session transcript.\n" +
    "If the context does not support a personal fact, say that it is not available rather than guessing.\n" +
    "The transcript is short-term conversational continuity supplied by the browser; it is not durable canonical memory.\n\n" +
    "MODE: " + mode.toUpperCase() + "\n" +
    "MODE INSTRUCTION: " + getAegisAiModeInstructionV1_(mode) + "\n\n" +
    "CURRENT_CONTEXT:\n" + JSON.stringify(context) + "\n\n" +
    "SESSION_TRANSCRIPT:\n" + transcript + "\n\n" +
    "CURRENT_USER_QUESTION:\n" + question + "\n\n" +
    "Respond in clear conversational prose. Prefer actionable answers, but label uncertainty and recommendations appropriately.";

  var answer = callGemini(prompt);

  return {
    status: "success",
    contract: "AEGIS_AI_QUERY_V1",
    generated_at: new Date().toISOString(),
    request_id: Utilities.getUuid(),
    mode: mode,
    answer: answer,
    model: cfg.model,
    mutation_performed: false,
    memory: {
      type: "CLIENT_SESSION_ONLY",
      history_messages_used: history.length,
      durable_memory_written: false
    },
    context_sources: Object.keys(context).filter(function(key) {
      return context[key] && typeof context[key] === "object" && context[key].source;
    }).map(function(key) {
      return {
        key: key,
        source: context[key].source,
        status: context[key].status
      };
    })
  };
}

function testAegisAiQueryV1() {
  var result = handleAegisAiQueryV1_({
    mode: "system",
    question: "Summarize current AEGIS system health in three concise bullets.",
    history: []
  });
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}



/* ============================================================
   AQ-2 — CONVERSATIONAL CALENDAR CONTROL
   ============================================================ */



function getAegisCalendarModelV23_() {
  var props = PropertiesService.getScriptProperties();
  return String(props.getProperty("AEGIS_CALENDAR_MODEL") || "gemini-3.5-flash-lite").trim();
}

function callAegisCalendarModelV23_(prompt) {
  var cfg = getGeminiConfig();
  var model = getAegisCalendarModelV23_();
  var url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent";
  var response = UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    headers: { "x-goog-api-key": cfg.apiKey },
    payload: JSON.stringify({ contents: [{ role: "user", parts: [{ text: prompt }] }] }),
    muteHttpExceptions: true
  });
  var code = response.getResponseCode();
  var body = response.getContentText();
  if (code < 200 || code >= 300) {
    throw new Error("Gemini API HTTP " + code + " using Calendar model " + model + ": " + body);
  }
  var json = JSON.parse(body);
  var parts = json.candidates && json.candidates[0] && json.candidates[0].content && json.candidates[0].content.parts;
  if (!parts || !parts.length) throw new Error("Calendar model returned no usable content using " + model + ".");
  return parts.map(function(part) { return part.text || ""; }).join("").trim();
}

function parseAegisClockV23_(raw) {
  var m = String(raw || "").trim().match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i);
  if (!m) return null;
  var h = Number(m[1]), min = Number(m[2] || 0), ap = String(m[3] || "").toLowerCase();
  if (ap) {
    if (h < 1 || h > 12) return null;
    if (ap === "pm" && h !== 12) h += 12;
    if (ap === "am" && h === 12) h = 0;
  } else if (h > 23) return null;
  if (min < 0 || min > 59) return null;
  return { hour:h, minute:min };
}

function aegisCalendarLocalDatePartsV23_(daysFromToday) {
  var anchor = new Date(Date.now() + (Number(daysFromToday) || 0) * 86400000);
  return Utilities.formatDate(anchor, CONFIG.TIMEZONE, "yyyy-MM-dd");
}

function aegisCalendarLocalIsoV23_(dateStr, clock) {
  var probe = new Date(dateStr + "T12:00:00Z");
  var offset = Utilities.formatDate(probe, CONFIG.TIMEZONE, "XXX");
  var hh = ("0" + clock.hour).slice(-2);
  var mm = ("0" + clock.minute).slice(-2);
  return dateStr + "T" + hh + ":" + mm + ":00" + offset;
}

function extractAegisCalendarTimesV23_(question) {
  var q = String(question || ""), out = [], re = /\b(\d{1,2}(?::\d{2})?\s*(?:am|pm))\b/ig, m;
  while ((m = re.exec(q))) {
    var c = parseAegisClockV23_(m[1]);
    if (c) out.push({ raw:m[1], hour:c.hour, minute:c.minute, index:m.index });
  }
  return out;
}

function extractAegisCalendarDurationV23_(question) {
  var m = String(question || "").toLowerCase().match(/\bfor\s+(\d+)\s*(minutes?|mins?|hours?|hrs?)\b/);
  if (!m) return 60;
  var n = Number(m[1]);
  return /hour|hr/.test(m[2]) ? n * 60 : n;
}

function deterministicAegisCalendarIntentV23_(question) {
  var q = String(question || "").trim();
  var op = enforceAegisCalendarOperationV21_(q, "READ");
  var dayOffset = /\btomorrow\b/i.test(q) ? 1 : (/\btoday\b/i.test(q) ? 0 : null);
  var times = extractAegisCalendarTimesV23_(q);

  if (op === "READ" && dayOffset !== null &&
      /\b(calendar|schedule|what(?:'s| is)|anything|events?|appointments?)\b/i.test(q)) {
    var d = aegisCalendarLocalDatePartsV23_(dayOffset);
    var next = aegisCalendarLocalDatePartsV23_(dayOffset + 1);
    return {
      source:"DETERMINISTIC", operation:"READ",
      range_start:new Date(aegisCalendarLocalIsoV23_(d,{hour:0,minute:0})).toISOString(),
      range_end:new Date(aegisCalendarLocalIsoV23_(next,{hour:0,minute:0})).toISOString(),
      target_text:"", event:{}, changes:{}
    };
  }

  if (op === "CREATE" && dayOffset !== null && times.length >= 1) {
    var title = q.replace(/^\s*(add|create|schedule|book)\s+/i,"")
      .replace(/\b(today|tomorrow)\b[\s\S]*$/i,"").trim()
      .replace(/\b(on|to)\s+my\s+calendar\s*$/i,"").trim();
    if (title) {
      var dateC = aegisCalendarLocalDatePartsV23_(dayOffset);
      var start = new Date(aegisCalendarLocalIsoV23_(dateC,times[0]));
      var end = new Date(start.getTime() + extractAegisCalendarDurationV23_(q) * 60000);
      return { source:"DETERMINISTIC", operation:"CREATE", range_start:null, range_end:null, target_text:"",
        event:{title:title,start:start.toISOString(),end:end.toISOString(),all_day:false,location:"",description:""}, changes:{} };
    }
  }

  if (op === "UPDATE" && dayOffset !== null && times.length >= 2) {
    var target = q.replace(/^\s*(move|reschedule|shift|change|modify|edit|postpone|delay|push)\s+/i,"")
      .replace(/\b(today|tomorrow)\b[\s\S]*$/i,"").trim();
    var dateU = aegisCalendarLocalDatePartsV23_(dayOffset);
    return {
      source:"DETERMINISTIC", operation:"UPDATE",
      range_start:new Date(aegisCalendarLocalIsoV23_(dateU,{hour:0,minute:0})).toISOString(),
      range_end:new Date(aegisCalendarLocalIsoV23_(aegisCalendarLocalDatePartsV23_(dayOffset+1),{hour:0,minute:0})).toISOString(),
      target_text:target,
      target_start:new Date(aegisCalendarLocalIsoV23_(dateU,times[0])).toISOString(),
      event:{},
      changes:{title:null,start:new Date(aegisCalendarLocalIsoV23_(dateU,times[1])).toISOString(),end:null,location:null,description:null}
    };
  }

  if (op === "DELETE" && dayOffset !== null) {
    var targetD = q.replace(/^\s*(delete|remove|cancel)\s+/i,"")
      .replace(/\b(today|tomorrow)\b[\s\S]*$/i,"").trim();
    if (targetD) {
      var dateD = aegisCalendarLocalDatePartsV23_(dayOffset);
      return { source:"DETERMINISTIC", operation:"DELETE",
        range_start:new Date(aegisCalendarLocalIsoV23_(dateD,{hour:0,minute:0})).toISOString(),
        range_end:new Date(aegisCalendarLocalIsoV23_(aegisCalendarLocalDatePartsV23_(dayOffset+1),{hour:0,minute:0})).toISOString(),
        target_text:targetD,event:{},changes:{} };
    }
  }
  return null;
}

function renderAegisCalendarReadDirectV23_(events) {
  if (!events.length) return "No Calendar events were found for that period.";
  return events.map(function(ev) {
    if (ev.all_day) return "* **" + ev.title + "** — All day";
    return "* **" + ev.title + "** — " + Utilities.formatDate(new Date(ev.start), CONFIG.TIMEZONE, "h:mm a");
  }).join("\n");
}

function callAegisCalendarGeminiV2_(prompt) {
  var delays = [0, 1500, 3500];
  var lastErr = null;
  for (var attempt = 0; attempt < delays.length; attempt++) {
    if (delays[attempt] > 0) Utilities.sleep(delays[attempt]);
    try {
      return callAegisCalendarModelV23_(prompt);
    } catch (err) {
      lastErr = err;
      var msg = String(err && err.message || err || "");
      var transient = /Gemini API HTTP (429|503)\b/i.test(msg);
      if (!transient || attempt === delays.length - 1) throw err;
      Logger.log("AQ-2 Calendar Gemini transient failure; retry " + (attempt + 2) + "/" + delays.length + ": " + msg);
    }
  }
  throw lastErr || new Error("AQ-2 Calendar Gemini request failed.");
}

function clipAegisCalendarTextV2_(value, maxChars) {
  var text = String(value == null ? "" : value).replace(/\s+/g, " ").trim();
  return text.length > maxChars ? text.slice(0, maxChars) + "…" : text;
}

function parseAegisCalendarJsonV2_(raw) {
  var text = String(raw || "").trim()
    .replace(/^```json\s*/i, "")
    .replace(/^```\s*/i, "")
    .replace(/```\s*$/i, "")
    .trim();
  return JSON.parse(text);
}

function serializeAegisCalendarEventV2_(ev) {
  return {
    id: ev.getId(),
    title: ev.getTitle(),
    start: ev.getStartTime().toISOString(),
    end: ev.getEndTime().toISOString(),
    all_day: ev.isAllDayEvent(),
    location: ev.getLocation() || "",
    description: clipAegisCalendarTextV2_(ev.getDescription() || "", 1200)
  };
}

function resolveAegisCalendarIntentV2_(question) {
  var now = new Date();
  var prompt =
    "You are the intent parser for AEGIS AQ-2 Calendar. Current local datetime: " +
    Utilities.formatDate(now, CONFIG.TIMEZONE, "yyyy-MM-dd'T'HH:mm:ssXXX") +
    " in " + CONFIG.TIMEZONE + ".\n" +
    "Classify the user request as READ, CREATE, UPDATE, or DELETE.\n" +
    "Return ONLY JSON with this shape:\n" +
    '{"operation":"READ|CREATE|UPDATE|DELETE","range_start":"ISO|null","range_end":"ISO|null","target_text":"string","event":{"title":"string","start":"ISO|null","end":"ISO|null","all_day":false,"location":"string","description":"string"},"changes":{"title":"string|null","start":"ISO|null","end":"ISO|null","location":"string|null","description":"string|null"}}\n' +
    "Rules: READ includes schedule, availability, free time, and what-is-on-my-calendar questions. " +
    "For CREATE resolve title and time; if duration is omitted use 60 minutes. " +
    "For UPDATE/DELETE put identifying words in target_text and use range_start/range_end when the user names a date. " +
    "Do not invent location/description. All timed ISO values must include timezone offset.\n\n" +
    "USER REQUEST: " + question;
  var obj = parseAegisCalendarJsonV2_(callAegisCalendarGeminiV2_(prompt));
  var op = String(obj.operation || "READ").toUpperCase();
  if (["READ","CREATE","UPDATE","DELETE"].indexOf(op) < 0) op = "READ";
  obj.operation = op;
  return obj;
}


function enforceAegisCalendarOperationV21_(question, modelOperation) {
  var q = String(question || "").toLowerCase().replace(/\s+/g, " ").trim();
  var op = String(modelOperation || "READ").toUpperCase();

  // Destructive intent takes precedence over every other mutation class.
  if (/\b(delete|remove|cancel)\b/.test(q)) {
    return "DELETE";
  }

  // Language that clearly refers to modifying an existing event must never
  // degrade into CREATE, even if the model misclassifies the request.
  if (/\b(move|reschedule|shift|change|modify|edit|postpone|delay|push)\b/.test(q) ||
      /\b(move up|move back|bring forward)\b/.test(q)) {
    return "UPDATE";
  }

  // Explicit creation language may create only when no update/delete signal
  // was found above.
  if (/\b(add|create|schedule|book)\b/.test(q) ||
      /\bput\b.*\bon my calendar\b/.test(q)) {
    return "CREATE";
  }

  return ["READ", "CREATE", "UPDATE", "DELETE"].indexOf(op) >= 0 ? op : "READ";
}

function safeAegisCalendarRangeV2_(intent, operation) {
  var now = new Date();
  var start = intent && intent.range_start ? new Date(intent.range_start) : null;
  var end = intent && intent.range_end ? new Date(intent.range_end) : null;
  if (!start || isNaN(start.getTime())) {
    start = new Date(now);
    start.setDate(start.getDate() - (operation === "READ" ? 1 : 14));
  }
  if (!end || isNaN(end.getTime())) {
    end = new Date(now);
    end.setDate(end.getDate() + (operation === "READ" ? 30 : 120));
  }
  var maxSpan = 180 * 24 * 60 * 60 * 1000;
  if (end <= start || end - start > maxSpan) {
    end = new Date(start.getTime() + 60 * 24 * 60 * 60 * 1000);
  }
  return { start: start, end: end };
}

function listAegisCalendarEventsV2_(start, end) {
  return CalendarApp.getDefaultCalendar().getEvents(start, end)
    .slice(0, 80)
    .map(serializeAegisCalendarEventV2_);
}

function normalizeAegisCalendarMatchV2_(text) {
  return String(text || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").replace(/\s+/g, " ").trim();
}

function findAegisCalendarCandidatesV2_(events, targetText) {
  var target = normalizeAegisCalendarMatchV2_(targetText);
  if (!target) return events.slice(0, 8);
  var words = target.split(" ").filter(function(x) { return x.length > 1; });
  return events.map(function(ev) {
    var title = normalizeAegisCalendarMatchV2_(ev.title);
    var score = title === target ? 100 : (title.indexOf(target) >= 0 || target.indexOf(title) >= 0 ? 60 : 0);
    words.forEach(function(w) { if (title.indexOf(w) >= 0) score += 8; });
    return { event: ev, score: score };
  }).filter(function(x) { return x.score > 0; })
    .sort(function(a,b) { return b.score - a.score; })
    .slice(0, 8)
    .map(function(x) { return x.event; });
}

function buildAegisCalendarReadAnswerV2_(question, events, range) {
  var prompt =
    "You are AEGIS Calendar in READ-ONLY mode. Answer the user's calendar question using ONLY the supplied verified event list. " +
    "If there are no matching events, say so. Do not claim a mutation. Use concise Markdown.\n\n" +
    "TIMEZONE: " + CONFIG.TIMEZONE + "\n" +
    "RANGE: " + range.start.toISOString() + " to " + range.end.toISOString() + "\n" +
    "EVENTS: " + JSON.stringify(events) + "\n\n" +
    "QUESTION: " + question;
  return callGemini(prompt);
}

function validateAegisCalendarCreateV2_(eventObj) {
  eventObj = eventObj || {};
  var title = clipAegisCalendarTextV2_(eventObj.title, 300);
  if (!title) throw new Error("Calendar create intent is missing a title.");
  if (eventObj.all_day === true) {
    var d = new Date(eventObj.start);
    if (isNaN(d.getTime())) throw new Error("Calendar all-day create intent is missing a valid date.");
    return { title:title, start:d.toISOString(), end:null, all_day:true, location:clipAegisCalendarTextV2_(eventObj.location,500), description:clipAegisCalendarTextV2_(eventObj.description,2000) };
  }
  var start = new Date(eventObj.start), end = new Date(eventObj.end);
  if (isNaN(start.getTime()) || isNaN(end.getTime()) || end <= start) throw new Error("Calendar create intent has an invalid time range.");
  return { title:title, start:start.toISOString(), end:end.toISOString(), all_day:false, location:clipAegisCalendarTextV2_(eventObj.location,500), description:clipAegisCalendarTextV2_(eventObj.description,2000) };
}

function issueAegisCalendarConfirmationV2_(userEmail, proposal) {
  var token = Utilities.getUuid();
  var expires = new Date(Date.now() + 10 * 60 * 1000);
  var payload = {
    token: token,
    user_email: String(userEmail || "").toLowerCase(),
    issued_at: new Date().toISOString(),
    expires_at: expires.toISOString(),
    proposal: proposal
  };
  CacheService.getScriptCache().put("AEGIS_CAL_V2_" + token, JSON.stringify(payload), 600);
  return payload;
}

function handleAegisCalendarAiV2_(contents, authContext) {
  var question = clipAegisCalendarTextV2_(contents && contents.question, 4000);
  if (!question) return { status:"error", code:"CALENDAR_QUERY_EMPTY", error:"A Calendar question is required." };
  var intent = deterministicAegisCalendarIntentV23_(question);
  var parserSource = intent ? "DETERMINISTIC" : "MODEL";
  if (!intent) intent = resolveAegisCalendarIntentV2_(question);
  var op = enforceAegisCalendarOperationV21_(question, intent.operation);
  intent.operation = op;
  var range = safeAegisCalendarRangeV2_(intent, op);
  var email = authContext && authContext.user ? authContext.user.email : "";

  if (op === "READ") {
    var readEvents = listAegisCalendarEventsV2_(range.start, range.end);
    var readAnswer = parserSource === "DETERMINISTIC"
      ? renderAegisCalendarReadDirectV23_(readEvents)
      : buildAegisCalendarReadAnswerV2_(question, readEvents, range);
    return {
      status:"success", contract:"AEGIS_CALENDAR_ACTION_V2", operation:"READ",
      answer:readAnswer, mutation_performed:false, confirmation_required:false,
      event_count:readEvents.length, parser_source:parserSource,
      model_used:parserSource === "MODEL" ? getAegisCalendarModelV23_() : null
    };
  }

  var proposal;
  if (op === "CREATE") {
    proposal = { operation:"CREATE", event:validateAegisCalendarCreateV2_(intent.event) };
  } else {
    var events = listAegisCalendarEventsV2_(range.start, range.end);
    var candidates = findAegisCalendarCandidatesV2_(events, intent.target_text || (intent.event && intent.event.title));
    if (intent.target_start && candidates.length > 1) {
      var targetMs = new Date(intent.target_start).getTime();
      candidates = candidates.filter(function(ev) {
        return Math.abs(new Date(ev.start).getTime() - targetMs) < 5 * 60 * 1000;
      });
    }
    if (candidates.length !== 1) {
      return {
        status:"success", contract:"AEGIS_CALENDAR_ACTION_V2", operation:op,
        answer:candidates.length ? "I found multiple possible Calendar matches. Please identify the exact event before I make a change." : "I could not find a Calendar event matching that request. No change was made.",
        mutation_performed:false, confirmation_required:false,
        candidates:candidates
      };
    }
    if (op === "DELETE") {
      proposal = { operation:"DELETE", target:candidates[0] };
    } else {
      var c = intent.changes || {};
      var changes = {
        title: c.title == null || c.title === "" ? null : clipAegisCalendarTextV2_(c.title,300),
        start: c.start || null,
        end: c.end || null,
        location: c.location == null ? null : clipAegisCalendarTextV2_(c.location,500),
        description: c.description == null ? null : clipAegisCalendarTextV2_(c.description,2000)
      };
      if (changes.start || changes.end) {
        var ns = new Date(changes.start || candidates[0].start);
        var ne;
        if (changes.end) ne = new Date(changes.end);
        else if (changes.start) {
          var originalDuration = new Date(candidates[0].end).getTime() - new Date(candidates[0].start).getTime();
          ne = new Date(ns.getTime() + originalDuration);
        } else ne = new Date(candidates[0].end);
        if (isNaN(ns.getTime()) || isNaN(ne.getTime()) || ne <= ns) throw new Error("Calendar update resolved an invalid time range.");
        changes.start = ns.toISOString(); changes.end = ne.toISOString();
      }
      proposal = { operation:"UPDATE", target:candidates[0], changes:changes };
    }
  }

  var pending = issueAegisCalendarConfirmationV2_(email, proposal);
  return {
    status:"success", contract:"AEGIS_CALENDAR_ACTION_V2", operation:op,
    answer:"I prepared this Calendar change, but nothing has been written yet. Review the preview and confirm if it is correct.",
    mutation_performed:false, confirmation_required:true,
    confirmation_token:pending.token, expires_at:pending.expires_at, proposal:proposal,
    parser_source:parserSource, model_used:parserSource === "MODEL" ? getAegisCalendarModelV23_() : null
  };
}

/**
 * Prepares an exact Calendar CREATE proposal from trusted structured fields.
 *
 * This bypasses Gemini interpretation but retains AUTH-1 account binding,
 * bounded validation, a ten-minute one-shot token, preview, and explicit
 * calendar_confirm. It never writes to Google Calendar directly.
 */
function handleAegisCalendarPrepareV1_(contents, authContext) {
  var input = contents && contents.event ? contents.event : {};
  var exact = {
    title: input.title,
    start: input.start,
    end: input.end,
    all_day: input.all_day === true,
    location: input.location,
    description: input.description
  };

  // Interpret YYYY-MM-DD all-day input in the configured AEGIS timezone.
  if (
    exact.all_day &&
    /^\d{4}-\d{2}-\d{2}$/.test(String(exact.start || ""))
  ) {
    exact.start = Utilities.parseDate(
      String(exact.start),
      CONFIG.TIMEZONE,
      "yyyy-MM-dd"
    ).toISOString();
  }

  var proposal = {
    operation: "CREATE",
    event: validateAegisCalendarCreateV2_(exact)
  };
  var email = authContext && authContext.user ? authContext.user.email : "";
  var pending = issueAegisCalendarConfirmationV2_(email, proposal);

  return {
    status: "success",
    contract: "AEGIS_CALENDAR_ACTION_V2",
    operation: "CREATE",
    answer: "I prepared this exact Calendar event, but nothing has been written yet. Review the preview and confirm if it is correct.",
    mutation_performed: false,
    confirmation_required: true,
    confirmation_token: pending.token,
    expires_at: pending.expires_at,
    proposal: proposal,
    parser_source: "STRUCTURED",
    model_used: null
  };
}

function getAegisCalendarEventByIdV2_(id) {
  if (!id) return null;
  return CalendarApp.getDefaultCalendar().getEventById(id);
}

function confirmAegisCalendarMutationV2_(contents, authContext) {
  var token = String(contents && contents.confirmation_token || "").trim();
  if (!token) return { status:"error", code:"CALENDAR_CONFIRMATION_MISSING", error:"Confirmation token is required." };
  var cache = CacheService.getScriptCache();
  var key = "AEGIS_CAL_V2_" + token;
  var raw = cache.get(key);
  if (!raw) return { status:"error", code:"CALENDAR_CONFIRMATION_EXPIRED", error:"This Calendar preview expired or was already used. Please ask AEGIS to prepare it again." };
  var pending = JSON.parse(raw);
  var email = String(authContext && authContext.user && authContext.user.email || "").toLowerCase();
  if (!email || email !== pending.user_email) return { status:"error", code:"CALENDAR_CONFIRMATION_ACCOUNT_MISMATCH", error:"This Calendar preview belongs to a different authenticated account." };
  if (new Date(pending.expires_at).getTime() < Date.now()) { cache.remove(key); return { status:"error", code:"CALENDAR_CONFIRMATION_EXPIRED", error:"This Calendar preview expired. Please prepare it again." }; }

  // One-shot token: remove before mutation so replay cannot duplicate a write.
  cache.remove(key);
  var p = pending.proposal || {};
  var resultEvent;
  if (p.operation === "CREATE") {
    var ev = p.event;
    if (ev.all_day) {
      resultEvent = CalendarApp.getDefaultCalendar().createAllDayEvent(ev.title, new Date(ev.start), { location:ev.location || "", description:ev.description || "" });
    } else {
      resultEvent = CalendarApp.getDefaultCalendar().createEvent(ev.title, new Date(ev.start), new Date(ev.end), { location:ev.location || "", description:ev.description || "" });
    }
  } else {
    resultEvent = getAegisCalendarEventByIdV2_(p.target && p.target.id);
    if (!resultEvent) return { status:"error", code:"CALENDAR_TARGET_MISSING", error:"The target Calendar event no longer exists. No change was made." };
    if (p.operation === "DELETE") {
      var deleted = serializeAegisCalendarEventV2_(resultEvent);
      resultEvent.deleteEvent();
      return { status:"success", contract:"AEGIS_CALENDAR_ACTION_V2", operation:"DELETE", mutation_performed:true, event:deleted, answer:"Calendar event deleted." };
    }
    if (p.operation === "UPDATE") {
      var ch = p.changes || {};
      if (ch.title != null) resultEvent.setTitle(ch.title);
      if (ch.start || ch.end) resultEvent.setTime(new Date(ch.start || resultEvent.getStartTime()), new Date(ch.end || resultEvent.getEndTime()));
      if (ch.location != null) resultEvent.setLocation(ch.location);
      if (ch.description != null) resultEvent.setDescription(ch.description);
    }
  }
  return { status:"success", contract:"AEGIS_CALENDAR_ACTION_V2", operation:p.operation, mutation_performed:true, event:serializeAegisCalendarEventV2_(resultEvent), answer:"Calendar change confirmed and applied." };
}

function testAegisCalendarReadV2() {
  var result = handleAegisCalendarAiV2_({ question:"What is on my calendar tomorrow?" }, { user:{ email:"AUTH_TEST" } });
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

/* ============================================================
   AUTONOMOUS HORIZON GENERATION
   ============================================================ */

/**
 * This is the GENERATION stage.
 *
 * AEGIS triggers this function.
 * Gemini/HORIZON is responsible for generating the intelligent
 * daily itinerary/briefing.
 *
 * The result is written to the canonical Google Doc.
 *
 * AEGIS subsequently reads and processes that document.
 */
function runHorizonPipelineUnsafe() {
  var now = new Date();
  var tomorrow = new Date(now); tomorrow.setDate(tomorrow.getDate() + 1);

  var kinetic = buildKineticToHorizonV2();
  var spark = buildSparkToHorizonV2();
  var activeNotes = getActiveNotesV25();
  var sentinel = buildSentinelFinToHorizonV25();
  var weather = getCurrentWeatherContextV25();
  var gmail = getCurrentGmailContextV25();
  var intelligence = getCurrentIntelligenceContextV25();
  var tasks = getCurrentTasksV25();
  var calendarContext = { today: serializeCalendarDayV25(now), tomorrow: serializeCalendarDayV25(tomorrow) };

  // Clean-room: previous latest_horizon_briefing is intentionally never read as input.
  var sourceEnvelope = {
    generated_at: now.toISOString(),
    lifecycle_rule: "Only independently verified CURRENT items may be presented.",
    calendar: calendarContext,
    active_tasks: tasks,
    weather: weather,
    kinetic_to_horizon_v2: kinetic,
    spark_to_horizon_v2: spark,
    sentinel_fin_72h_summary: sentinel,
    gmail_tracking: gmail,
    active_notes: activeNotes,
    intelligence: intelligence
  };

  var prompt =
    "You are HORIZON V2.5, the executive briefing presentation engine.\n" +
    "Generate today's briefing for " + Utilities.formatDate(now, CONFIG.TIMEZONE, "EEEE, MMMM d, yyyy") + ".\n\n" +
    "CLEAN-ROOM RULES:\n" +
    "1. Use ONLY the source envelope below. Never use model memory, prior briefings, cached facts, plausible estimates, or historical carry-forward.\n" +
    "2. KINETIC: consume kinetic_to_horizon_v2 exactly. Do not recalculate nutrition. null=no observation; numeric 0=verified zero; NO_ENTRIES => 'No nutrition logged yet today'.\n" +
    "3. SPARK: consume spark_to_horizon_v2 only. If state_status is NOT_MATERIAL, STALE, or OMITTED, do not include a SPARK reflection/somatic card or infer cognitive/emotional state.\n" +
    "4. Notes: only active_notes.active_candidates may appear in Things to Consider. Unmarked archival notes and tombstones are forbidden.\n" +
    "5. Finance: use sentinel_fin_72h_summary only as the bounded SENTINEL-FIN presentation input. Never read finance source rows or infer PRISM state. If status is UNAVAILABLE, say so without fallback.\n" +
    "6. Gmail is time-bounded and intentionally NOT unread-only. Read status is not a relevance gate. Use only supplied metadata.\n" +
    "7. If any source is UNAVAILABLE, state that cleanly; do not substitute older or remembered information. For active_tasks, status=AVAILABLE with items=[] means no active tasks; status=UNAVAILABLE means task state is unknown and must not be rendered as zero tasks.\n" +
    "8. Personal Newspaper items must come only from supplied current intelligence items.\n\n" +
    "OUTPUT: clean Markdown using exactly these ## top-level sections:\n" +
    "## Header / Executive Orientation\n## Weather\n## Health / KINETIC\n## SENTINEL-FIN\n## Calendar\n## Active Tasks\n## Gmail / Logistics Tracking\n## Things to Consider\n## Personal Newspaper\n\n" +
    "Do not add unsupported claims simply to fill a section.\n\nSOURCE ENVELOPE:\n" + JSON.stringify(sourceEnvelope, null, 2);

  var generatedBriefing = callGemini(prompt);
  if (!generatedBriefing) throw new Error("Gemini returned an empty HORIZON briefing.");
  validateHorizonBriefing(generatedBriefing);

  var doc = DocumentApp.openById(CONFIG.LATEST_HORIZON_BRIEFING_DOC_ID);
  var body = doc.getBody();
  body.clear();
  body.setText(generatedBriefing);
  doc.saveAndClose();

  return {
    status: "success",
    version: CONFIG.HORIZON_VERSION,
    message: "HORIZON V2.5 generated from bounded current-source contracts.",
    document_id: CONFIG.LATEST_HORIZON_BRIEFING_DOC_ID,
    generated_at: new Date().toISOString(),
    contract_status: {
      kinetic: "KINETIC_TO_HORIZON_V2",
      spark: "SPARK_TO_HORIZON_V2:" + spark.state_status,
      active_notes: activeNotes.filter,
      previous_briefing_as_input: false,
      legacy_json_io: false
    }
  };
}

/* ============================================================
   CANONICAL HORIZON DOCUMENT → AEGIS JSON
   ============================================================ */

/**
 * Reads the fixed Google Doc that Gemini/HORIZON overwrites.
 *
 * Gemini owns generation.
 * The Google Doc is the canonical human-readable result.
 * AEGIS converts that result into machine-readable runtime data.
 */
function getLatestHorizonBriefing() {

  var doc =
    DocumentApp.openById(
      CONFIG
        .LATEST_HORIZON_BRIEFING_DOC_ID
    );

  var file =
    DriveApp.getFileById(
      CONFIG
        .LATEST_HORIZON_BRIEFING_DOC_ID
    );

  var body =
    doc.getBody();

  return {

    source:
      "google_docs",

    document_id:
      CONFIG
        .LATEST_HORIZON_BRIEFING_DOC_ID,

    document_title:
      doc.getName(),

    last_updated:
      file
        .getLastUpdated()
        .toISOString(),

    fetched_at:
      new Date()
        .toISOString(),

    plain_text:
      body.getText(),

    blocks:
      extractHorizonDocumentBlocks(
        body
      )
  };
}

/**
 * Convert Google Doc structure into predictable AEGIS blocks.
 */
function extractHorizonDocumentBlocks(
  body
) {

  var blocks = [];

  for (
    var i = 0;
    i < body.getNumChildren();
    i++
  ) {

    var child =
      body.getChild(i);

    var type =
      child.getType();

    /* Paragraph / Heading */

    if (
      type ===
      DocumentApp.ElementType.PARAGRAPH
    ) {

      var paragraph =
        child.asParagraph();

      var text =
        paragraph
          .getText()
          .trim();

      if (!text) {
        continue;
      }

      var heading =
        paragraph.getHeading();

      if (
        heading &&
        heading !==
          DocumentApp
            .ParagraphHeading
            .NORMAL
      ) {

        blocks.push({

          type:
            "heading",

          level:
            headingToLevel(
              heading
            ),

          text:
            text
        });

      } else {

        blocks.push({

          type:
            "paragraph",

          text:
            text
        });
      }

      continue;
    }

    /* List item */

    if (
      type ===
      DocumentApp.ElementType.LIST_ITEM
    ) {

      var listItem =
        child.asListItem();

      var listText =
        listItem
          .getText()
          .trim();

      if (!listText) {
        continue;
      }

      blocks.push({

        type:
          "list_item",

        text:
          listText,

        nesting_level:
          listItem
            .getNestingLevel(),

        glyph_type:
          String(
            listItem
              .getGlyphType()
          )
      });

      continue;
    }

    /* Table */

    if (
      type ===
      DocumentApp.ElementType.TABLE
    ) {

      var table =
        child.asTable();

      var rows = [];

      for (
        var r = 0;
        r < table.getNumRows();
        r++
      ) {

        var row =
          table.getRow(r);

        var cells = [];

        for (
          var c = 0;
          c < row.getNumCells();
          c++
        ) {

          cells.push(
            row
              .getCell(c)
              .getText()
              .trim()
          );
        }

        rows.push(
          cells
        );
      }

      blocks.push({

        type:
          "table",

        rows:
          rows
      });

      continue;
    }

    /* Horizontal rule */

    if (
      type ===
      DocumentApp
        .ElementType
        .HORIZONTAL_RULE
    ) {

      blocks.push({
        type:
          "horizontal_rule"
      });
    }
  }

  return blocks;
}

/**
 * Convert Google Docs heading type to a simple numerical level.
 */
function headingToLevel(
  heading
) {

  switch (heading) {

    case DocumentApp
      .ParagraphHeading
      .TITLE:

      return 1;

    case DocumentApp
      .ParagraphHeading
      .SUBTITLE:

      return 2;

    case DocumentApp
      .ParagraphHeading
      .HEADING1:

      return 1;

    case DocumentApp
      .ParagraphHeading
      .HEADING2:

      return 2;

    case DocumentApp
      .ParagraphHeading
      .HEADING3:

      return 3;

    case DocumentApp
      .ParagraphHeading
      .HEADING4:

      return 4;

    case DocumentApp
      .ParagraphHeading
      .HEADING5:

      return 5;

    case DocumentApp
      .ParagraphHeading
      .HEADING6:

      return 6;

    default:

      return null;
  }
}

/* ============================================================
   HORIZON JSON PRUNING
   ============================================================ */

function pruneHorizonJsonFile(itemsToMark, completedTasks) {
  Logger.log("RETIRED_HORIZON_PATH_BLOCKED: pruneHorizonJsonFile() has zero I/O in HORIZON V2.5.");
  return { status: "RETIRED_HORIZON_PATH_BLOCKED", retired_function: "pruneHorizonJsonFile", drive_io: false, timestamp: new Date().toISOString() };
}

/* ============================================================
   COMPILE AEGIS HORIZON STATE
   ============================================================ */

function refreshHorizonDataFeed() {
  Logger.log("RETIRED_HORIZON_PATH_BLOCKED: refreshHorizonDataFeed() has zero I/O in HORIZON V2.5.");
  return { status: "RETIRED_HORIZON_PATH_BLOCKED", retired_function: "refreshHorizonDataFeed", drive_io: false, timestamp: new Date().toISOString() };
}

/* ============================================================
   DAILY CALORIE TOTAL
   ============================================================ */

function getTodayCaloriesFromSheet() {

  try {

    var sheet =
      SpreadsheetApp
        .openById(
          CONFIG.CALORIES_SHEET_ID
        )
        .getActiveSheet();

    var data =
      sheet
        .getDataRange()
        .getValues();

    var total = 0;

    var todayStr =
      Utilities.formatDate(
        new Date(),
        CONFIG.TIMEZONE,
        "yyyy-MM-dd"
      );

    for (
      var i = 1;
      i < data.length;
      i++
    ) {

      var rowDate =
        data[i][0];

      if (rowDate) {

        var dStr =

          rowDate instanceof Date

            ? Utilities.formatDate(
                rowDate,
                CONFIG.TIMEZONE,
                "yyyy-MM-dd"
              )

            : String(rowDate);

        if (
          dStr === todayStr
        ) {

          // Column E = Calories
          total +=
            Number(
              data[i][4]
            ) || 0;
        }
      }
    }

    return total;

  } catch (e) {

    Logger.log(
      "Calorie total error: " +
      e.message
    );

    return 0;
  }
}

/* ============================================================
   AEGIS v2.4 LIVE INTELLIGENCE / RSS AGGREGATOR
   ============================================================ */

var AEGIS_RSS_SOURCES = [
  { name: "INDY Week", category: "local-triangle", sourceType: "Editorial", priority: 8, url: "https://indyweek.com/feed/" },
  { name: "The Assembly NC", category: "nc-policy", sourceType: "Editorial", priority: 8, url: "https://theassemblync.com/feed/" },
  { name: "WRAL Top News", category: "local-triangle", sourceType: "Editorial", priority: 9, url: "https://www.wral.com/news/rss/48/" },
  { name: "WRAL Local Triangle", category: "local-triangle", sourceType: "Editorial", priority: 10, url: "https://www.wral.com/news/local/rss/142/" },
  { name: "ABC11", category: "local-triangle", sourceType: "Editorial", priority: 8, url: "https://abc11.com/feed/" },
  { name: "CBS 17", category: "local-triangle", sourceType: "Editorial", priority: 8, url: "https://www.cbs17.com/feed/" },
  { name: "City of Durham", category: "local-triangle", sourceType: "Official", priority: 10, url: "https://www.durhamnc.gov/RSSFeed.aspx?ModID=1&CID=All-0" },
  { name: "North Carolina Health News", category: "nc-policy", sourceType: "Editorial", priority: 8, url: "https://www.northcarolinahealthnews.org/feed/" },
  { name: "Steam Platform News", category: "pc-gaming", sourceType: "Official", priority: 8, url: "https://store.steampowered.com/feeds/news.xml" },
  { name: "Factorio", category: "game-updates", sourceType: "Official", priority: 10, url: "https://store.steampowered.com/feeds/news/app/427520" },
  { name: "Street Fighter 6", category: "game-updates", sourceType: "Official", priority: 10, url: "https://store.steampowered.com/feeds/news/app/1364780" },
  { name: "Rocket League", category: "game-updates", sourceType: "Official", priority: 10, url: "https://store.steampowered.com/feeds/news/app/252950" },
  { name: "HELLDIVERS 2", category: "game-updates", sourceType: "Official", priority: 10, url: "https://store.steampowered.com/feeds/news/app/553850" },
  { name: "Overwatch Official News", category: "game-updates", sourceType: "Official", priority: 10, url: "https://news.blizzard.com/en-us/feed/overwatch" },
  { name: "Rock Paper Shotgun", category: "pc-gaming", sourceType: "Editorial", priority: 7, url: "https://www.rockpapershotgun.com/feed" },
  { name: "PC Gamer", category: "pc-gaming", sourceType: "Editorial", priority: 7, url: "https://www.pcgamer.com/rss" },
  { name: "Eurogamer", category: "pc-gaming", sourceType: "Editorial", priority: 7, url: "https://www.eurogamer.net/feed" },
  { name: "Digital Foundry", category: "pc-gaming", sourceType: "Editorial", priority: 9, url: "https://www.eurogamer.net/feed/digital-foundry" },
  { name: "GamesIndustry.biz", category: "industry", sourceType: "Editorial", priority: 8, url: "https://www.gamesindustry.biz/feed" },
  { name: "VGC", category: "industry", sourceType: "Editorial", priority: 7, url: "https://www.videogameschronicle.com/feed/" },
  { name: "IsThereAnyDeal", category: "deals", sourceType: "Aggregator", priority: 7, url: "https://isthereanydeal.com/rss/deals/" },
  { name: "r/EscapefromTarkov", category: "tarkov", sourceType: "Community", priority: 7, url: "https://www.reddit.com/r/EscapefromTarkov/.rss" },
  { name: "r/EscapeFromTarkovArena", category: "tarkov", sourceType: "Community", priority: 6, url: "https://www.reddit.com/r/EscapeFromTarkovArena/.rss" },
  { name: "Tarkov Wiki Recent Changes", category: "tarkov", sourceType: "Community", priority: 6, url: "https://escapefromtarkov.fandom.com/wiki/Special:RecentChanges?feed=rss" }
];

function getAegisRssBootstrapSources_() {
  try {
    var response = UrlFetchApp.fetch(
      "https://raw.githubusercontent.com/Cokkles/aegis-itinerary-project/main/rss-sources.json",
      { muteHttpExceptions: true, headers: { "User-Agent": "AEGIS-Dashboard/2.8" } }
    );
    if (response.getResponseCode() >= 200 && response.getResponseCode() < 300) {
      var parsed = JSON.parse(response.getContentText());
      if (parsed && Array.isArray(parsed.sources)) return parsed.sources;
    }
  } catch (err) {
    Logger.log("RSS registry fetch failed; using embedded sources: " + err.message);
  }
  return AEGIS_RSS_SOURCES;
}

function aegisRssSlugV1_(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60) || "general";
}

function defaultAegisRssCategoriesV1_() {
  return [
    { id: "local-triangle", title: "Local Triangle", headliner_eligible: true },
    { id: "nc-policy", title: "North Carolina", headliner_eligible: true },
    { id: "pc-gaming", title: "PC Gaming", headliner_eligible: true },
    { id: "game-updates", title: "Game Updates", headliner_eligible: true },
    { id: "industry", title: "Games Industry", headliner_eligible: true },
    { id: "tarkov", title: "Escape from Tarkov", headliner_eligible: false },
    { id: "deals", title: "Game Deals", headliner_eligible: false }
  ];
}

function normalizeAegisRssRegistryV1_(value) {
  var input = value && typeof value === "object" ? value : {};
  var categories = Array.isArray(input.categories)
    ? input.categories
    : defaultAegisRssCategoriesV1_();
  var categoryMap = {};

  categories.slice(0, 30).forEach(function(category) {
    var id = aegisRssSlugV1_(category.id || category.title);
    if (categoryMap[id]) return;
    categoryMap[id] = {
      id: id,
      title: String(category.title || id.replace(/-/g, " ")).trim().slice(0, 80),
      headliner_eligible: category.headliner_eligible === true
    };
  });

  var usedIds = {};
  var sources = (Array.isArray(input.sources) ? input.sources : [])
    .slice(0, 60)
    .map(function(source, index) {
      var name = String(source.name || "RSS Source").trim().slice(0, 120);
      var categoryId = aegisRssSlugV1_(source.category || "general");
      if (!categoryMap[categoryId]) {
        categoryMap[categoryId] = {
          id: categoryId,
          title: categoryId.replace(/-/g, " "),
          headliner_eligible: false
        };
      }

      var baseId = aegisRssSlugV1_(source.id || name);
      var id = baseId;
      var suffix = 2;
      while (usedIds[id]) {
        id = (baseId.slice(0, 54) + "-" + suffix++).slice(0, 60);
      }
      usedIds[id] = true;

      return {
        id: id,
        name: name,
        category: categoryId,
        sourceType: String(source.sourceType || "Editorial").trim().slice(0, 40),
        priority: Math.max(0, Math.min(10, Number(source.priority) || 0)),
        enabled: source.enabled !== false,
        url: String(source.url || "").trim().slice(0, 2000),
        note: String(source.note || "").trim().slice(0, 300),
        order: index
      };
    })
    .filter(function(source) { return source.name && source.url; });

  return {
    status: "success",
    contract: "AEGIS_RSS_REGISTRY_V1",
    updated: input.updated || new Date().toISOString(),
    categories: Object.keys(categoryMap).map(function(id) { return categoryMap[id]; }),
    sources: sources
  };
}

function getAegisRssConfigFileV1_() {
  var files = DriveApp.getFilesByName("aegis_rss_config_v1.json");
  return files.hasNext() ? files.next() : null;
}

function readAegisRssConfigV1_() {
  try {
    var file = getAegisRssConfigFileV1_();
    if (!file) return null;
    return normalizeAegisRssRegistryV1_(
      JSON.parse(file.getBlob().getDataAsString())
    );
  } catch (err) {
    Logger.log("RSS configuration read failed: " + err.message);
    return null;
  }
}

function writeAegisRssConfigV1_(registry) {
  var normalized = normalizeAegisRssRegistryV1_(registry);
  normalized.updated = new Date().toISOString();
  var text = JSON.stringify(normalized);
  var file = getAegisRssConfigFileV1_();
  if (file) file.setContent(text);
  else DriveApp.createFile("aegis_rss_config_v1.json", text, MimeType.PLAIN_TEXT);
  return normalized;
}

function getAegisRssConfigV1_() {
  var configured = readAegisRssConfigV1_();
  if (configured) return configured;
  return normalizeAegisRssRegistryV1_({
    updated: "bootstrap",
    categories: defaultAegisRssCategoriesV1_(),
    sources: getAegisRssBootstrapSources_()
  });
}

function validateAegisRssUrlV1_(value) {
  var url = String(value || "").trim();
  if (!/^https:\/\/[a-z0-9.-]+(?::\d+)?(?:[/?#]|$)/i.test(url)) {
    throw new Error("RSS URL must be an absolute HTTPS URL.");
  }
  var host = url.replace(/^https:\/\//i, "").split(/[/:?#]/)[0].toLowerCase();
  if (
    host === "localhost" ||
    /(?:^|\.)local$/.test(host) ||
    /^(127\.|10\.|192\.168\.|169\.254\.)/.test(host) ||
    /^172\.(1[6-9]|2\d|3[01])\./.test(host)
  ) {
    throw new Error("Private or loopback RSS hosts are not allowed.");
  }
  return url.slice(0, 2000);
}

function withAegisRssConfigLockV1_(callback) {
  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    return callback();
  } finally {
    lock.releaseLock();
  }
}

function upsertAegisRssSourceV1_(contents) {
  return withAegisRssConfigLockV1_(function() {
    var registry = getAegisRssConfigV1_();
    var incoming = contents && contents.source || contents || {};
    var name = String(incoming.name || "").trim();
    if (!name || name.length > 120) {
      throw new Error("RSS source name is required and must be 120 characters or fewer.");
    }

    var category = aegisRssSlugV1_(incoming.category || "general");
    if (!registry.categories.some(function(item) { return item.id === category; })) {
      throw new Error("Select an existing RSS category.");
    }

    var id = incoming.id ? aegisRssSlugV1_(incoming.id) : aegisRssSlugV1_(name);
    var existing = registry.sources.filter(function(item) { return item.id === id; })[0];
    var source = {
      id: id,
      name: name,
      category: category,
      sourceType: String(
        incoming.sourceType || (existing && existing.sourceType) || "Editorial"
      ).trim().slice(0, 40),
      priority: Math.max(0, Math.min(10, Number(incoming.priority) || 0)),
      enabled: incoming.enabled !== false,
      url: validateAegisRssUrlV1_(incoming.url),
      note: String(incoming.note || "").trim().slice(0, 300)
    };

    var index = registry.sources.map(function(item) { return item.id; }).indexOf(id);
    if (index >= 0) registry.sources[index] = source;
    else {
      if (registry.sources.length >= 60) throw new Error("RSS source limit reached.");
      registry.sources.push(source);
    }

    var saved = writeAegisRssConfigV1_(registry);
    return {
      status: "success",
      contract: saved.contract,
      operation: index >= 0 ? "UPDATE_SOURCE" : "CREATE_SOURCE",
      source: source,
      updated: saved.updated
    };
  });
}

function deleteAegisRssSourceV1_(contents) {
  if (!(
    contents &&
    (contents.confirmed === true || String(contents.confirmed).toLowerCase() === "true")
  )) {
    throw new Error("RSS source deletion requires explicit confirmation.");
  }

  return withAegisRssConfigLockV1_(function() {
    var registry = getAegisRssConfigV1_();
    var id = aegisRssSlugV1_(contents.source_id);
    var before = registry.sources.length;
    registry.sources = registry.sources.filter(function(source) {
      return source.id !== id;
    });
    if (registry.sources.length === before) throw new Error("RSS source was not found.");
    var saved = writeAegisRssConfigV1_(registry);
    return {
      status: "success",
      contract: saved.contract,
      operation: "DELETE_SOURCE",
      source_id: id,
      updated: saved.updated
    };
  });
}

function upsertAegisRssCategoryV1_(contents) {
  return withAegisRssConfigLockV1_(function() {
    var registry = getAegisRssConfigV1_();
    var incoming = contents && contents.category || contents || {};
    var title = String(incoming.title || "").trim();
    if (!title || title.length > 80) {
      throw new Error("RSS category title is required and must be 80 characters or fewer.");
    }
    var id = aegisRssSlugV1_(incoming.id || title);
    var category = {
      id: id,
      title: title,
      headliner_eligible: incoming.headliner_eligible === true
    };
    var index = registry.categories.map(function(item) { return item.id; }).indexOf(id);
    if (index >= 0) registry.categories[index] = category;
    else {
      if (registry.categories.length >= 30) throw new Error("RSS category limit reached.");
      registry.categories.push(category);
    }
    var saved = writeAegisRssConfigV1_(registry);
    return {
      status: "success",
      contract: saved.contract,
      operation: index >= 0 ? "UPDATE_CATEGORY" : "CREATE_CATEGORY",
      category: category,
      updated: saved.updated
    };
  });
}

function renameAegisRssCategoryV1_(contents) {
  return withAegisRssConfigLockV1_(function() {
    var registry = getAegisRssConfigV1_();
    var id = aegisRssSlugV1_(contents && contents.category_id);
    var title = String(contents && contents.title || "").trim();
    if (!title || title.length > 80) {
      throw new Error("RSS category title is required and must be 80 characters or fewer.");
    }
    var category = registry.categories.filter(function(item) { return item.id === id; })[0];
    if (!category) throw new Error("RSS category was not found.");
    category.title = title;
    if (typeof contents.headliner_eligible === "boolean") {
      category.headliner_eligible = contents.headliner_eligible;
    }
    var saved = writeAegisRssConfigV1_(registry);
    return {
      status: "success",
      contract: saved.contract,
      operation: "RENAME_CATEGORY",
      category: category,
      updated: saved.updated
    };
  });
}

function testAegisRssSourceV1_(contents) {
  var source = contents && contents.source || contents || {};
  var checked = {
    name: String(source.name || "RSS test").trim().slice(0, 120) || "RSS test",
    category: aegisRssSlugV1_(source.category || "general"),
    sourceType: String(source.sourceType || "Editorial").trim().slice(0, 40),
    priority: Math.max(0, Math.min(10, Number(source.priority) || 0)),
    url: validateAegisRssUrlV1_(source.url)
  };
  var response = UrlFetchApp.fetch(checked.url, {
    muteHttpExceptions: true,
    followRedirects: true,
    headers: { "User-Agent": "AEGIS-Dashboard/2.8 (+Google Apps Script)" }
  });
  var items = parseRssResponse(checked, response);
  return {
    status: "success",
    contract: "AEGIS_RSS_REGISTRY_V1",
    http: response.getResponseCode(),
    item_count: items.length,
    sample: items.slice(0, 3).map(function(item) {
      return { title: item.title, link: item.link, published: item.published };
    })
  };
}

function getAegisRssSources() {
  var registry = getAegisRssConfigV1_();
  var categoryMap = {};
  registry.categories.forEach(function(category) {
    categoryMap[category.id] = category;
  });
  return registry.sources
    .filter(function(source) { return source.enabled !== false; })
    .map(function(source) {
      var copy = Object.assign({}, source);
      copy.headlinerEligible = !!(
        categoryMap[source.category] &&
        categoryMap[source.category].headliner_eligible
      );
      return copy;
    });
}

function getIntelligenceFeed(forceRefresh) {
  var cache = CacheService.getScriptCache();
  var cacheKey = "aegis_intelligence_v23";
  if (!forceRefresh) {
    var cached = cache.get(cacheKey);
    if (cached) return JSON.parse(cached);
  }

  var all = [];
  var errors = [];
  getAegisRssSources().forEach(function(source) {
    try {
      var items = fetchRssItems(source);
      items.forEach(function(item) { all.push(item); });
    } catch (err) {
      errors.push({ source: source.name, error: err.message });
      Logger.log("RSS source error " + source.name + ": " + err.message);
    }
  });

  all.sort(function(a, b) {
    var ad = a.published ? new Date(a.published).getTime() : 0;
    var bd = b.published ? new Date(b.published).getTime() : 0;
    if (bd !== ad) return bd - ad;
    return (b.priority || 0) - (a.priority || 0);
  });

  // Conservative title-based deduplication: retain the higher-priority/newer first item.
  var seen = {};
  all = all.filter(function(item) {
    var key = normalizeIntelTitle(item.title);
    if (!key || seen[key]) return false;
    seen[key] = true;
    return true;
  }).slice(0, 120);

  var categories = {};
  all.forEach(function(item) {
    categories[item.category] = categories[item.category] || [];
    if (categories[item.category].length < 20) categories[item.category].push(item);
  });

  var result = {
    status: "success",
    updated: new Date().toISOString(),
    items: all,
    categories: categories,
    source_count: getAegisRssSources().length,
    source_errors: errors
  };

  try { cache.put(cacheKey, JSON.stringify(result), 900); } catch (cacheErr) {}
  return result;
}

function fetchRssItems(source) {
  var response = UrlFetchApp.fetch(source.url, {
    muteHttpExceptions: true,
    followRedirects: true,
    headers: { "User-Agent": "AEGIS-Dashboard/2.4 (+Google Apps Script)" }
  });
  var code = response.getResponseCode();
  if (code < 200 || code >= 300) throw new Error("HTTP " + code);
  var xml = response.getContentText();
  var root = XmlService.parse(xml).getRootElement();
  var entries = [];

  if (root.getName().toLowerCase() === "rss" || root.getChild("channel")) {
    var channel = root.getChild("channel") || root;
    entries = channel.getChildren("item").slice(0, 12).map(function(item) {
      return rssItemFromElement(item, source, false);
    });
  } else {
    var ns = root.getNamespace();
    entries = root.getChildren("entry", ns).slice(0, 12).map(function(entry) {
      return rssItemFromElement(entry, source, true, ns);
    });
  }
  return entries.filter(function(x) { return x.title && x.link; });
}

function rssItemFromElement(el, source, atom, ns) {
  function text(name) {
    var child = atom ? el.getChild(name, ns) : el.getChild(name);
    return child ? child.getText() : "";
  }
  var link = "";
  if (atom) {
    var links = el.getChildren("link", ns);
    for (var i = 0; i < links.length; i++) {
      var href = links[i].getAttribute("href");
      var rel = links[i].getAttribute("rel");
      if (href && (!rel || rel.getValue() === "alternate")) { link = href.getValue(); break; }
    }
  } else {
    link = text("link");
  }
  var published = text(atom ? "updated" : "pubDate") || text(atom ? "published" : "dc:date");
  var description = text(atom ? "summary" : "description") || text(atom ? "content" : "content:encoded");
  var iso = "";
  if (published) {
    var d = new Date(published);
    if (!isNaN(d.getTime())) iso = d.toISOString();
  }
  return {
    title: text("title").trim(),
    link: link.trim(),
    published: iso,
    description: description,
    source: source.name,
    category: source.category,
    sourceType: source.sourceType,
    priority: source.priority
  };
}

function normalizeIntelTitle(title) {
  return String(title || "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, " ")
    .replace(/\b(the|a|an|and|or|to|of|in|for|on|with)\b/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .split(" ")
    .slice(0, 10)
    .join(" ");
}

function reverseGeocodeLocation(lat, lon) {
  if (!isFinite(lat) || !isFinite(lon)) return { status: "error", error: "Invalid coordinates." };
  try {
    var url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=" +
      encodeURIComponent(lat) + "&lon=" + encodeURIComponent(lon);
    var response = UrlFetchApp.fetch(url, {
      muteHttpExceptions: true,
      headers: { "User-Agent": "AEGIS-Dashboard/2.4" }
    });
    if (response.getResponseCode() < 200 || response.getResponseCode() >= 300) {
      throw new Error("Reverse geocode HTTP " + response.getResponseCode());
    }
    var json = JSON.parse(response.getContentText());
    var a = json.address || {};
    var city = a.city || a.town || a.village || a.county || "Current location";
    var state = a.state || "";
    return { status: "success", label: city + (state ? ", " + state : ""), lat: lat, lon: lon };
  } catch (err) {
    return { status: "error", label: "Current location", error: err.message, lat: lat, lon: lon };
  }
}


/* ============================================================
   AEGIS v2.4 APPLICATION FOUNDATION / RELIABILITY
   ============================================================ */

function jsonOutput(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function getAegisCapabilities() {
  return {
    status: "success",
    backend_version: AEGIS_BACKEND_VERSION,
    
    ux_contracts: {
      calendar_range_v1: true,
      followups_v1: true,
      task_action_v1: true,
      task_crud_v1: true,
      task_workspace_v1: true,
      tasks_history_v1: true,
      task_lists_v1: true,
      rss_health_threshold_v1: true,
      rss_registry_v1: true,
      rss_headliners_v1: true,
      calendar_prepare_v1: true,
      nutrition_capture_v2: true,
      capture_reliability_v1: true,
      nutrition_capture_async_v1: true,
      nutrition_capture_status_v1: true,
      nutrition_result_cache_v1: true,
      nutrition_provider_routing_v1: true,
      nutrition_quota_diagnostics_v1: true,
      nutrition_circuit_breaker_v1: true,
      nutrition_multi_item_integrity_v1: true,
      device_session_v1: true,
      interactive_auth_background_forbidden_v1: true
    },
features: {
      horizon_generation: true,
      horizon_validation: true,
      scheduled_horizon: false,
      notifications: true,
      appointment_reminders: true,
      natural_language_calendar: true,
      ai_query_v1: true,
      calendar_ai_v2: true,
      recent_finance_72h: true,
      intelligence_v24: true,
      reverse_geocode: true,
      task_completion: true
    },
    horizon_generation: getHorizonGenerationStatus(),
    time: new Date().toISOString()
  };
}

function getAegisHealth() {
  var props = PropertiesService.getScriptProperties();
  return {
    status: "success",
    backend_version: AEGIS_BACKEND_VERSION,
    horizon: getHorizonGenerationStatus(),
    intelligence_last_refresh: props.getProperty("AEGIS_INTEL_LAST_SUCCESS") || null,
    notification_count: getServerNotifications(false).length,
    trigger_status: getInstalledAegisTriggers(),
    time: new Date().toISOString()
  };
}

function validateHorizonBriefing(text) {
  var required = [
    "header", "weather", "health", "sentinel", "calendar",
    "tasks", "gmail", "things to consider", "personal newspaper"
  ];
  var lower = String(text || "").toLowerCase();
  if (lower.length < 1200) throw new Error("HORIZON validation failed: briefing was unexpectedly short.");
  var missing = required.filter(function(k) { return lower.indexOf(k) === -1; });
  if (missing.length) throw new Error("HORIZON validation failed; missing expected sections: " + missing.join(", "));
  var h2 = String(text || "").match(/^##\s+/gm) || [];
  if (h2.length < 8) throw new Error("HORIZON validation failed: fewer than 8 top-level Markdown sections.");
  return true;
}

function runHorizonPipeline() {
  var props = PropertiesService.getScriptProperties();
  var mode = props.getProperty("AEGIS_HORIZON_RUN_MODE") || "manual";
  var started = new Date().toISOString();
  props.setProperty("AEGIS_HORIZON_LAST_ATTEMPT", started);
  props.setProperty("AEGIS_HORIZON_LAST_MODE", mode);
  try {
    var result = runHorizonPipelineUnsafe();
    props.setProperty("AEGIS_HORIZON_LAST_SUCCESS", new Date().toISOString());
    props.deleteProperty("AEGIS_HORIZON_LAST_ERROR");
    return result;
  } catch (err) {
    props.setProperty("AEGIS_HORIZON_LAST_ERROR", String(err.message || err));
    addServerNotification(
      "HORIZON generation failed",
      "The prior valid HORIZON briefing was preserved.",
      "critical",
      "horizon-generation",
      String(err.message || err),
      "horizon-failure-" + Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "yyyy-MM-dd-HH")
    );
    throw err;
  } finally {
    props.deleteProperty("AEGIS_HORIZON_RUN_MODE");
  }
}

function getHorizonGenerationStatus() {
  var p = PropertiesService.getScriptProperties();
  return {
    last_attempt: p.getProperty("AEGIS_HORIZON_LAST_ATTEMPT") || null,
    last_success: p.getProperty("AEGIS_HORIZON_LAST_SUCCESS") || null,
    last_error: p.getProperty("AEGIS_HORIZON_LAST_ERROR") || null,
    mode: p.getProperty("AEGIS_HORIZON_LAST_MODE") || null
  };
}

/* ------------------- Notifications ------------------- */

function readNotificationStore() {
  var raw = PropertiesService.getScriptProperties().getProperty("AEGIS_NOTIFICATIONS");
  if (!raw) return [];
  try { return JSON.parse(raw); } catch (e) { return []; }
}

function writeNotificationStore(items) {
  items = (items || []).slice(0, 35);
  var raw = JSON.stringify(items);
  // Script property values are limited; trim oldest if necessary.
  while (raw.length > 8500 && items.length > 5) {
    items.pop();
    raw = JSON.stringify(items);
  }
  PropertiesService.getScriptProperties().setProperty("AEGIS_NOTIFICATIONS", raw);
}

function addServerNotification(title, message, severity, type, detail, dedupeKey) {
  var items = readNotificationStore();
  var key = dedupeKey || [type, title, message].join("|");
  var existing = items.some(function(n) { return !n.acknowledged && n.key === key; });
  if (existing) return null;
  var item = {
    id: Utilities.getUuid(),
    key: key,
    title: title,
    message: message,
    severity: severity || "warning",
    type: type || "system",
    detail: detail || "",
    createdAt: new Date().toISOString(),
    acknowledged: false
  };
  items.unshift(item);
  writeNotificationStore(items);
  return item;
}

function getServerNotifications(includeAcknowledged) {
  return readNotificationStore().filter(function(n) {
    return includeAcknowledged || !n.acknowledged;
  });
}

function ackServerNotification(id) {
  var items = readNotificationStore();
  var found = false;
  items.forEach(function(n) {
    if (n.id === id) { n.acknowledged = true; n.acknowledgedAt = new Date().toISOString(); found = true; }
  });
  writeNotificationStore(items);
  return { status: found ? "success" : "not_found", notificationId: id };
}

function ensureAppointmentReminders() {
  try {
    var now = new Date();
    var horizon = new Date(now.getTime() + 75 * 60 * 1000);
    var events = CalendarApp.getDefaultCalendar().getEvents(now, horizon);
    events.forEach(function(ev) {
      if (ev.isAllDayEvent()) return;
      var mins = Math.round((ev.getStartTime().getTime() - now.getTime()) / 60000);
      if (mins < 0) return;
      var threshold = mins <= 15 ? 15 : (mins <= 60 ? 60 : null);
      if (!threshold) return;
      var key = "appointment:" + ev.getId() + ":" + threshold + ":" +
        Utilities.formatDate(ev.getStartTime(), CONFIG.TIMEZONE, "yyyyMMddHHmm");
      addServerNotification(
        "Appointment reminder",
        ev.getTitle() + " begins in about " + mins + " minute" + (mins === 1 ? "" : "s") + ".",
        mins <= 15 ? "critical" : "warning",
        "calendar-reminder",
        Utilities.formatDate(ev.getStartTime(), CONFIG.TIMEZONE, "EEEE, MMM d • h:mm a z"),
        key
      );
    });
  } catch (err) {
    Logger.log("Appointment reminder sweep failed: " + err.message);
  }
}

/* ------------------- Natural-language Calendar ------------------- */

function resolveCalendarEventText(text) {
  text = String(text || "").trim();
  if (!text) throw new Error("No calendar event text supplied.");
  var now = new Date();
  var prompt =
    "You convert natural language into one calendar event. Current local datetime is " +
    Utilities.formatDate(now, CONFIG.TIMEZONE, "yyyy-MM-dd'T'HH:mm:ssXXX") +
    " in timezone " + CONFIG.TIMEZONE + ".\n" +
    "Resolve this request: " + text + "\n\n" +
    "Return ONLY JSON with keys title,start,end,location,description. " +
    "start and end must be ISO-8601 with timezone offset. If duration is not stated, use 60 minutes. " +
    "Do not invent a location unless the user supplied one.";
  var raw = callGemini(prompt).replace(/^```json\s*/i, "").replace(/```\s*$/i, "").trim();
  var obj = JSON.parse(raw);
  if (!obj.title || !obj.start || !obj.end) throw new Error("Gemini did not resolve a complete event.");
  var start = new Date(obj.start), end = new Date(obj.end);
  if (isNaN(start.getTime()) || isNaN(end.getTime()) || end <= start) throw new Error("Resolved event contained invalid dates.");
  return {
    title: String(obj.title),
    start: start.toISOString(),
    end: end.toISOString(),
    location: String(obj.location || ""),
    description: String(obj.description || "")
  };
}

function createCalendarEventFromResolved(eventObj) {
  if (!eventObj || !eventObj.title || !eventObj.start || !eventObj.end) {
    throw new Error("Incomplete resolved event.");
  }
  var start = new Date(eventObj.start), end = new Date(eventObj.end);
  if (isNaN(start.getTime()) || isNaN(end.getTime()) || end <= start) throw new Error("Invalid event time range.");
  var event = CalendarApp.getDefaultCalendar().createEvent(
    String(eventObj.title),
    start,
    end,
    {
      location: String(eventObj.location || ""),
      description: String(eventObj.description || "")
    }
  );
  return {
    status: "success",
    event: {
      id: event.getId(),
      title: event.getTitle(),
      start: event.getStartTime().toISOString(),
      end: event.getEndTime().toISOString()
    }
  };
}

/* ------------------- SENTINEL-FIN 72 hour activity ------------------- */

/**
 * Convert a finance-sheet Date + Timestamp pair into the canonical instant.
 *
 * IMPORTANT:
 * - The ledger's Date/Timestamp columns are wall-clock values in CONFIG.TIMEZONE.
 * - Callers should prefer getDisplayValues() for those two columns so Google
 *   Sheets' internal Date timezone interpretation cannot shift the transaction.
 * - The returned Date is an absolute instant; occurredAt remains ISO-8601 UTC.
 *
 * GPOS / AEGIS / SENTINEL-FIN COMPATIBILITY BOUNDARY:
 * Do not change the public getRecentFinanceActivity() response schema here
 * without validating the GPOS helper and downstream HORIZON consumers.
 */
function combineFinanceDateTime(dateCell, timeCell) {
  function parseDateParts_(value) {
    if (value instanceof Date && !isNaN(value.getTime())) {
      // Legacy/raw-value fallback only. Normal finance ingestion supplies
      // display strings so the sheet's wall-clock date is preserved.
      return {
        year: Number(Utilities.formatDate(value, CONFIG.TIMEZONE, "yyyy")),
        month: Number(Utilities.formatDate(value, CONFIG.TIMEZONE, "M")),
        day: Number(Utilities.formatDate(value, CONFIG.TIMEZONE, "d"))
      };
    }

    var s = String(value == null ? "" : value).trim();
    if (!s) return null;

    var iso = s.match(/^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})$/);
    if (iso) {
      return { year: Number(iso[1]), month: Number(iso[2]), day: Number(iso[3]) };
    }

    var us = s.match(/^(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{2}|\d{4})$/);
    if (us) {
      var y = Number(us[3]);
      if (y < 100) y += (y >= 70 ? 1900 : 2000);
      return { year: y, month: Number(us[1]), day: Number(us[2]) };
    }

    return null;
  }

  function parseTimeParts_(value) {
    if (value instanceof Date && !isNaN(value.getTime())) {
      return {
        hour: Number(Utilities.formatDate(value, CONFIG.TIMEZONE, "H")),
        minute: Number(Utilities.formatDate(value, CONFIG.TIMEZONE, "m")),
        second: Number(Utilities.formatDate(value, CONFIG.TIMEZONE, "s"))
      };
    }

    var s = String(value == null ? "" : value).trim();
    if (!s || /^n\/?a$/i.test(s)) return { hour: 12, minute: 0, second: 0 };

    var match = s.match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(AM|PM)?$/i);
    if (!match) return null;

    var h = Number(match[1]);
    var m = Number(match[2]);
    var sec = Number(match[3] || 0);
    var ap = String(match[4] || "").toUpperCase();

    if (m > 59 || sec > 59) return null;

    if (ap) {
      if (h < 1 || h > 12) return null;
      if (ap === "PM" && h < 12) h += 12;
      if (ap === "AM" && h === 12) h = 0;
    } else if (h > 23) {
      return null;
    }

    return { hour: h, minute: m, second: sec };
  }

  function timezoneOffsetMinutes_(instant, timezone) {
    var z = Utilities.formatDate(instant, timezone, "Z"); // e.g. -0400
    var sign = z.charAt(0) === "-" ? -1 : 1;
    var hh = Number(z.slice(1, 3));
    var mm = Number(z.slice(3, 5));
    return sign * (hh * 60 + mm);
  }

  function wallClockToInstant_(parts, timezone) {
    // Treat the desired local wall-clock components as UTC first, then remove
    // the target timezone's offset. Re-evaluate once to handle DST boundaries.
    var wallAsUtcMs = Date.UTC(
      parts.year, parts.month - 1, parts.day,
      parts.hour, parts.minute, parts.second, 0
    );

    var probe = new Date(wallAsUtcMs);
    var offset1 = timezoneOffsetMinutes_(probe, timezone);
    var candidate = new Date(wallAsUtcMs - offset1 * 60000);
    var offset2 = timezoneOffsetMinutes_(candidate, timezone);

    if (offset2 !== offset1) {
      candidate = new Date(wallAsUtcMs - offset2 * 60000);
    }

    // Round-trip validation prevents silently accepting malformed/nonexistent
    // local times (notably the DST spring-forward gap).
    var expected = [
      parts.year,
      ("0" + parts.month).slice(-2),
      ("0" + parts.day).slice(-2),
      ("0" + parts.hour).slice(-2),
      ("0" + parts.minute).slice(-2),
      ("0" + parts.second).slice(-2)
    ].join("|");

    var actual = Utilities.formatDate(
      candidate,
      timezone,
      "yyyy|MM|dd|HH|mm|ss"
    );

    return actual === expected ? candidate : null;
  }

  var d = parseDateParts_(dateCell);
  if (!d) return null;

  var t = parseTimeParts_(timeCell);
  if (!t) return null;

  return wallClockToInstant_({
    year: d.year,
    month: d.month,
    day: d.day,
    hour: t.hour,
    minute: t.minute,
    second: t.second
  }, CONFIG.TIMEZONE);
}

function getRecentFinanceActivity(hours) {
  hours = Number(hours) || 72;

  var ss = SpreadsheetApp.openById(CONFIG.FINANCE_SHEET_ID);
  var sheet = ss.getSheetByName("Log") || ss.getSheets()[0];
  var range = sheet.getDataRange();

  // Raw values remain authoritative for numeric/business fields. Display
  // values are used only for Date/Timestamp so the ledger's visible local
  // wall-clock values survive Sheets/App Script timezone differences.
  var values = range.getValues();
  var displayValues = range.getDisplayValues();

  if (!values.length) {
    return {
      status: "success",
      updated: new Date().toISOString(),
      hours: hours,
      transactions: [],
      summary: {}
    };
  }

  var headers = displayValues[0].map(function(h) {
    return String(h).trim().toLowerCase();
  });

  function col(name, fallback) {
    var i = headers.indexOf(name);
    return i >= 0 ? i : fallback;
  }

  var cDate = col("date", 0),
      cTime = col("timestamp", 1),
      cVendor = col("vendor / description", 2),
      cAmount = col("amount ($)", 3),
      cCategory = col("category", 4),
      cSource = col("payment method / source", 5),
      cNotes = col("notes", 6);

  var nowMs = Date.now();
  var cutoff = nowMs - hours * 60 * 60 * 1000;
  var futureTolerance = nowMs + 5 * 60 * 1000;
  var tx = [];

  values.slice(1).forEach(function(row, idx) {
    var displayRow = displayValues[idx + 1] || [];

    var when = combineFinanceDateTime(
      displayRow[cDate],
      displayRow[cTime]
    );

    // Defensive fallback for an unusual sheet/display format. This preserves
    // legacy compatibility rather than dropping an otherwise valid row.
    if (!when) {
      when = combineFinanceDateTime(row[cDate], row[cTime]);
    }

    if (!when ||
        when.getTime() < cutoff ||
        when.getTime() > futureTolerance) {
      return;
    }

    tx.push({
      row: idx + 2,
      occurredAt: when.toISOString(),
      vendor: String(row[cVendor] || "Unknown"),
      amount: Number(row[cAmount]) || 0,
      category: String(row[cCategory] || ""),
      paymentSource: String(row[cSource] || ""),
      notes: String(row[cNotes] || "")
    });
  });

  tx.sort(function(a, b) {
    return new Date(b.occurredAt) - new Date(a.occurredAt);
  });

  var purchaseTotal = 0,
      creditTotal = 0,
      transferTotal = 0,
      pendingCount = 0;

  tx.forEach(function(t) {
    var transfer = /^transfers?\b/i.test(t.category);
    if (t.amount < 0) creditTotal += t.amount;
    else if (transfer) transferTotal += t.amount;
    else if (t.amount > 0) purchaseTotal += t.amount;
    else pendingCount++;
  });

  return {
    status: "success",
    updated: new Date().toISOString(),
    hours: hours,
    transactions: tx,
    summary: {
      purchaseTotal: Math.round(purchaseTotal * 100) / 100,
      creditTotal: Math.round(creditTotal * 100) / 100,
      transferTotal: Math.round(transferTotal * 100) / 100,
      pendingCount: pendingCount,
      activityCount: tx.length
    }
  };
}

/**
 * Regression test for the finance timestamp boundary.
 *
 * This is read-only and does not touch the ledger. It validates representative
 * Eastern Time standard/daylight offsets plus the existing noon fallback for
 * rows whose Timestamp is N/A/blank.
 */
function testFinanceTimestampV264() {
  var cases = [
    {
      date: "8/27/2026",
      time: "9:15:30 AM",
      expected: "2026-08-27T13:15:30.000Z"
    },
    {
      date: "1/15/2026",
      time: "9:15:30 AM",
      expected: "2026-01-15T14:15:30.000Z"
    },
    {
      date: "8/27/2026",
      time: "9:15:30 PM",
      expected: "2026-08-28T01:15:30.000Z"
    },
    {
      date: "8/27/2026",
      time: "N/A",
      expected: "2026-08-27T16:00:00.000Z"
    }
  ];

  var results = cases.map(function(c) {
    var actualDate = combineFinanceDateTime(c.date, c.time);
    var actual = actualDate ? actualDate.toISOString() : null;
    return {
      date: c.date,
      time: c.time,
      expected: c.expected,
      actual: actual,
      pass: actual === c.expected
    };
  });

  var passed = results.every(function(r) { return r.pass; });
  var result = {
    status: passed ? "PASS" : "FAIL",
    timezone: CONFIG.TIMEZONE,
    backend_version: AEGIS_BACKEND_VERSION,
    cases: results
  };

  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

/**
 * SENTINEL-FIN -> HORIZON bounded presentation contract.
 *
 * Ownership boundary: getRecentFinanceActivity() is treated as the internal
 * SENTINEL-FIN producer/aggregation implementation. HORIZON must consume only
 * this bounded projection and must never read FINANCE_SHEET_ID or PRISM state
 * directly.
 */
function buildSentinelFinToHorizonV25() {
  try {
    var internal = getRecentFinanceActivity(72);
    var tx = (internal.transactions || []).map(function(t) {
      return {
        occurredAt: t.occurredAt,
        vendor: t.vendor,
        amount: t.amount,
        category: t.category,
        paymentSource: t.paymentSource
      };
    });
    return {
      status: "AVAILABLE",
      contract: "SENTINEL_FIN_TO_HORIZON_V25",
      generated_at: internal.updated || new Date().toISOString(),
      period_hours: 72,
      transactions: tx,
      summary: {
        purchaseTotal: internal.summary ? internal.summary.purchaseTotal : 0,
        creditTotal: internal.summary ? internal.summary.creditTotal : 0,
        transferTotal: internal.summary ? internal.summary.transferTotal : 0,
        pendingCount: internal.summary ? internal.summary.pendingCount : 0,
        activityCount: internal.summary ? internal.summary.activityCount : tx.length
      },
      provenance: {
        authority: "SENTINEL-FIN",
        source: "Receipts & Expense Intake Log",
        prism_consumed: false
      }
    };
  } catch (err) {
    return {
      status: "UNAVAILABLE",
      contract: "SENTINEL_FIN_TO_HORIZON_V25",
      generated_at: new Date().toISOString(),
      period_hours: 72,
      transactions: [],
      summary: null,
      error: err.message,
      provenance: {
        authority: "SENTINEL-FIN",
        prism_consumed: false
      }
    };
  }
}

/* ------------------- RSS / Intelligence v2.4 ------------------- */

function getIntelligenceCacheFile() {
  var name = "aegis_intelligence_cache.json";
  var files = DriveApp.getFilesByName(name);
  return files.hasNext() ? files.next() : null;
}

function readPersistentIntelligenceCache() {
  try {
    var f = getIntelligenceCacheFile();
    if (!f) return null;
    return JSON.parse(f.getBlob().getDataAsString());
  } catch (e) { return null; }
}

function writePersistentIntelligenceCache(obj) {
  var text = JSON.stringify(obj);
  var f = getIntelligenceCacheFile();
  if (f) f.setContent(text);
  else DriveApp.createFile("aegis_intelligence_cache.json", text, MimeType.PLAIN_TEXT);
}

function parseRssResponse(source, response) {
  var code = response.getResponseCode();
  if (code < 200 || code >= 300) throw new Error("HTTP " + code);
  var xml = response.getContentText();
  var root = XmlService.parse(xml).getRootElement();
  var entries = [];
  if (root.getName().toLowerCase() === "rss" || root.getChild("channel")) {
    var channel = root.getChild("channel") || root;
    entries = channel.getChildren("item").slice(0, 12).map(function(item) {
      return rssItemFromElement(item, source, false);
    });
  } else {
    var ns = root.getNamespace();
    entries = root.getChildren("entry", ns).slice(0, 12).map(function(entry) {
      return rssItemFromElement(entry, source, true, ns);
    });
  }
  return entries.filter(function(x) { return x.title && x.link; });
}

function getIntelligenceFeedV24(forceRefresh) {
  var rssRegistry = getAegisRssConfigV1_();
  var old = readPersistentIntelligenceCache();
  if (
    !forceRefresh &&
    old &&
    old.updated &&
    old.rss_config_updated === rssRegistry.updated
  ) {
    var age = Date.now() - new Date(old.updated).getTime();
    if (age < 20 * 60 * 1000) {
      var cachedFailed = old.source_errors ? old.source_errors.length : 0;
      var cachedTotal = Number(old.source_count) || ((old.source_health || []).length) || 0;
      var cachedFailureRate = cachedTotal ? cachedFailed / cachedTotal : 0;
      old.status = (cachedFailed > 2 && cachedFailureRate > 0.40) ? "partial" : "ready";
      old.source_coverage = {
        attempted: cachedTotal,
        failed: cachedFailed,
        successful: Math.max(0, cachedTotal - cachedFailed),
        failure_rate: cachedTotal ? Math.round(cachedFailureRate * 1000) / 1000 : 0,
        partial_threshold: 0.40
      };
      old.cache = "persistent";
      return old;
    }
  }

  var sources = getAegisRssSources();
  var requests = sources.map(function(s) {
    return {
      url: s.url,
      muteHttpExceptions: true,
      followRedirects: true,
      headers: { "User-Agent": "AEGIS-Dashboard/2.4 (+Google Apps Script)" }
    };
  });

  var responses;
  try {
    responses = UrlFetchApp.fetchAll(requests);
  } catch (fatal) {
    if (old) {
      old.status = "cached";
      old.cache_error = fatal.message;
      return old;
    }
    throw fatal;
  }

  var all = [], errors = [], health = [];
  responses.forEach(function(resp, i) {
    var source = sources[i];
    try {
      var items = parseRssResponse(source, resp);
      items.forEach(function(item) { all.push(item); });
      health.push({ source: source.name, status: "ready", items: items.length, http: resp.getResponseCode() });
    } catch (err) {
      errors.push({ source: source.name, error: err.message });
      health.push({ source: source.name, status: "failed", items: 0, error: err.message, http: resp.getResponseCode() });
    }
  });

  all.sort(function(a,b) {
    var ad = a.published ? new Date(a.published).getTime() : 0;
    var bd = b.published ? new Date(b.published).getTime() : 0;
    return bd !== ad ? bd - ad : (b.priority || 0) - (a.priority || 0);
  });

  var seen = {};
  all = all.filter(function(item) {
    var key = normalizeIntelTitle(item.title);
    if (!key || seen[key]) return false;
    seen[key] = true;
    return true;
  }).slice(0, 140);

  var categories = {};
  all.forEach(function(item) {
    categories[item.category] = categories[item.category] || [];
    if (categories[item.category].length < 24) categories[item.category].push(item);
  });

  var result = {
    status: (function() {
      if (!all.length && errors.length) return "failed";
      var rate = sources.length ? errors.length / sources.length : 0;
      return (errors.length > 2 && rate > 0.40) ? "partial" : "ready";
    })(),
    updated: new Date().toISOString(),
    items: all,
    headliners: all.filter(function(item) {
      return item.headlinerEligible === true;
    }).slice(0, 8),
    categories: categories,
    category_registry: rssRegistry.categories,
    rss_config_updated: rssRegistry.updated,
    source_count: sources.length,
    source_errors: errors,
    source_health: health,
    source_coverage: {
      attempted: sources.length,
      successful: sources.length - errors.length,
      failed: errors.length,
      failure_rate: sources.length
        ? Math.round((errors.length / sources.length) * 1000) / 1000
        : 0,
      partial_threshold: 0.40,
      tolerated_failures: Math.min(errors.length, 2)
    }
  };

  if (all.length) {
    writePersistentIntelligenceCache(result);
    PropertiesService.getScriptProperties().setProperty("AEGIS_INTEL_LAST_SUCCESS", result.updated);
  } else if (old) {
    old.status = "cached";
    old.source_errors = errors;
    old.source_health = health;
    return old;
  }
  return result;
}

/* ------------------- Scheduled automation ------------------- */

function scheduledHorizonRun() {
  Logger.log("RETIRED_HORIZON_SCHEDULE_BLOCKED: HORIZON is on-demand only.");
  return { status: "RETIRED_HORIZON_SCHEDULE_BLOCKED", executed: false };
}

function scheduledIntelligenceRefresh() {
  try { getIntelligenceFeedV24(true); }
  catch (err) {
    addServerNotification("Intelligence refresh failed", "Scheduled RSS refresh failed.", "warning", "intelligence", err.message,
      "intel-scheduled-" + Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "yyyyMMddHH"));
  }
}

function scheduledNotificationSweep() {
  ensureAppointmentReminders();
}

function installAegisAutomationTriggers() {
  var managed = ["scheduledHorizonRun", "scheduledIntelligenceRefresh", "scheduledNotificationSweep"];
  ScriptApp.getProjectTriggers().forEach(function(t) {
    if (managed.indexOf(t.getHandlerFunction()) >= 0) ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger("scheduledIntelligenceRefresh").timeBased().everyHours(1).create();
  ScriptApp.newTrigger("scheduledNotificationSweep").timeBased().everyMinutes(15).create();
  return getInstalledAegisTriggers();
}

function removeRetiredHorizonTriggersV25() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function(t) {
    if (t.getHandlerFunction() === "scheduledHorizonRun") {
      ScriptApp.deleteTrigger(t);
      removed++;
    }
  });
  return { status: "success", removed_scheduled_horizon_triggers: removed, horizon_mode: "on-demand-only" };
}

function getInstalledAegisTriggers() {
  return ScriptApp.getProjectTriggers().map(function(t) {
    return { handler: t.getHandlerFunction(), source: String(t.getEventType()) };
  });
}


function testAegisCalendarOperationGuardV21() {
  var cases = [
    { q: "Add a test appointment tomorrow at 3 PM for 30 minutes.", expected: "CREATE" },
    { q: "Move the test appointment tomorrow from 3 PM to 4 PM.", expected: "UPDATE" },
    { q: "Reschedule my dentist appointment to Friday at 2 PM.", expected: "UPDATE" },
    { q: "Delete the test appointment tomorrow.", expected: "DELETE" },
    { q: "What is on my calendar tomorrow?", expected: "READ" }
  ];
  var results = cases.map(function(c) {
    var actual = enforceAegisCalendarOperationV21_(c.q, c.expected === "READ" ? "READ" : "CREATE");
    return { question: c.q, expected: c.expected, actual: actual, pass: actual === c.expected };
  });
  Logger.log(JSON.stringify(results, null, 2));
  return results;
}


function testAegisCalendarDeterministicV23() {
  var cases = [
    "What is on my calendar tomorrow?",
    "Add test appointment tomorrow at 3 PM for 30 minutes.",
    "Move the test appointment tomorrow from 3 PM to 4 PM.",
    "Delete the test appointment tomorrow."
  ];
  var results = cases.map(function(q) {
    var parsed = deterministicAegisCalendarIntentV23_(q);
    return { question:q, operation:parsed && parsed.operation, uses_model:!parsed, parsed:parsed };
  });
  Logger.log(JSON.stringify(results, null, 2));
  return results;
}

function testAegisCalendarModelConfigV23() {
  var result = {
    calendar_model:getAegisCalendarModelV23_(),
    global_model:getGeminiConfig().model,
    isolated:getAegisCalendarModelV23_() !== getGeminiConfig().model
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}
