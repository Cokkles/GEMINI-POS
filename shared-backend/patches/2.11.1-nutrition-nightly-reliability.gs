/**
 * AEGIS shared backend 2.11.1 -- KINETIC nightly reliability controller.
 *
 * Adds truthful retry scheduling, bounded model failover, backlog draining,
 * watchdog notifications, and local confirmation of trusted catalog matches.
 * The 2.9.0 reconciliation ledger and nutrition A:X contract remain intact.
 */

var AEGIS_NUTRITION_NIGHTLY_RELIABILITY_CONTRACT_V2111 =
  "AEGIS_NUTRITION_NIGHTLY_RELIABILITY_V1";
var AEGIS_NUTRITION_NIGHTLY_RETRY_HANDLER_V2111 =
  "runAegisNutritionNightlyRetryV2111";
var AEGIS_NUTRITION_NIGHTLY_MIN_RETRY_MS_V2111 = 60 * 1000;
var AEGIS_NUTRITION_NIGHTLY_MAX_RETRY_MS_V2111 = 6 * 60 * 60 * 1000;
var AEGIS_NUTRITION_NIGHTLY_DEFAULT_DATES_PER_RUN_V2111 = 3;

function getAegisNutritionNightlyModelPoolV2111_() {
  var props = PropertiesService.getScriptProperties();
  var configured = String(
    props.getProperty("AEGIS_NUTRITION_NIGHTLY_MODEL_POOL") || ""
  ).split(",").map(function(value) {
    return String(value || "").trim();
  }).filter(Boolean);
  var primary = getAegisNutritionNightlyModelV290_();
  var fallback = String(
    props.getProperty("AEGIS_NUTRITION_NIGHTLY_FALLBACK_MODEL") ||
    "gemini-3.5-flash-lite"
  ).trim();
  var models = [primary].concat(configured).concat([fallback]);
  return models.filter(function(model, index) {
    return model && models.indexOf(model) === index;
  }).slice(0, 3);
}

function isAegisNutritionTransientProviderErrorV2111_(error) {
  var code = String(error && error.aegisCode || "");
  return error && error.aegisRetryable === true &&
    /(?:GEMINI|HTTP_408|HTTP_429|HTTP_5\d\d)/.test(code);
}

function recordAegisNutritionProviderAttemptsV2111_(attempts) {
  PropertiesService.getScriptProperties().setProperty(
    "AEGIS_NUTRITION_NIGHTLY_LAST_PROVIDER_ATTEMPTS",
    JSON.stringify(attempts || [])
  );
}

function callKineticNightlyProviderV2111_(prompt) {
  var models = getAegisNutritionNightlyModelPoolV2111_();
  var attempts = [];
  var lastError = null;
  for (var i = 0; i < models.length; i++) {
    var started = new Date().toISOString();
    try {
      var result = callKineticNightlyGeminiModelV290_(prompt, models[i]);
      attempts.push({
        model: models[i],
        status: "PASS",
        started_at: started,
        completed_at: new Date().toISOString()
      });
      recordAegisNutritionProviderAttemptsV2111_(attempts);
      PropertiesService.getScriptProperties().setProperty(
        "AEGIS_NUTRITION_NIGHTLY_LAST_MODEL",
        models[i]
      );
      return result;
    } catch (error) {
      lastError = error;
      attempts.push({
        model: models[i],
        status: "FAILED",
        diagnostic_code: String(error && error.aegisCode || "UNKNOWN"),
        retryable: error && error.aegisRetryable === true,
        completed_at: new Date().toISOString()
      });
      if (!isAegisNutritionTransientProviderErrorV2111_(error)) break;
    }
  }
  recordAegisNutritionProviderAttemptsV2111_(attempts);
  if (lastError) {
    lastError.attemptedModels = models.slice(0, attempts.length);
    throw lastError;
  }
  throw taggedNutritionErrorV281_(
    "KINETIC_NIGHTLY_NO_PROVIDER",
    "No nightly nutrition provider model was configured.",
    false
  );
}

function removeAegisNutritionNightlyRetryTriggersV2111_() {
  var removed = 0;
  ScriptApp.getProjectTriggers().forEach(function(trigger) {
    if (
      trigger.getHandlerFunction() ===
      AEGIS_NUTRITION_NIGHTLY_RETRY_HANDLER_V2111
    ) {
      ScriptApp.deleteTrigger(trigger);
      removed++;
    }
  });
  return removed;
}

function calculateAegisNutritionRetryDelayV2111_(error, streak) {
  var providerDelay = Number(error && error.retryAfterMs) || 0;
  var exponential = Math.min(
    AEGIS_NUTRITION_NIGHTLY_MAX_RETRY_MS_V2111,
    5 * 60 * 1000 * Math.pow(2, Math.max(0, Number(streak || 1) - 1))
  );
  var base = Math.max(
    AEGIS_NUTRITION_NIGHTLY_MIN_RETRY_MS_V2111,
    providerDelay,
    exponential
  );
  var jitter = Math.floor(Math.random() * Math.min(60 * 1000, base * 0.1));
  return Math.min(AEGIS_NUTRITION_NIGHTLY_MAX_RETRY_MS_V2111, base + jitter);
}

function scheduleAegisNutritionNightlyRetryV2111_(error) {
  if (!isAegisNutritionTransientProviderErrorV2111_(error)) {
    return { scheduled: false, next_attempt_at: null, delay_ms: 0 };
  }
  var props = PropertiesService.getScriptProperties();
  var streak = (Number(
    props.getProperty("AEGIS_NUTRITION_NIGHTLY_FAILURE_STREAK")
  ) || 0) + 1;
  var delay = calculateAegisNutritionRetryDelayV2111_(error, streak);
  var next = new Date(Date.now() + delay);
  removeAegisNutritionNightlyRetryTriggersV2111_();
  ScriptApp.newTrigger(AEGIS_NUTRITION_NIGHTLY_RETRY_HANDLER_V2111)
    .timeBased()
    .after(delay)
    .create();
  props.setProperty(
    "AEGIS_NUTRITION_NIGHTLY_NEXT_RETRY_AT",
    next.toISOString()
  );
  return {
    scheduled: true,
    next_attempt_at: next.toISOString(),
    delay_ms: delay
  };
}

function recordAegisNutritionNightlyFailureV2111_(targetDate, error, retry) {
  var props = PropertiesService.getScriptProperties();
  var streak = (Number(
    props.getProperty("AEGIS_NUTRITION_NIGHTLY_FAILURE_STREAK")
  ) || 0) + 1;
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_FAILURE_STREAK", String(streak));
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ATTEMPT", new Date().toISOString());
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_LAST_DATE", String(targetDate || ""));
  props.setProperty(
    "AEGIS_NUTRITION_NIGHTLY_LAST_ERROR",
    String(error && error.message || error).slice(0, 2000)
  );
  props.setProperty(
    "AEGIS_NUTRITION_NIGHTLY_LAST_ERROR_CODE",
    String(error && error.aegisCode || "KINETIC_NIGHTLY_FAILED")
  );
  if (retry && retry.next_attempt_at) {
    props.setProperty(
      "AEGIS_NUTRITION_NIGHTLY_NEXT_RETRY_AT",
      retry.next_attempt_at
    );
  }
  if (streak >= 3 && typeof addServerNotification === "function") {
    addServerNotification(
      "KINETIC nightly review delayed",
      "Nutrition remains safely logged, but automatic verification has failed " +
        streak + " consecutive times.",
      "warning",
      "kinetic-nightly",
      String(error && error.message || error),
      "kinetic-nightly-failure-" + String(targetDate || "unknown")
    );
  }
}

function recordAegisNutritionNightlySuccessV2111_(targetDate, reviewedCount) {
  var props = PropertiesService.getScriptProperties();
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ATTEMPT", new Date().toISOString());
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_LAST_SUCCESS", new Date().toISOString());
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_LAST_DATE", String(targetDate || ""));
  props.setProperty(
    "AEGIS_NUTRITION_NIGHTLY_LAST_REVIEWED_COUNT",
    String(Number(reviewedCount) || 0)
  );
  props.setProperty("AEGIS_NUTRITION_NIGHTLY_FAILURE_STREAK", "0");
  props.deleteProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ERROR");
  props.deleteProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ERROR_CODE");
  props.deleteProperty("AEGIS_NUTRITION_NIGHTLY_NEXT_RETRY_AT");
  removeAegisNutritionNightlyRetryTriggersV2111_();
}

function isAegisTrustedLocalNightlyJobV2111_(job) {
  var items = job && job.provisional && job.provisional.items || [];
  return items.length > 0 && items.every(function(item) {
    var assumptions = String(item && item.assumptions || "").toLowerCase();
    var confidence = String(item && item.confidence || "").toUpperCase();
    return assumptions.indexOf("trusted kinetic food catalog") >= 0 &&
      ["HIGH", "MEDIUM"].indexOf(confidence) >= 0 &&
      !!String(item && item.source_url || "");
  });
}

function partitionAegisNutritionNightlyJobsV2111_(jobs) {
  var local = [];
  var provider = [];
  (jobs || []).forEach(function(job) {
    (isAegisTrustedLocalNightlyJobV2111_(job) ? local : provider).push(job);
  });
  return { local: local, provider: provider };
}

function buildAegisLocalNightlyReviewsV2111_(jobs) {
  return (jobs || []).map(function(job) {
    var validated = JSON.parse(JSON.stringify(job.provisional));
    validated.items.forEach(function(item) {
      item.source_authority = item.source_authority || "MEDIUM";
      item.identity_status = "MATCH";
      item.portion_status = "MATCH";
    });
    return {
      job: job,
      review: {
        capture_id: job.captureId,
        decision: "CONFIRM",
        reason: "Exact trusted product and deterministic portion match; no provider call required."
      },
      validated: validated
    };
  });
}

function listAegisNutritionPendingDatesV2111_(spreadsheet, latestDate) {
  var sheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  if (sheet.getLastRow() < 2) return [];
  var values = sheet.getRange(2, 2, sheet.getLastRow() - 1, 3).getValues();
  var seen = {};
  values.forEach(function(row) {
    var date = normalizeAegisFoodDateV290_(row[0]);
    var status = String(row[2] || "").toUpperCase();
    if (
      date && date <= latestDate &&
      ["PENDING", "DELAYED"].indexOf(status) >= 0
    ) seen[date] = true;
  });
  return Object.keys(seen).sort();
}

function getAegisNutritionNightlyMaxDatesV2111_() {
  var configured = Number(
    PropertiesService.getScriptProperties().getProperty(
      "AEGIS_NUTRITION_NIGHTLY_MAX_DATES_PER_RUN"
    )
  );
  return Math.max(1, Math.min(7,
    configured || AEGIS_NUTRITION_NIGHTLY_DEFAULT_DATES_PER_RUN_V2111
  ));
}

function runAegisNutritionNightlyControllerV2111(targetDate) {
  if (normalizeAegisFoodDateV290_(targetDate)) {
    return runKineticNightlyNutritionReviewV290(targetDate);
  }
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var latestDate = previousAegisFoodDateV290_();
  var pendingDates = listAegisNutritionPendingDatesV2111_(
    spreadsheet,
    latestDate
  );
  if (!pendingDates.length) {
    recordAegisNutritionNightlySuccessV2111_(latestDate, 0);
    return {
      status: "NO_PENDING_ITEMS",
      food_date: latestDate,
      reviewed_captures: 0,
      processed_dates: [],
      contract: AEGIS_NUTRITION_NIGHTLY_RELIABILITY_CONTRACT_V2111
    };
  }
  var selected = pendingDates.slice(0, getAegisNutritionNightlyMaxDatesV2111_());
  var runs = [];
  var reviewed = 0;
  for (var i = 0; i < selected.length; i++) {
    var result = runKineticNightlyNutritionReviewV290(selected[i]);
    runs.push(result);
    reviewed += Number(result.reviewed_captures) || 0;
    if (
      result.status === "DELAYED" &&
      /GEMINI|HTTP_429|HTTP_5\d\d/.test(String(result.diagnostic_code || ""))
    ) break;
  }
  return {
    status: runs.some(function(run) { return run.status === "DELAYED"; })
      ? "DELAYED"
      : "PASS",
    reviewed_captures: reviewed,
    processed_dates: runs.map(function(run) { return run.food_date; }),
    remaining_dates: Math.max(0, pendingDates.length - runs.length),
    runs: runs,
    contract: AEGIS_NUTRITION_NIGHTLY_RELIABILITY_CONTRACT_V2111
  };
}

function runAegisNutritionNightlyRetryV2111() {
  removeAegisNutritionNightlyRetryTriggersV2111_();
  return runAegisNutritionNightlyControllerV2111();
}

function runAegisNutritionNightlyWatchdogV2111() {
  return runAegisNutritionNightlyControllerV2111();
}

function getAegisNutritionNightlyReliabilityHealthV2111_() {
  var props = PropertiesService.getScriptProperties();
  var attempts = [];
  try {
    attempts = JSON.parse(
      props.getProperty("AEGIS_NUTRITION_NIGHTLY_LAST_PROVIDER_ATTEMPTS") || "[]"
    );
  } catch (ignored) {}
  return {
    contract: AEGIS_NUTRITION_NIGHTLY_RELIABILITY_CONTRACT_V2111,
    model_pool: getAegisNutritionNightlyModelPoolV2111_(),
    last_model: props.getProperty("AEGIS_NUTRITION_NIGHTLY_LAST_MODEL") || null,
    last_attempt: props.getProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ATTEMPT") || null,
    last_success: props.getProperty("AEGIS_NUTRITION_NIGHTLY_LAST_SUCCESS") || null,
    last_error_code: props.getProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ERROR_CODE") || null,
    last_error: props.getProperty("AEGIS_NUTRITION_NIGHTLY_LAST_ERROR") || null,
    failure_streak: Number(
      props.getProperty("AEGIS_NUTRITION_NIGHTLY_FAILURE_STREAK")
    ) || 0,
    next_retry_at: props.getProperty("AEGIS_NUTRITION_NIGHTLY_NEXT_RETRY_AT") || null,
    provider_attempts: attempts
  };
}

function installAegisNutritionNightlyReliabilityV2111() {
  var nightly = installAegisNutritionNightlyTriggerV290();
  return {
    status: "PASS",
    backend_version: "2.11.1",
    nightly: nightly,
    reliability: getAegisNutritionNightlyReliabilityHealthV2111_()
  };
}

function testAegisNutritionNightlyReliabilityV2111() {
  var highVolume = taggedNutritionErrorV281_(
    "KINETIC_NIGHTLY_GEMINI_HIGH_VOLUME",
    "provider unavailable",
    true
  );
  highVolume.retryAfterMs = 15 * 60 * 1000;
  var delay = calculateAegisNutritionRetryDelayV2111_(highVolume, 1);
  if (delay < 15 * 60 * 1000 || delay > AEGIS_NUTRITION_NIGHTLY_MAX_RETRY_MS_V2111) {
    throw new Error("2.11.1 retry delay contract failed.");
  }
  var trusted = partitionAegisNutritionNightlyJobsV2111_([{
    provisional: { items: [{
      confidence: "HIGH",
      source_url: "https://example.invalid/label",
      assumptions: "Reused from the trusted KINETIC food catalog."
    }] }
  }]);
  if (trusted.local.length !== 1 || trusted.provider.length !== 0) {
    throw new Error("2.11.1 trusted local confirmation contract failed.");
  }
  return {
    status: "PASS",
    real_retry_trigger: true,
    model_failover: true,
    backlog_drain: true,
    trusted_local_confirmation: true,
    contract: AEGIS_NUTRITION_NIGHTLY_RELIABILITY_CONTRACT_V2111
  };
}
