/**
 * AEGIS shared backend 2.8.2 -- durable asynchronous nutrition capture.
 *
 * Install beside Code.gs and NutritionReliability281.gs. This module deliberately
 * depends on the validated V281 normalization, prompt, validation, header, summary,
 * and aggregation helpers; it replaces only the request lifecycle.
 *
 * The protected router must send enqueue_nutrition_capture, get_nutrition_capture_status,
 * and retry_nutrition_capture here before the legacy /calories route. Legacy
 * /calories behavior is unchanged.
 */

var AEGIS_NUTRITION_ASYNC_CONTRACT_V282 = "AEGIS_NUTRITION_CAPTURE_ASYNC_V1";
var AEGIS_NUTRITION_BACKEND_VERSION_V282 = "2.9.0";
var AEGIS_NUTRITION_QUEUE_SHEET_V282 = "_AEGIS_NUTRITION_CAPTURE_QUEUE_V1";
var AEGIS_NUTRITION_QUEUE_HANDLER_V282 = "processAegisNutritionQueueV282";
var AEGIS_NUTRITION_DATA_SHEET_PROPERTY_V282 = "AEGIS_NUTRITION_SHEET_NAME";
var AEGIS_NUTRITION_MAX_WORKER_JOBS_V282 = 3;
var AEGIS_NUTRITION_MAX_AI_ATTEMPTS_V282 = 6;
var AEGIS_NUTRITION_PROCESSING_LEASE_MS_V282 = 10 * 60 * 1000;
var AEGIS_NUTRITION_CACHE_LIMIT_V282 = 1000;
var AEGIS_NUTRITION_CACHE_TTL_MS_V282 = 30 * 24 * 60 * 60 * 1000;

var AEGIS_NUTRITION_QUEUE_HEADERS_V282 = [
  "Capture ID",
  "Status",
  "Input",
  "Fingerprint",
  "Created At ISO",
  "Updated At ISO",
  "Next Attempt ISO",
  "Attempt Count",
  "Last Error Code",
  "Last Error",
  "Result JSON",
  "Client ID",
  "Client Version",
  "Lease Started ISO",
  "Completed At ISO"
];

var AEGIS_NUTRITION_QUEUE_COLUMN_V282 = {
  CAPTURE_ID: 1,
  STATUS: 2,
  INPUT: 3,
  FINGERPRINT: 4,
  CREATED_AT: 5,
  UPDATED_AT: 6,
  NEXT_ATTEMPT: 7,
  ATTEMPTS: 8,
  ERROR_CODE: 9,
  ERROR: 10,
  RESULT_JSON: 11,
  CLIENT_ID: 12,
  CLIENT_VERSION: 13,
  LEASE_STARTED: 14,
  COMPLETED_AT: 15
};

function enqueueAegisNutritionCaptureV282_(contents) {
  var input = normalizeAegisNutritionInputV281_(contents && contents.message || "");
  var captureId;
  try {
    captureId = normalizeAegisCaptureIdV281_(
      contents && (contents.capture_id || contents.submission_id)
    );
  } catch (idError) {
    return nutritionQueueErrorV282_(
      "CAPTURE_ID_INVALID",
      String(idError && idError.message || idError),
      null,
      false
    );
  }

  if (!input) {
    return nutritionQueueErrorV282_(
      "NUTRITION_INPUT_EMPTY",
      "Please provide food details to log.",
      captureId,
      false
    );
  }

  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  ensureAegisNutritionHeadersV281_(nutritionSheet);

  var alreadyWritten = findAegisNutritionCaptureV282_(nutritionSheet, captureId);
  if (alreadyWritten) {
    return annotateAegisNutritionResponseV290_(
      upgradeConfirmedResponseV282_(alreadyWritten, true, false),
      captureId
    );
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) {
    return nutritionQueueErrorV282_(
      "NUTRITION_QUEUE_BUSY",
      "The nutrition queue is briefly busy. Retrying this capture ID is safe.",
      captureId,
      true
    );
  }

  try {
    var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
    var fingerprint = nutritionFingerprintV282_(input);
    var existing = findAegisNutritionQueueJobV282_(queueSheet, captureId);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        return nutritionQueueErrorV282_(
          "CAPTURE_ID_CONFLICT",
          "This capture ID is already reserved for different nutrition input.",
          captureId,
          false
        );
      }
      return annotateAegisNutritionResponseV290_(
        nutritionQueueJobResponseV282_(existing, true),
        captureId
      );
    }

    var cached = findConfirmedNutritionFingerprintV282_(queueSheet, fingerprint);
    var local = cached ? null : tryResolveAegisNutritionLocallyV284_(input);
    var reusable = cached && cached.result ? cached.result : local;
    var provisional = reusable || buildAegisNutritionProvisionalV290_(
      input,
      spreadsheet
    );
    provisional = validateAegisNutritionResultV282_(provisional, input);

    var row = appendAegisNutritionQueueJobV282_(
      queueSheet,
      captureId,
      input,
      fingerprint,
      contents
    );
    var response = commitAegisNutritionResultV282_(
      spreadsheet,
      nutritionSheet,
      queueSheet,
      row,
      captureId,
      provisional,
      cached !== null
    );
    registerAegisNutritionReconciliationV290_(
      spreadsheet,
      nutritionSheet,
      captureId,
      input,
      provisional
    );
    response.result = renderAegisNutritionSummaryV281_(provisional.items) +
      "\n\nRecorded as a provisional estimate; nightly KINETIC verification is pending.";
    response.nutrition_verification_status = "PENDING";
    response.terminal = true;
    return annotateAegisNutritionResponseV290_(response, captureId);
  } finally {
    lock.releaseLock();
  }
}

function handleAegisNutritionCaptureStatusV282_(contents) {
  var captureId;
  try {
    captureId = normalizeAegisCaptureIdV281_(
      contents && (contents.capture_id || contents.submission_id)
    );
  } catch (error) {
    return nutritionQueueErrorV282_(
      "CAPTURE_ID_INVALID",
      String(error && error.message || error),
      null,
      false
    );
  }

  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  var confirmed = findAegisNutritionCaptureV282_(nutritionSheet, captureId);
  if (confirmed) return upgradeConfirmedResponseV282_(confirmed, true, false);

  var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
  var job = findAegisNutritionQueueJobV282_(queueSheet, captureId);
  if (!job) {
    return nutritionQueueErrorV282_(
      "NUTRITION_CAPTURE_NOT_FOUND",
      "No server-side nutrition capture exists for this capture ID.",
      captureId,
      false
    );
  }
  return nutritionQueueJobResponseV282_(job, true);
}

function handleAegisNutritionCaptureRetryV282_(contents) {
  var captureId;
  try {
    captureId = normalizeAegisCaptureIdV281_(
      contents && (contents.capture_id || contents.submission_id)
    );
  } catch (error) {
    return nutritionQueueErrorV282_(
      "CAPTURE_ID_INVALID",
      String(error && error.message || error),
      null,
      false
    );
  }

  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  var confirmed = findAegisNutritionCaptureV282_(nutritionSheet, captureId);
  if (confirmed) return upgradeConfirmedResponseV282_(confirmed, true, false);

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) {
    return nutritionQueueErrorV282_(
      "NUTRITION_QUEUE_BUSY",
      "The nutrition queue is briefly busy. Try the same capture ID again.",
      captureId,
      true
    );
  }
  try {
    var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
    var job = findAegisNutritionQueueJobV282_(queueSheet, captureId);
    if (!job) {
      return nutritionQueueErrorV282_(
        "NUTRITION_CAPTURE_NOT_FOUND",
        "No server-side nutrition capture exists for this capture ID.",
        captureId,
        false
      );
    }
    if (job.status !== "NEEDS_REVIEW" && job.status !== "FAILED") {
      return nutritionQueueJobResponseV282_(job, true);
    }
    updateAegisNutritionQueueJobV282_(queueSheet, job.row, {
      status: "AI_PENDING",
      updatedAt: new Date(),
      nextAttemptAt: new Date(),
      errorCode: "",
      error: "",
      leaseStartedAt: ""
    });
    ensureAegisNutritionWorkerTriggerV282_();
    return nutritionQueueJobResponseV282_(
      readAegisNutritionQueueJobV282_(queueSheet, job.row),
      true
    );
  } finally {
    lock.releaseLock();
  }
}

function processAegisNutritionQueueV282() {
  var processed = 0;
  for (var i = 0; i < AEGIS_NUTRITION_MAX_WORKER_JOBS_V282; i++) {
    var job = claimNextAegisNutritionJobV282_();
    if (!job) break;
    processed++;
    processClaimedAegisNutritionJobV282_(job);
  }

  var nextDelay = getNextAegisNutritionDelayV282_();
  return { status: "PASS", processed: processed, next_delay_ms: nextDelay };
}

function claimNextAegisNutritionJobV282_() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) return null;
  try {
    var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
    var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
    if (queueSheet.getLastRow() < 2) return null;
    var now = new Date();
    var values = queueSheet.getRange(
      2,
      1,
      queueSheet.getLastRow() - 1,
      AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
    ).getValues();

    for (var i = 0; i < values.length; i++) {
      var job = queueValuesToJobV282_(values[i], i + 2);
      var leaseExpired = job.status === "PROCESSING" &&
        job.leaseStartedAt &&
        now.getTime() - job.leaseStartedAt.getTime() >= AEGIS_NUTRITION_PROCESSING_LEASE_MS_V282;
      var due = !job.nextAttemptAt || job.nextAttemptAt.getTime() <= now.getTime();
      var eligible = ["ACCEPTED", "AI_PENDING", "RETRY_SCHEDULED"].indexOf(job.status) >= 0;
      if ((!eligible || !due) && !leaseExpired) continue;

      updateAegisNutritionQueueJobV282_(queueSheet, job.row, {
        status: "PROCESSING",
        updatedAt: now,
        attempts: job.attempts + 1,
        leaseStartedAt: now,
        nextAttemptAt: ""
      });
      return {
        spreadsheetId: spreadsheet.getId(),
        queueSheetId: queueSheet.getSheetId(),
        row: job.row,
        captureId: job.captureId,
        input: job.input,
        fingerprint: job.fingerprint,
        attempts: job.attempts + 1
      };
    }
    return null;
  } finally {
    lock.releaseLock();
  }
}

function processClaimedAegisNutritionJobV282_(claim) {
  var spreadsheet = SpreadsheetApp.openById(claim.spreadsheetId);
  var queueSheet = spreadsheet.getSheets().filter(function(sheet) {
    return sheet.getSheetId() === claim.queueSheetId;
  })[0] || getAegisNutritionQueueSheetV282_(spreadsheet);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);

  try {
    var provisional = buildAegisNutritionProvisionalV290_(
      claim.input,
      spreadsheet
    );
    provisional = validateAegisNutritionResultV282_(provisional, claim.input);
    commitAegisNutritionResultV282_(
      spreadsheet,
      nutritionSheet,
      queueSheet,
      claim.row,
      claim.captureId,
      provisional,
      false
    );
    registerAegisNutritionReconciliationV290_(
      spreadsheet,
      nutritionSheet,
      claim.captureId,
      claim.input,
      provisional
    );
  } catch (error) {
    releaseAegisNutritionJobAfterFailureV282_(
      queueSheet,
      claim.row,
      claim.captureId,
      claim.attempts,
      error
    );
  }
}

function callGeminiForNutritionOnceV282_(foodText) {
  var cfg = getGeminiConfig();
  var model = getAegisNutritionModelV281_();
  var url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent";
  var response;
  try {
    response = UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      headers: { "x-goog-api-key": cfg.apiKey },
      payload: JSON.stringify({
        contents: [{
          role: "user",
          parts: [{ text: buildAegisNutritionPromptV281_(foodText) }]
        }],
        tools: [{ google_search: {} }],
        generationConfig: {
          temperature: 0.15,
          responseMimeType: "application/json"
        }
      }),
      muteHttpExceptions: true
    });
  } catch (transportError) {
    throw taggedNutritionErrorV281_(
      "GEMINI_TRANSPORT",
      String(transportError && transportError.message || transportError),
      true
    );
  }

  var code = response.getResponseCode();
  var body = response.getContentText();
  if (code === 429 || code === 503) {
    throw taggedNutritionErrorV281_(
      code === 429 ? "GEMINI_RATE_LIMITED" : "GEMINI_HIGH_VOLUME",
      "Gemini is temporarily unavailable (HTTP " + code + "). The capture remains safely queued.",
      true
    );
  }
  if (code < 200 || code >= 300) {
    throw taggedNutritionErrorV281_(
      "GEMINI_HTTP_" + code,
      "Gemini nutrition lookup failed with HTTP " + code + ". " + body.slice(0, 600),
      false
    );
  }

  var envelope;
  try {
    envelope = JSON.parse(body);
  } catch (parseEnvelopeError) {
    throw taggedNutritionErrorV281_(
      "GEMINI_ENVELOPE_INVALID",
      "Gemini returned an invalid response envelope.",
      false
    );
  }
  var parts = envelope.candidates && envelope.candidates[0] &&
    envelope.candidates[0].content && envelope.candidates[0].content.parts;
  var raw = (parts || []).map(function(part) { return part.text || ""; }).join("").trim();
  if (!raw) {
    throw taggedNutritionErrorV281_(
      "GEMINI_EMPTY_RESULT",
      "Gemini returned no nutrition result.",
      false
    );
  }
  try {
    return JSON.parse(raw.replace(/^\`\`\`json\s*/i, "").replace(/\`\`\`\s*$/i, "").trim());
  } catch (parseResultError) {
    throw taggedNutritionErrorV281_(
      "GEMINI_JSON_INVALID",
      "Gemini returned nutrition data in an invalid format.",
      false
    );
  }
}

function releaseAegisNutritionJobAfterFailureV282_(
  queueSheet,
  row,
  captureId,
  attempts,
  error
) {
  var retryable = error && error.aegisRetryable === true;
  var code = error && error.aegisCode || "NUTRITION_LOOKUP_FAILED";
  var capacityFailure = isGeminiCapacityErrorV284_(error);
  var effectiveAttempts = /_CIRCUIT_OPEN$/.test(code)
    ? Math.max(0, attempts - 1)
    : attempts;
  // Provider capacity must not turn an accepted capture into a terminal loss.
  var exhausted = !capacityFailure &&
    effectiveAttempts >= AEGIS_NUTRITION_MAX_AI_ATTEMPTS_V282;
  var delay = retryable && !exhausted
    ? nutritionRetryDelayV282_(attempts)
    : null;
  if (delay !== null && error && Number(error.retryAfterMs) > 0) {
    delay = Math.max(delay, Number(error.retryAfterMs));
  }
  var status = delay !== null
    ? "RETRY_SCHEDULED"
    : "NEEDS_REVIEW";
  var now = new Date();
  updateAegisNutritionQueueJobV282_(queueSheet, row, {
    status: status,
    updatedAt: now,
    nextAttemptAt: delay === null ? "" : new Date(now.getTime() + delay),
    errorCode: code,
    error: String(error && error.message || error).slice(0, 1000),
    attempts: effectiveAttempts,
    leaseStartedAt: ""
  });
  if (delay !== null) ensureAegisNutritionWorkerTriggerV282_();
}

function nutritionRetryDelayV282_(attemptsCompleted) {
  var delays = [
    2 * 60 * 1000,
    5 * 60 * 1000,
    15 * 60 * 1000,
    30 * 60 * 1000,
    2 * 60 * 60 * 1000,
    6 * 60 * 60 * 1000,
    12 * 60 * 60 * 1000
  ];
  return delays[Math.max(0, Math.min(delays.length - 1, attemptsCompleted - 1))];
}

function ensureAegisNutritionWorkerTriggerV282_() {
  var matches = ScriptApp.getProjectTriggers().filter(function(trigger) {
    return trigger.getHandlerFunction() === AEGIS_NUTRITION_QUEUE_HANDLER_V282;
  });
  if (!matches.length) {
    ScriptApp.newTrigger(AEGIS_NUTRITION_QUEUE_HANDLER_V282)
      .timeBased()
      .everyMinutes(1)
      .create();
    return;
  }
  for (var i = 1; i < matches.length; i++) {
    ScriptApp.deleteTrigger(matches[i]);
  }
}

function getNextAegisNutritionDelayV282_() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
  if (queueSheet.getLastRow() < 2) return null;
  var now = Date.now();
  var values = queueSheet.getRange(
    2,
    1,
    queueSheet.getLastRow() - 1,
    AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
  ).getValues();
  var delays = values.map(function(row, index) {
    var job = queueValuesToJobV282_(row, index + 2);
    if (["ACCEPTED", "AI_PENDING", "RETRY_SCHEDULED"].indexOf(job.status) < 0) {
      return null;
    }
    return job.nextAttemptAt
      ? Math.max(60 * 1000, job.nextAttemptAt.getTime() - now)
      : 60 * 1000;
  }).filter(function(delay) { return delay !== null; });
  return delays.length ? Math.min.apply(null, delays) : null;
}

function commitAegisNutritionResultV282_(
  spreadsheet,
  nutritionSheet,
  queueSheet,
  row,
  captureId,
  validated,
  cacheHit
) {
  var lock = LockService.getScriptLock();
  var ownsLock = false;
  try {
    if (!lock.hasLock()) {
      if (!lock.tryLock(15000)) {
        throw taggedNutritionErrorV281_(
          "NUTRITION_WRITE_BUSY",
          "Nutrition is briefly busy; the capture remains queued.",
          true
        );
      }
      ownsLock = true;
    }

    var existing = findAegisNutritionCaptureV282_(nutritionSheet, captureId);
    if (existing) {
      updateAegisNutritionQueueJobV282_(queueSheet, row, {
        status: "CONFIRMED",
        updatedAt: new Date(),
        completedAt: new Date(),
        resultJson: JSON.stringify({
          items: existing.items,
          confidence: existing.items[0] && existing.items[0].confidence || "LOW",
          lookup_depth: existing.items[0] && existing.items[0].lookup_depth || 5
        }),
        errorCode: "",
        error: "",
        leaseStartedAt: ""
      });
      return upgradeConfirmedResponseV282_(existing, true, cacheHit);
    }

    ensureAegisNutritionHeadersV281_(nutritionSheet);
    appendAegisNutritionRowsV282_(nutritionSheet, validated.items, captureId);
    SpreadsheetApp.flush();
    var written = findAegisNutritionCaptureV282_(nutritionSheet, captureId);
    if (!written || written.items.length !== validated.items.length) {
      var uncertainAt = new Date();
      updateAegisNutritionQueueJobV282_(queueSheet, row, {
        status: "NEEDS_REVIEW",
        updatedAt: uncertainAt,
        errorCode: "NUTRITION_WRITE_CONFIRMATION_UNKNOWN",
        error: "Nutrition rows may have been appended, but the expected output count could not be certified.",
        nextAttemptAt: "",
        leaseStartedAt: ""
      });
      return nutritionQueueJobResponseV282_(
        readAegisNutritionQueueJobV282_(queueSheet, row),
        false
      );
    }
    var now = new Date();
    updateAegisNutritionQueueJobV282_(queueSheet, row, {
      status: "CONFIRMED",
      updatedAt: now,
      completedAt: now,
      resultJson: JSON.stringify(validated),
      errorCode: "",
      error: "",
      nextAttemptAt: "",
      leaseStartedAt: ""
    });
    return confirmedNutritionResponseV282_(validated, captureId, false, cacheHit);
  } finally {
    if (ownsLock) lock.releaseLock();
  }
}

function appendAegisNutritionRowsV282_(sheet, items, captureId) {
  var now = new Date();
  var date = Utilities.formatDate(now, CONFIG.TIMEZONE, "M/d/yyyy");
  var time = Utilities.formatDate(now, CONFIG.TIMEZONE, "h:mm:ss a");
  var rows = items.map(function(item) {
    return [
      date, time, item.item, item.portion, item.calories,
      item.protein, item.carbs, item.fat, item.sodium,
      "Logged via AEGIS Nutrition Async V1",
      item.saturated_fat, item.fiber, item.sugar, item.cholesterol,
      item.source_type, item.source_url, item.confidence, item.lookup_depth,
      item.assumptions, item.conservative_adjustment,
      captureId, "CONFIRMED", AEGIS_NUTRITION_BACKEND_VERSION_V282,
      now.toISOString()
    ];
  });
  sheet.getRange(
    sheet.getLastRow() + 1,
    1,
    rows.length,
    AEGIS_NUTRITION_HEADERS_V281.length
  ).setValues(rows);
}

function confirmedNutritionResponseV282_(validated, captureId, deduplicated, cacheHit) {
  return {
    status: "success",
    accepted: true,
    terminal: true,
    server_managed: true,
    capture_status: "CONFIRMED",
    contract: AEGIS_NUTRITION_ASYNC_CONTRACT_V282,
    nutrition_contract: AEGIS_NUTRITION_CONTRACT_V281,
    reliability_contract: AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281,
    backend_version: AEGIS_NUTRITION_BACKEND_VERSION_V282,
    capture_id: captureId,
    deduplicated: deduplicated === true,
    cache_hit: cacheHit === true,
    result: renderAegisNutritionSummaryV281_(validated.items),
    items: validated.items,
    totals: sumAegisNutritionItemsV281_(validated.items),
    totalCalories: getTodayCaloriesFromSheetV282_(),
    source_policy: "PROVISIONAL_CACHE_DATABASE_ESTIMATE_THEN_NIGHTLY_KINETIC_V1",
    lookup_depth: validated.lookup_depth,
    confidence: validated.confidence
  };
}

function upgradeConfirmedResponseV282_(response, deduplicated, cacheHit) {
  response.status = "success";
  response.accepted = true;
  response.terminal = true;
  response.server_managed = true;
  response.capture_status = "CONFIRMED";
  response.contract = AEGIS_NUTRITION_ASYNC_CONTRACT_V282;
  response.nutrition_contract = AEGIS_NUTRITION_CONTRACT_V281;
  response.reliability_contract = AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281;
  response.backend_version = AEGIS_NUTRITION_BACKEND_VERSION_V282;
  response.deduplicated = deduplicated === true;
  response.cache_hit = cacheHit === true;
  return annotateAegisNutritionResponseV290_(response, response.capture_id);
}

function nutritionQueueJobResponseV282_(job, deduplicated) {
  var terminal = job.status === "CONFIRMED" ||
    job.status === "NEEDS_REVIEW" ||
    job.status === "FAILED";
  var result = null;
  if (job.resultJson) {
    try { result = JSON.parse(job.resultJson); } catch (ignored) {}
  }
  if (job.status === "CONFIRMED" && result) {
    return confirmedNutritionResponseV282_(
      result,
      job.captureId,
      deduplicated,
      false
    );
  }
  var waiting = job.status === "RETRY_SCHEDULED" &&
    /^GEMINI_/.test(job.errorCode)
    ? "Capture saved. Waiting for provider capacity; Android does not need to resubmit it."
    : job.status === "NEEDS_REVIEW"
      ? "Capture is safely stored but needs review before nutrition can be confirmed."
      : "Capture saved to the AEGIS server queue and will continue in the background.";
  return {
    status: "success",
    accepted: true,
    terminal: terminal,
    server_managed: true,
    capture_status: job.status,
    contract: AEGIS_NUTRITION_ASYNC_CONTRACT_V282,
    nutrition_contract: AEGIS_NUTRITION_CONTRACT_V281,
    reliability_contract: AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281,
    backend_version: AEGIS_NUTRITION_BACKEND_VERSION_V282,
    capture_id: job.captureId,
    deduplicated: deduplicated === true,
    result: waiting,
    retryable: job.status === "NEEDS_REVIEW",
    retry_after_ms: retryAfterForJobV282_(job),
    write_state: "NOT_STARTED",
    diagnostic_code: job.errorCode || null,
    last_error: job.error || null,
    attempt_count: job.attempts
  };
}

function nutritionQueueErrorV282_(code, message, captureId, retryable) {
  return {
    status: "error",
    accepted: false,
    terminal: !retryable,
    server_managed: false,
    capture_status: retryable ? "QUEUED" : "FAILED",
    contract: AEGIS_NUTRITION_ASYNC_CONTRACT_V282,
    nutrition_contract: AEGIS_NUTRITION_CONTRACT_V281,
    reliability_contract: AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281,
    backend_version: AEGIS_NUTRITION_BACKEND_VERSION_V282,
    code: code,
    error: message,
    retryable: retryable === true,
    write_state: "NOT_STARTED",
    capture_id: captureId || null
  };
}

function getAegisNutritionQueueSheetV282_(spreadsheet) {
  var sheet = spreadsheet.getSheetByName(AEGIS_NUTRITION_QUEUE_SHEET_V282);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(AEGIS_NUTRITION_QUEUE_SHEET_V282);
    sheet.getRange(
      1,
      1,
      1,
      AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
    ).setValues([AEGIS_NUTRITION_QUEUE_HEADERS_V282]);
    sheet.setFrozenRows(1);
    try { sheet.hideSheet(); } catch (ignored) {}
  } else {
    var existing = sheet.getRange(
      1,
      1,
      1,
      AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
    ).getDisplayValues()[0];
    for (var i = 0; i < AEGIS_NUTRITION_QUEUE_HEADERS_V282.length; i++) {
      if (String(existing[i] || "").trim() !== AEGIS_NUTRITION_QUEUE_HEADERS_V282[i]) {
        throw new Error(
          "Nutrition queue column " + (i + 1) + " does not match the 2.8.2 contract."
        );
      }
    }
  }
  return sheet;
}

function getAegisNutritionDataSheetV282_(spreadsheet) {
  var configuredName = String(
    PropertiesService.getScriptProperties()
      .getProperty(AEGIS_NUTRITION_DATA_SHEET_PROPERTY_V282) || ""
  ).trim();
  if (configuredName) {
    var configured = spreadsheet.getSheetByName(configuredName);
    if (!configured) {
      throw new Error(
        "Configured nutrition sheet '" + configuredName + "' does not exist."
      );
    }
    if (configured.getName() === AEGIS_NUTRITION_QUEUE_SHEET_V282) {
      throw new Error("The nutrition queue cannot be used as the nutrition data sheet.");
    }
    return configured;
  }

  var candidates = spreadsheet.getSheets().filter(function(sheet) {
    return sheet.getName() !== AEGIS_NUTRITION_QUEUE_SHEET_V282;
  });
  for (var i = 0; i < candidates.length; i++) {
    if (candidates[i].getLastColumn() < 5) continue;
    var headers = candidates[i].getRange(1, 1, 1, 10).getDisplayValues()[0]
      .map(function(value) { return String(value || "").trim().toLowerCase(); });
    if (
      headers[0] === "date" &&
      headers[2] === "food item" &&
      headers[4] === "calories"
    ) {
      return candidates[i];
    }
  }

  var active = spreadsheet.getActiveSheet();
  if (active && active.getName() !== AEGIS_NUTRITION_QUEUE_SHEET_V282) {
    return active;
  }
  if (candidates.length) return candidates[0];
  throw new Error("No nutrition data sheet is available.");
}

function validateAegisNutritionResultV282_(result, originalInput) {
  var validated = validateAegisNutritionResultV281_(result, originalInput);
  validated.items.forEach(function(item) {
    var primary = [item.calories, item.protein, item.carbs, item.fat];
    var hasPositivePrimary = primary.some(function(value) {
      return value !== null && Number(value) > 0;
    });
    var verifiedZero = (item.source_type === "OFFICIAL" || item.source_type === "USDA") &&
      item.calories === 0 &&
      item.source_url;
    if (!hasPositivePrimary && !verifiedZero) {
      throw taggedNutritionErrorV281_(
        "NUTRITION_ZERO_FALLBACK_REJECTED",
        "Nutrition estimation returned no positive primary nutrient values; nothing was written.",
        false
      );
    }
  });
  return validated;
}

function findAegisNutritionCaptureV282_(sheet, captureId) {
  if (!captureId || sheet.getLastRow() < 2 || sheet.getLastColumn() < 21) return null;
  var matches = sheet
    .getRange(2, 21, sheet.getLastRow() - 1, 1)
    .createTextFinder(captureId)
    .matchEntireCell(true)
    .findAll();
  if (!matches.length) return null;
  var items = matches.map(function(match) {
    var row = sheet.getRange(
      match.getRow(),
      1,
      1,
      Math.max(24, sheet.getLastColumn())
    ).getValues()[0];
    return {
      item: row[2],
      portion: row[3],
      calories: Number(row[4]) || 0,
      protein: nullableSheetNumberV281_(row[5]),
      carbs: nullableSheetNumberV281_(row[6]),
      fat: nullableSheetNumberV281_(row[7]),
      sodium: nullableSheetNumberV281_(row[8]),
      saturated_fat: nullableSheetNumberV281_(row[10]),
      fiber: nullableSheetNumberV281_(row[11]),
      sugar: nullableSheetNumberV281_(row[12]),
      cholesterol: nullableSheetNumberV281_(row[13]),
      source_type: row[14],
      source_url: row[15],
      confidence: row[16],
      lookup_depth: Number(row[17]) || null,
      assumptions: row[18],
      conservative_adjustment: row[19] === true
    };
  });
  var firstRow = sheet.getRange(
    matches[0].getRow(),
    1,
    1,
    Math.max(24, sheet.getLastColumn())
  ).getValues()[0];
  return {
    status: "success",
    capture_status: "CONFIRMED",
    backend_version: String(firstRow[22] || AEGIS_NUTRITION_BACKEND_VERSION_V282),
    capture_id: captureId,
    result: "Nutrition capture is confirmed; no duplicate row was written.",
    items: items,
    totals: sumAegisNutritionItemsV281_(items),
    totalCalories: getTodayCaloriesFromSheetV282_()
  };
}

function getTodayCaloriesFromSheetV282_() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var sheet = getAegisNutritionDataSheetV282_(spreadsheet);
  if (sheet.getLastRow() < 2) return 0;
  var today = Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "M/d/yyyy");
  var rows = sheet.getRange(2, 1, sheet.getLastRow() - 1, 5).getDisplayValues();
  return Math.round(rows.reduce(function(total, row) {
    return String(row[0]).trim() === today
      ? total + (Number(String(row[4]).replace(/,/g, "")) || 0)
      : total;
  }, 0) * 10) / 10;
}

function appendAegisNutritionQueueJobV282_(
  queueSheet,
  captureId,
  input,
  fingerprint,
  contents
) {
  var now = new Date();
  var row = queueSheet.getLastRow() + 1;
  queueSheet.getRange(
    row,
    1,
    1,
    AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
  ).setValues([[
    captureId,
    "ACCEPTED",
    input,
    fingerprint,
    now.toISOString(),
    now.toISOString(),
    now.toISOString(),
    0,
    "",
    "",
    "",
    String(contents && contents.client_id || "").slice(0, 120),
    String(contents && contents.client_version || "").slice(0, 80),
    "",
    ""
  ]]);
  return row;
}

function findAegisNutritionQueueJobV282_(queueSheet, captureId) {
  if (queueSheet.getLastRow() < 2) return null;
  var finder = queueSheet
    .getRange(
      2,
      AEGIS_NUTRITION_QUEUE_COLUMN_V282.CAPTURE_ID,
      queueSheet.getLastRow() - 1,
      1
    )
    .createTextFinder(captureId)
    .matchEntireCell(true)
    .findNext();
  return finder
    ? readAegisNutritionQueueJobV282_(queueSheet, finder.getRow())
    : null;
}

function readAegisNutritionQueueJobV282_(queueSheet, row) {
  var values = queueSheet.getRange(
    row,
    1,
    1,
    AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
  ).getValues()[0];
  return queueValuesToJobV282_(values, row);
}

function queueValuesToJobV282_(values, row) {
  return {
    row: row,
    captureId: String(values[0] || ""),
    status: String(values[1] || "FAILED"),
    input: String(values[2] || ""),
    fingerprint: String(values[3] || ""),
    createdAt: asNutritionDateV282_(values[4]),
    updatedAt: asNutritionDateV282_(values[5]),
    nextAttemptAt: asNutritionDateV282_(values[6]),
    attempts: Math.max(0, Number(values[7]) || 0),
    errorCode: String(values[8] || ""),
    error: String(values[9] || ""),
    resultJson: String(values[10] || ""),
    clientId: String(values[11] || ""),
    clientVersion: String(values[12] || ""),
    leaseStartedAt: asNutritionDateV282_(values[13]),
    completedAt: asNutritionDateV282_(values[14])
  };
}

function updateAegisNutritionQueueJobV282_(queueSheet, row, changes) {
  var current = queueSheet.getRange(
    row,
    1,
    1,
    AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
  ).getValues()[0];
  if (Object.prototype.hasOwnProperty.call(changes, "status")) current[1] = changes.status;
  if (Object.prototype.hasOwnProperty.call(changes, "updatedAt")) current[5] = dateCellV282_(changes.updatedAt);
  if (Object.prototype.hasOwnProperty.call(changes, "nextAttemptAt")) current[6] = dateCellV282_(changes.nextAttemptAt);
  if (Object.prototype.hasOwnProperty.call(changes, "attempts")) current[7] = changes.attempts;
  if (Object.prototype.hasOwnProperty.call(changes, "errorCode")) current[8] = changes.errorCode;
  if (Object.prototype.hasOwnProperty.call(changes, "error")) current[9] = changes.error;
  if (Object.prototype.hasOwnProperty.call(changes, "resultJson")) current[10] = changes.resultJson;
  if (Object.prototype.hasOwnProperty.call(changes, "leaseStartedAt")) current[13] = dateCellV282_(changes.leaseStartedAt);
  if (Object.prototype.hasOwnProperty.call(changes, "completedAt")) current[14] = dateCellV282_(changes.completedAt);
  queueSheet.getRange(
    row,
    1,
    1,
    AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
  ).setValues([current]);
}

function findConfirmedNutritionFingerprintV282_(queueSheet, fingerprint) {
  var lastRow = queueSheet.getLastRow();
  if (lastRow < 2) return null;
  var startRow = Math.max(2, lastRow - AEGIS_NUTRITION_CACHE_LIMIT_V282 + 1);
  var values = queueSheet.getRange(
    startRow,
    1,
    lastRow - startRow + 1,
    AEGIS_NUTRITION_QUEUE_HEADERS_V282.length
  ).getValues();
  for (var i = values.length - 1; i >= 0; i--) {
    var job = queueValuesToJobV282_(values[i], startRow + i);
    if (
      job.status === "CONFIRMED" &&
      job.fingerprint === fingerprint &&
      job.resultJson &&
      (job.completedAt || job.updatedAt) &&
      Date.now() - (job.completedAt || job.updatedAt).getTime() <= AEGIS_NUTRITION_CACHE_TTL_MS_V282
    ) {
      try {
        return { job: job, result: JSON.parse(job.resultJson) };
      } catch (ignored) {}
    }
  }
  return null;
}

function nutritionFingerprintV282_(input) {
  var normalized = String(input || "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  var digest = Utilities.computeDigest(
    Utilities.DigestAlgorithm.SHA_256,
    normalized,
    Utilities.Charset.UTF_8
  );
  return digest.map(function(value) {
    var byte = value < 0 ? value + 256 : value;
    return ("0" + byte.toString(16)).slice(-2);
  }).join("");
}

function tryDeterministicNutritionV282_(input) {
  var pieces = String(input || "")
    .split(/[\n,;|]+/)
    .map(function(value) { return value.trim(); })
    .filter(function(value) { return value; });
  if (!pieces.length || pieces.length > 12) return null;
  var items = [];
  for (var i = 0; i < pieces.length; i++) {
    var item = deterministicNutritionItemV282_(pieces[i]);
    if (!item) return null;
    items.push(item);
  }
  return {
    items: items,
    confidence: "MEDIUM",
    lookup_depth: 4
  };
}

function deterministicNutritionItemV282_(text) {
  var normalized = String(text || "").toLowerCase().trim();
  var numberMatch = normalized.match(/\b(\d+)\b/);
  var explicitCount = numberMatch ? Math.max(1, Math.min(12, Number(numberMatch[1]))) : null;
  var source = "https://fdc.nal.usda.gov/";
  var item = null;

  if (/\beggs?\b/.test(normalized)) {
    var eggs = explicitCount || 2;
    item = deterministicScaleV282_(
      "Large egg",
      eggs + (eggs === 1 ? " egg" : " eggs"),
      eggs,
      [72, 6.3, 0.4, 4.8, 1.6, 0, 0.2, 71, 186],
      explicitCount ? "" : "Assumed two large eggs."
    );
  } else if (/\btoast\b/.test(normalized)) {
    var slices = explicitCount || 2;
    item = deterministicScaleV282_(
      "Toast",
      slices + (slices === 1 ? " slice" : " slices"),
      slices,
      [100, 4, 18, 1.5, 0.3, 1.5, 2, 170, 0],
      explicitCount ? "" : "Assumed two standard slices without toppings."
    );
  } else if (/\b(black )?coffee\b/.test(normalized) && !/latte|cream|milk|sugar|sweet/.test(normalized)) {
    item = deterministicScaleV282_(
      "Black coffee",
      "12 fl oz",
      1,
      [5, 0.3, 0, 0, 0, 0, 0, 10, 0],
      "Assumed unsweetened black coffee."
    );
  } else if (/\bbananas?\b/.test(normalized)) {
    var bananas = explicitCount || 1;
    item = deterministicScaleV282_(
      "Medium banana",
      bananas + (bananas === 1 ? " banana" : " bananas"),
      bananas,
      [105, 1.3, 27, 0.4, 0.1, 3.1, 14.4, 1, 0],
      explicitCount ? "" : "Assumed one medium banana."
    );
  }

  if (!item) return null;
  item.source_type = "USDA";
  item.source_url = source;
  item.confidence = "MEDIUM";
  item.lookup_depth = 4;
  item.conservative_adjustment = true;
  return item;
}

function deterministicScaleV282_(
  name,
  portion,
  count,
  nutrients,
  assumptions
) {
  function scaled(index) {
    return Math.round(Number(nutrients[index]) * count * 10) / 10;
  }
  return {
    item: name,
    portion: portion,
    calories: scaled(0),
    protein: scaled(1),
    carbs: scaled(2),
    fat: scaled(3),
    saturated_fat: scaled(4),
    fiber: scaled(5),
    sugar: scaled(6),
    sodium: scaled(7),
    cholesterol: scaled(8),
    assumptions: assumptions,
    source_type: "USDA",
    source_url: "https://fdc.nal.usda.gov/",
    confidence: "MEDIUM",
    lookup_depth: 4,
    conservative_adjustment: true
  };
}

function retryAfterForJobV282_(job) {
  if (!job.nextAttemptAt) return null;
  return Math.max(0, job.nextAttemptAt.getTime() - Date.now());
}

function asNutritionDateV282_(value) {
  if (value instanceof Date && !isNaN(value.getTime())) return value;
  var text = String(value || "").trim();
  if (!text) return null;
  var parsed = new Date(text);
  return isNaN(parsed.getTime()) ? null : parsed;
}

function dateCellV282_(value) {
  if (value === "" || value === null || typeof value === "undefined") return "";
  var date = asNutritionDateV282_(value);
  return date ? date.toISOString() : "";
}

function installAegisNutritionQueueV282() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  ensureAegisNutritionHeadersV281_(nutritionSheet);
  var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
  PropertiesService.getScriptProperties().setProperty(
    AEGIS_NUTRITION_DATA_SHEET_PROPERTY_V282,
    nutritionSheet.getName()
  );
  ensureAegisNutritionWorkerTriggerV282_();
  var result = {
    status: "PASS",
    backend_version: AEGIS_NUTRITION_BACKEND_VERSION_V282,
    contract: AEGIS_NUTRITION_ASYNC_CONTRACT_V282,
    nutrition_sheet: nutritionSheet.getName(),
    queue_sheet: queueSheet.getName()
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function removeAegisNutritionQueueTriggerV282() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function(trigger) {
    if (trigger.getHandlerFunction() === AEGIS_NUTRITION_QUEUE_HANDLER_V282) {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  var result = { status: "PASS", removed_triggers: removed };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function testAegisNutritionQueueV282() {
  var fingerprintA = nutritionFingerprintV282_("Eggs, toast, coffee");
  var fingerprintB = nutritionFingerprintV282_(" eggs | toast | coffee ");
  if (fingerprintA !== fingerprintB) {
    throw new Error("Normalized nutrition fingerprint is not stable.");
  }
  var deterministic = validateAegisNutritionResultV282_(
    tryDeterministicNutritionV282_("eggs, toast, coffee"),
    "eggs, toast, coffee"
  );
  if (
    !deterministic ||
    deterministic.items.length !== 3 ||
    deterministic.items.some(function(item) {
      return !isFinite(item.calories) || item.calories <= 0;
    })
  ) {
    throw new Error("Deterministic nutrition fallback regression failed.");
  }
  var zeroRejected = false;
  try {
    validateAegisNutritionResultV282_({
      items: [{
        item: "Unknown meal",
        portion: "1 serving",
        calories: 0,
        protein: 0,
        carbs: 0,
        fat: 0,
        source_type: "MODEL_ESTIMATE",
        source_url: "",
        confidence: "LOW",
        lookup_depth: 5
      }]
    }, "Unknown meal");
  } catch (expected) {
    zeroRejected = expected && expected.aegisCode === "NUTRITION_ZERO_FALLBACK_REJECTED";
  }
  if (!zeroRejected) throw new Error("Zero-macro fallback was not rejected.");
  var delays = [1, 2, 3, 4, 5, 6].map(nutritionRetryDelayV282_);
  for (var i = 1; i < delays.length; i++) {
    if (delays[i] <= delays[i - 1]) {
      throw new Error("Nutrition retry delays must increase.");
    }
  }
  var result = {
    status: "PASS",
    contract: AEGIS_NUTRITION_ASYNC_CONTRACT_V282,
    deterministic_items: deterministic.items.length,
    retry_delays_ms: delays
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function testAegisNutritionIdempotencyV282() {
  var captureId = "V282-TEST-" + Utilities.getUuid();
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
  var payload = {
    message: "/calories 1 egg",
    capture_id: captureId,
    submission_id: captureId,
    client_id: "AEGIS_BACKEND_TEST",
    client_version: AEGIS_NUTRITION_BACKEND_VERSION_V282
  };
  try {
    var first = enqueueAegisNutritionCaptureV282_(payload);
    var second = enqueueAegisNutritionCaptureV282_(payload);
    var status = handleAegisNutritionCaptureStatusV282_(payload);
    var matches = nutritionSheet.getLastRow() < 2 ? [] : nutritionSheet
      .getRange(2, 21, nutritionSheet.getLastRow() - 1, 1)
      .createTextFinder(captureId)
      .matchEntireCell(true)
      .findAll();
    if (
      first.capture_status !== "CONFIRMED" ||
      second.capture_status !== "CONFIRMED" ||
      status.capture_status !== "CONFIRMED" ||
      second.deduplicated !== true ||
      matches.length !== 1
    ) {
      throw new Error("2.8.2 idempotency verification failed for " + captureId + ".");
    }
    var result = {
      status: "PASS",
      capture_id: captureId,
      capture_status: status.capture_status,
      duplicate_rows_written: 0,
      deduplicated_response: second.deduplicated,
      cleanup: "AUTOMATIC_AFTER_ASSERTIONS"
    };
    Logger.log(JSON.stringify(result, null, 2));
    return result;
  } finally {
    deleteRowsByCaptureIdV282_(nutritionSheet, 21, captureId);
    deleteRowsByCaptureIdV282_(queueSheet, 1, captureId);
    Logger.log("Cleaned 2.8.2 test capture " + captureId + ".");
  }
}

function testAegisNutritionStatusLifecycleV282() {
  var captureId = "V282-STATUS-" + Utilities.getUuid();
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
  try {
    var row = appendAegisNutritionQueueJobV282_(
      queueSheet,
      captureId,
      "status lifecycle fixture",
      nutritionFingerprintV282_("status lifecycle fixture"),
      { client_id: "AEGIS_BACKEND_TEST", client_version: AEGIS_NUTRITION_BACKEND_VERSION_V282 }
    );
    var accepted = handleAegisNutritionCaptureStatusV282_({ capture_id: captureId });
    updateAegisNutritionQueueJobV282_(queueSheet, row, {
      status: "RETRY_SCHEDULED",
      updatedAt: new Date(),
      nextAttemptAt: new Date(Date.now() + 120000),
      attempts: 1,
      errorCode: "GEMINI_RATE_LIMITED",
      error: "Test fixture"
    });
    var retrying = handleAegisNutritionCaptureStatusV282_({ capture_id: captureId });
    if (
      accepted.capture_status !== "ACCEPTED" ||
      accepted.terminal !== false ||
      retrying.capture_status !== "RETRY_SCHEDULED" ||
      retrying.server_managed !== true ||
      !(retrying.retry_after_ms > 0)
    ) {
      throw new Error("2.8.2 status lifecycle verification failed for " + captureId + ".");
    }
    var result = {
      status: "PASS",
      capture_id: captureId,
      accepted_status: accepted.capture_status,
      retry_status: retrying.capture_status,
      retry_after_ms: retrying.retry_after_ms,
      cleanup: "AUTOMATIC_AFTER_ASSERTIONS"
    };
    Logger.log(JSON.stringify(result, null, 2));
    return result;
  } finally {
    deleteRowsByCaptureIdV282_(queueSheet, 1, captureId);
    Logger.log("Cleaned 2.8.2 status fixture " + captureId + ".");
  }
}

function deleteRowsByCaptureIdV282_(sheet, captureIdColumn, captureId) {
  if (!sheet || sheet.getLastRow() < 2) return;
  var matches = sheet
    .getRange(2, captureIdColumn, sheet.getLastRow() - 1, 1)
    .createTextFinder(captureId)
    .matchEntireCell(true)
    .findAll()
    .map(function(range) { return range.getRow(); })
    .sort(function(a, b) { return b - a; });
  matches.forEach(function(row) { sheet.deleteRow(row); });
}
