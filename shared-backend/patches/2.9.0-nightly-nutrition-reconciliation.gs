/**
 * AEGIS shared backend 2.9.0 -- provisional nutrition and nightly KINETIC reconciliation.
 *
 * Apps Script remains the system of record. Gemini is invoked once for the selected
 * food-log day, after every capture has already been recorded with a safe provisional
 * estimate. NotebookLM and HORIZON continue reading the reconciled tracker data.
 */

var AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290 = "AEGIS_NUTRITION_NIGHTLY_RECONCILIATION_V1";
var AEGIS_NUTRITION_PROVISIONAL_CONTRACT_V290 = "AEGIS_NUTRITION_PROVISIONAL_LOGGING_V1";
var AEGIS_NUTRITION_RECONCILIATION_SHEET_V290 = "_AEGIS_NUTRITION_RECONCILIATION_V1";
var AEGIS_NUTRITION_EVIDENCE_SHEET_V290 = "_AEGIS_NUTRITION_EVIDENCE_V1";
var AEGIS_NUTRITION_AUDIT_SHEET_V290 = "_AEGIS_NUTRITION_REVISION_AUDIT_V1";
var AEGIS_NUTRITION_NIGHTLY_HANDLER_V290 = "runAegisNutritionNightlyControllerV2111";
var AEGIS_NUTRITION_NIGHTLY_DEFAULT_MODEL_V290 = "gemini-3.5-flash";
var AEGIS_NUTRITION_NIGHTLY_MAX_CAPTURES_V290 = 30;

var AEGIS_NUTRITION_RECONCILIATION_HEADERS_V290 = [
  "Batch ID", "Food Date", "Capture ID", "Status", "Input",
  "Provisional JSON", "Reconciled JSON", "Decision",
  "Created At ISO", "Updated At ISO", "Completed At ISO",
  "Attempt Count", "Last Error Code", "Last Error"
];

var AEGIS_NUTRITION_EVIDENCE_HEADERS_V290 = [
  "Fingerprint", "Submitted Segment", "Canonical Item", "Canonical Portion",
  "Result JSON", "Source Authority", "Source URL", "Confidence",
  "Verified At ISO", "Last Used ISO", "Hit Count"
];

var AEGIS_NUTRITION_AUDIT_HEADERS_V290 = [
  "Batch ID", "Food Date", "Capture ID", "Decision",
  "Original JSON", "Revised JSON", "Calorie Delta",
  "Reason", "Applied At ISO"
];

function getAegisManagedSheetV290_(spreadsheet, name, headers) {
  var sheet = spreadsheet.getSheetByName(name);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(name);
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
    sheet.setFrozenRows(1);
    try { sheet.hideSheet(); } catch (ignored) {}
    return sheet;
  }
  var existing = sheet.getRange(1, 1, 1, headers.length).getDisplayValues()[0];
  for (var i = 0; i < headers.length; i++) {
    if (String(existing[i] || "").trim() !== headers[i]) {
      throw new Error(name + " column " + (i + 1) + " does not match the 2.9.0 contract.");
    }
  }
  return sheet;
}

function getAegisNutritionReconciliationSheetV290_(spreadsheet) {
  return getAegisManagedSheetV290_(
    spreadsheet,
    AEGIS_NUTRITION_RECONCILIATION_SHEET_V290,
    AEGIS_NUTRITION_RECONCILIATION_HEADERS_V290
  );
}

function getAegisNutritionEvidenceSheetV290_(spreadsheet) {
  return getAegisManagedSheetV290_(
    spreadsheet,
    AEGIS_NUTRITION_EVIDENCE_SHEET_V290,
    AEGIS_NUTRITION_EVIDENCE_HEADERS_V290
  );
}

function getAegisNutritionAuditSheetV290_(spreadsheet) {
  return getAegisManagedSheetV290_(
    spreadsheet,
    AEGIS_NUTRITION_AUDIT_SHEET_V290,
    AEGIS_NUTRITION_AUDIT_HEADERS_V290
  );
}

function normalizeAegisFoodDateV290_(value) {
  if (value instanceof Date) {
    return Utilities.formatDate(value, CONFIG.TIMEZONE, "yyyy-MM-dd");
  }
  var text = String(value || "").trim();
  var iso = text.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (iso) return text;
  var us = text.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (us) {
    return us[3] + "-" + ("0" + us[1]).slice(-2) + "-" + ("0" + us[2]).slice(-2);
  }
  return "";
}

function previousAegisFoodDateV290_() {
  var date = new Date(Date.now() - 24 * 60 * 60 * 1000);
  return Utilities.formatDate(date, CONFIG.TIMEZONE, "yyyy-MM-dd");
}

function nutritionQuantityFactorV290_(segment) {
  var text = String(segment || "").trim().toLowerCase();
  var match = text.match(/^(\d+(?:\.\d+)?)\b/);
  var factor = match ? Number(match[1]) : 1;
  if (!match) {
    var words = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6 };
    var word = text.match(/^(one|two|three|four|five|six)\b/);
    if (word) factor = words[word[1]];
  }
  return Math.max(0.25, Math.min(20, factor || 1));
}

function scaleAegisProvisionalTemplateV290_(segment, base, factor, label) {
  function scaled(value) {
    return Math.round(Number(value || 0) * factor * 10) / 10;
  }
  return {
    item: String(segment || label).trim(),
    portion: String(segment || "1 serving").trim(),
    calories: scaled(base[0]),
    protein: scaled(base[1]),
    carbs: scaled(base[2]),
    fat: scaled(base[3]),
    saturated_fat: scaled(base[4]),
    fiber: scaled(base[5]),
    sugar: scaled(base[6]),
    sodium: scaled(base[7]),
    cholesterol: scaled(base[8]),
    source_type: "COMPONENT_ESTIMATE",
    source_url: "",
    confidence: "LOW",
    lookup_depth: 1,
    assumptions: "Provisional " + label + " estimate recorded without AI; nightly KINETIC review required.",
    conservative_adjustment: true
  };
}

function estimateAegisNutritionProvisionalSegmentV290_(segment) {
  var text = String(segment || "").toLowerCase();
  var factor = nutritionQuantityFactorV290_(segment);
  var base = [300, 15, 40, 12, 4, 3, 6, 600, 45];
  var label = "general food";

  if (/\b(hamburger|cheeseburger|burger)\b/.test(text)) {
    base = [450, 25, 35, 24, 9, 2, 7, 850, 75];
    label = "hamburger component";
  } else if (/\b(hot sauce|texas pete)\b/.test(text)) {
    base = [5, 0, 1, 0, 0, 0, 0, 350, 0];
    label = "hot-sauce tablespoon";
  } else if (/\bsalsa\b/.test(text)) {
    base = [15, 0, 3, 0, 0, 1, 2, 150, 0];
    label = "salsa serving";
  } else if (/\b(tortilla|wrap)\b/.test(text)) {
    base = [150, 4, 26, 4, 1, 2, 2, 400, 0];
    label = "flour-tortilla serving";
  } else if (/\b(ramen|noodles?|instant noodles?)\b/.test(text)) {
    base = [380, 9, 52, 14, 7, 2, 3, 1600, 0];
    label = "instant-noodle package";
  } else if (/\b(rice|rice-a-roni)\b/.test(text)) {
    base = [240, 5, 41, 7, 4, 1, 1, 670, 20];
    label = "prepared rice serving";
  } else if (/\b(chicken|turkey)\b/.test(text)) {
    base = [200, 30, 6, 7, 2, 0, 1, 500, 85];
    label = "prepared poultry serving";
  } else if (/\b(pizza)\b/.test(text)) {
    base = [300, 13, 36, 12, 5, 2, 4, 700, 30];
    label = "pizza serving";
  } else if (/\b(egg|eggs)\b/.test(text)) {
    base = [80, 6, 1, 5, 2, 0, 0, 65, 185];
    label = "egg";
  } else if (/\b(toast|bread|bagel)\b/.test(text)) {
    base = [120, 4, 22, 3, 1, 2, 3, 220, 0];
    label = "bread serving";
  } else if (/\b(shake|smoothie)\b/.test(text)) {
    base = [250, 20, 30, 6, 2, 3, 18, 250, 25];
    label = "nutrition drink";
  }

  return scaleAegisProvisionalTemplateV290_(segment, base, factor, label);
}

function findAegisNutritionEvidenceV290_(spreadsheet, segment) {
  var sheet = getAegisNutritionEvidenceSheetV290_(spreadsheet);
  if (sheet.getLastRow() < 2) return null;
  var fingerprint = nutritionFingerprintV282_(segment);
  var finder = sheet.getRange(2, 1, sheet.getLastRow() - 1, 1)
    .createTextFinder(fingerprint)
    .matchEntireCell(true)
    .findNext();
  if (!finder) return null;
  var values = sheet.getRange(finder.getRow(), 1, 1, AEGIS_NUTRITION_EVIDENCE_HEADERS_V290.length)
    .getValues()[0];
  try {
    var item = JSON.parse(String(values[4] || ""));
    sheet.getRange(finder.getRow(), 10).setValue(new Date().toISOString());
    sheet.getRange(finder.getRow(), 11).setValue((Number(values[10]) || 0) + 1);
    return item;
  } catch (ignored) {
    return null;
  }
}

function buildAegisNutritionProvisionalV290_(input, spreadsheet) {
  var segments = splitAegisNutritionItemsV284_(input);
  var items = segments.map(function(segment) {
    var trusted = typeof resolveAegisTrustedNutritionV2100_ === "function"
      ? resolveAegisTrustedNutritionV2100_(spreadsheet, segment)
      : null;
    if (trusted && trusted.items && trusted.items.length === 1) {
      return trusted.items[0];
    }
    var evidence = findAegisNutritionEvidenceV290_(spreadsheet, segment);
    if (evidence && (
      typeof isAegisNutritionIdentitySafeV2100_ !== "function" ||
      isAegisNutritionIdentitySafeV2100_(segment, evidence)
    )) {
      evidence.assumptions = [
        String(evidence.assumptions || "").trim(),
        "Reused from the KINETIC evidence catalog; nightly confirmation pending."
      ].filter(Boolean).join(" ");
      return evidence;
    }
    var local = tryKnownFoodNutritionV284_(segment) ||
      tryDeterministicNutritionV282_(segment);
    if (local && local.items && local.items.length === 1) return local.items[0];
    return estimateAegisNutritionProvisionalSegmentV290_(segment);
  });
  return validateAegisNutritionResultV282_({
    items: items,
    overall_confidence: items.every(function(item) {
      return item.confidence === "HIGH" || item.confidence === "MEDIUM";
    }) ? "MEDIUM" : "LOW",
    lookup_depth: Math.max.apply(null, items.map(function(item) {
      return Number(item.lookup_depth) || 1;
    }))
  }, input);
}

function findAegisReconciliationRowV290_(sheet, captureId) {
  if (sheet.getLastRow() < 2) return 0;
  var finder = sheet.getRange(2, 3, sheet.getLastRow() - 1, 1)
    .createTextFinder(String(captureId))
    .matchEntireCell(true)
    .findNext();
  return finder ? finder.getRow() : 0;
}

function setAegisNutritionRowVerificationV290_(nutritionSheet, captureId, status) {
  if (!captureId || nutritionSheet.getLastRow() < 2) return;
  var matches = nutritionSheet.getRange(2, 21, nutritionSheet.getLastRow() - 1, 1)
    .createTextFinder(String(captureId))
    .matchEntireCell(true)
    .findAll();
  matches.forEach(function(match) {
    nutritionSheet.getRange(match.getRow(), 22).setValue(status);
    nutritionSheet.getRange(match.getRow(), 23).setValue("2.11.1");
  });
}

function registerAegisNutritionReconciliationV290_(
  spreadsheet,
  nutritionSheet,
  captureId,
  input,
  provisional
) {
  var sheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  var existingRow = findAegisReconciliationRowV290_(sheet, captureId);
  if (!existingRow) {
    var now = new Date().toISOString();
    var foodDate = Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "yyyy-MM-dd");
    sheet.appendRow([
      "", foodDate, captureId, "PENDING", input,
      JSON.stringify(provisional), "", "",
      now, now, "", 0, "", ""
    ]);
  }
  setAegisNutritionRowVerificationV290_(nutritionSheet, captureId, "ESTIMATED_PENDING");
  return getAegisNutritionVerificationStatusV290_(spreadsheet, captureId);
}

function getAegisNutritionVerificationStatusV290_(spreadsheet, captureId) {
  var sheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  var row = findAegisReconciliationRowV290_(sheet, captureId);
  if (!row) {
    return { status: "NOT_REGISTERED", capture_id: captureId };
  }
  var values = sheet.getRange(row, 1, 1, AEGIS_NUTRITION_RECONCILIATION_HEADERS_V290.length)
    .getValues()[0];
  return {
    batch_id: String(values[0] || ""),
    food_date: String(values[1] || ""),
    capture_id: String(values[2] || ""),
    status: String(values[3] || ""),
    decision: String(values[7] || ""),
    updated_at: values[9] ? new Date(values[9]).toISOString() : null,
    completed_at: values[10] ? new Date(values[10]).toISOString() : null,
    attempt_count: Number(values[11]) || 0,
    diagnostic_code: String(values[12] || "") || null,
    last_error: String(values[13] || "") || null
  };
}

function annotateAegisNutritionResponseV290_(response, captureId) {
  try {
    var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
    response.nutrition_verification = getAegisNutritionVerificationStatusV290_(
      spreadsheet,
      captureId
    );
    response.nutrition_verification_status =
      response.nutrition_verification.status || "NOT_REGISTERED";
  } catch (ignored) {
    response.nutrition_verification_status = "UNKNOWN";
  }
  response.provisional_contract = AEGIS_NUTRITION_PROVISIONAL_CONTRACT_V290;
  response.nightly_contract = AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290;
  return response;
}

function handleAegisLegacyNutritionV290_(foodText) {
  var captureId = "legacy-" + Utilities.getUuid();
  var response = enqueueAegisNutritionCaptureV282_({
    message: foodText,
    capture_id: captureId,
    client_id: "legacy-pwa",
    client_version: "2.11.1"
  });
  if (response && response.status === "success") {
    return String(response.result || "Nutrition recorded.") +
      "\n\nNightly KINETIC verification: pending.";
  }
  return "⚠️ " + String(response && response.error || "Nutrition could not be recorded.");
}

function getAegisNutritionNightlyModelV290_() {
  var props = PropertiesService.getScriptProperties();
  return String(
    props.getProperty("AEGIS_NUTRITION_NIGHTLY_MODEL") ||
    AEGIS_NUTRITION_NIGHTLY_DEFAULT_MODEL_V290
  ).trim();
}

function isKineticNightlySearchEnabledV290_() {
  var value = String(
    PropertiesService.getScriptProperties().getProperty(
      "AEGIS_NUTRITION_NIGHTLY_SEARCH_ENABLED"
    ) || "false"
  ).trim().toLowerCase();
  return value === "true" || value === "1" || value === "yes";
}

function isAegisNutritionReconciliationCandidateV290_(row, targetDate) {
  var rowDate = normalizeAegisFoodDateV290_(row[1]);
  var captureId = String(row[2] || "").trim();
  var status = String(row[3] || "").trim().toUpperCase();
  var isTestCapture = /^V\d+(?:\d+)?-TEST-/i.test(captureId);
  return !isTestCapture &&
    rowDate === targetDate &&
    ["PENDING", "DELAYED"].indexOf(status) >= 0;
}

function collectAegisNutritionReconciliationJobsV290_(spreadsheet, targetDate) {
  var sheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  if (sheet.getLastRow() < 2) return [];
  var values = sheet.getRange(
    2, 1, sheet.getLastRow() - 1, AEGIS_NUTRITION_RECONCILIATION_HEADERS_V290.length
  ).getValues();
  return values.map(function(row, index) {
    if (!isAegisNutritionReconciliationCandidateV290_(row, targetDate)) return null;
    try {
      var provisional = JSON.parse(String(row[5] || ""));
      return {
        row: index + 2,
        captureId: String(row[2] || ""),
        input: String(row[4] || ""),
        provisional: provisional,
        attempts: Number(row[11]) || 0
      };
    } catch (error) {
      throw new Error("Invalid provisional JSON for capture " + String(row[2] || "") + ".");
    }
  }).filter(Boolean);
}

function findOldestPendingAegisFoodDateV290_(spreadsheet, latestDate) {
  var sheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  if (sheet.getLastRow() < 2) return "";
  var values = sheet.getRange(2, 2, sheet.getLastRow() - 1, 3).getDisplayValues();
  var dates = values.filter(function(row) {
    return ["PENDING", "DELAYED"].indexOf(String(row[2])) >= 0 &&
      String(row[0]) <= latestDate;
  }).map(function(row) { return String(row[0]); }).sort();
  return dates.length ? dates[0] : "";
}

function buildKineticNightlyPromptV290_(targetDate, batchId, jobs) {
  var records = jobs.map(function(job) {
    return {
      capture_id: job.captureId,
      submitted_input: job.input,
      provisional_items: job.provisional.items
    };
  });
  return [
    "You are the nightly KINETIC nutrition reviewer for AEGIS.",
    "Review EVERY supplied food capture for " + targetDate + ".",
    "Return exactly one review for every capture_id and exactly one item for every provisional item.",
    "Never omit, combine, or reorder captures or food items.",
    "Previously resolved foods still require CONFIRM, ADJUST, or NEEDS_REVIEW.",
    isKineticNightlySearchEnabledV290_()
      ? "Use supplied evidence first. Perform fresh research only when it is missing, conflicting, or clearly stale."
      : "Google Search is disabled for this review. Use nutrition reasoning only; do not claim fresh research.",
    isKineticNightlySearchEnabledV290_()
      ? "Source priority: exact manufacturer/menu label; USDA FoodData Central; Open Food Facts; verified retailer label; reputable secondary database; component estimate."
      : "Do not invent or cite source URLs. Use source_type MODEL_ESTIMATE, an empty source_url, and candid confidence/assumptions.",
    "Do not label a retailer, aggregator, or crowdsourced page as OFFICIAL.",
    "Confirm product variant, serving basis, and prepared-versus-dry state before comparing values.",
    "Treat submitted brand and product words as immutable identity evidence. Never replace a branded or category-specific product with a generic keyword match.",
    "A flavor word is not the product category: for example, coffee yogurt is yogurt, not black coffee.",
    "Report a canonical serving definition separately from the user's submitted total.",
    "Provide canonical_serving_amount and canonical_serving_unit. When the label supports them, also provide canonical_serving_grams, canonical_serving_milliliters, canonical_count_unit, and canonical_count_per_serving.",
    "Provide submitted_serving_factor for the supplied total. Do not invent mass, volume, density, count equivalence, or package size.",
    "Never average different product variants, serving sizes, or preparation states.",
    "When an exact current manufacturer or government label matches, it outranks weaker sources and normally supplies the canonical value.",
    "When comparable sources have similar authority, use weights HIGH=5, MEDIUM=3, LOW=1.",
    "If HIGH and MEDIUM sources form a consistent cluster, exclude a materially conflicting LOW source as an outlier but mention it in the reason.",
    "Do not let one low-authority outlier distort several agreeing higher-authority sources.",
    "For unavailable local restaurant data, use a consistent ingredient/component estimate; perfection is not required.",
    "Small reasonable deviations should normally be CONFIRM. Explain meaningful adjustments.",
    "Return valid JSON only. Do not use markdown.",
    "Required structure:",
    '{"batch_id":"' + batchId + '","food_date":"' + targetDate + '","reviews":[{"capture_id":"...","decision":"CONFIRM|ADJUST|NEEDS_REVIEW","reason":"brief reason","items":[{"item":"specific item","portion":"submitted total portion","calories":400,"protein":20,"carbs":35,"fat":20,"saturated_fat":8,"fiber":2,"sugar":6,"sodium":700,"cholesterol":70,"source_type":"OFFICIAL|USDA|OPEN_FOOD_FACTS|COMPONENT_ESTIMATE|MODEL_ESTIMATE","source_url":"https://... or empty","confidence":"HIGH|MEDIUM|MEDIUM_LOW|LOW","lookup_depth":4,"assumptions":"explicit assumptions","conservative_adjustment":true,"canonical_serving_amount":1,"canonical_serving_unit":"serving|g|oz|ml|fl oz|cup|tbsp|tsp|package|count","canonical_serving_grams":null,"canonical_serving_milliliters":null,"canonical_count_unit":"cookie or empty","canonical_count_per_serving":null,"submitted_serving_factor":1}]}]}',
    "BATCH RECORDS:",
    JSON.stringify(records)
  ].join("\n");
}

function extractKineticNightlyRetryAfterMsV290_(body, response) {
  if (typeof extractGeminiRetryAfterMsV284_ === "function") {
    return extractGeminiRetryAfterMsV284_(body, response) || 0;
  }
  var text = String(body || "");
  var duration = text.match(/"retryDelay"\s*:\s*"([0-9]+(?:\.[0-9]+)?)s"/i);
  if (duration) return Math.ceil(Number(duration[1]) * 1000);
  try {
    var headers = response && response.getHeaders ? response.getHeaders() : {};
    var retryAfter = headers["Retry-After"] || headers["retry-after"];
    if (retryAfter && /^\d+(?:\.\d+)?$/.test(String(retryAfter).trim())) {
      return Math.ceil(Number(retryAfter) * 1000);
    }
  } catch (ignored) {}
  return 0;
}

function callKineticNightlyGeminiV290_(prompt) {
  if (typeof callKineticNightlyProviderV2111_ === "function") {
    return callKineticNightlyProviderV2111_(prompt);
  }
  return callKineticNightlyGeminiModelV290_(
    prompt,
    getAegisNutritionNightlyModelV290_()
  );
}

function callKineticNightlyGeminiModelV290_(prompt, model) {
  var cfg = getGeminiConfig();
  var url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent";
  var requestPayload = {
    contents: [{ role: "user", parts: [{ text: prompt }] }],
    generationConfig: {
      temperature: 0.1,
      responseMimeType: "application/json"
    }
  };
  if (isKineticNightlySearchEnabledV290_()) {
    requestPayload.tools = [{ google_search: {} }];
  }
  var response = UrlFetchApp.fetch(url, {
    method: "post",
    contentType: "application/json",
    headers: { "x-goog-api-key": cfg.apiKey },
    payload: JSON.stringify(requestPayload),
    muteHttpExceptions: true
  });
  var code = response.getResponseCode();
  var body = response.getContentText();
  if (code < 200 || code >= 300) {
    var capacity = (code === 429 || code === 503) &&
      typeof parseGeminiCapacityFailureV284_ === "function"
        ? parseGeminiCapacityFailureV284_(code, body, response, "NIGHTLY")
        : null;
    var diagnosticCode = capacity
      ? "KINETIC_NIGHTLY_" + String(capacity.code || "GEMINI_FAILED")
      : "KINETIC_NIGHTLY_HTTP_" + code;
    var diagnosticMessage = capacity
      ? capacity.message
      : "Nightly KINETIC review failed with HTTP " + code + ": " +
        compactGeminiProviderDetailV284_(body);
    var error = taggedNutritionErrorV281_(
      diagnosticCode,
      diagnosticMessage,
      code === 429 || code === 503
    );
    error.retryAfterMs = capacity
      ? capacity.retryAfterMs
      : extractKineticNightlyRetryAfterMsV290_(body, response) || 0;
    throw error;
  }
  var envelope = JSON.parse(body);
  var parts = envelope.candidates && envelope.candidates[0] &&
    envelope.candidates[0].content && envelope.candidates[0].content.parts;
  var raw = (parts || []).map(function(part) { return part.text || ""; }).join("").trim();
  if (!raw) throw new Error("Nightly KINETIC returned no review.");
  return JSON.parse(raw.replace(/^\`\`\`json\s*/i, "").replace(/\`\`\`\s*$/i, "").trim());
}

function sourceHostAegisV290_(url) {
  var match = String(url || "").toLowerCase().match(/^https:\/\/([^\/?#]+)/);
  return match ? match[1].replace(/^www\./, "") : "";
}

function classifyAegisNutritionSourceV290_(item) {
  var host = sourceHostAegisV290_(item && item.source_url);
  if (!host) return "LOW";
  if (/\.gov$/.test(host) || /(^|\.)fdc\.nal\.usda\.gov$/.test(host)) return "HIGH";
  var official = [
    "tyson.com", "texaspete.com", "missionfoods.com", "pepsico.info",
    "chobani.com", "bearnaked.com", "snackworks.com",
    "costco.com", "mcdonalds.com", "wendys.com", "tacobell.com",
    "chipotle.com", "subway.com", "panerabread.com", "starbucks.com",
    "chick-fil-a.com", "popeyes.com", "kfc.com", "fiveguys.com",
    "shakeshack.com", "olivegarden.com", "applebees.com", "chilis.com",
    "buffalowildwings.com", "noodles.com"
  ];
  if (typeof PropertiesService !== "undefined") {
    var configured = String(
      PropertiesService.getScriptProperties().getProperty(
        "AEGIS_NUTRITION_OFFICIAL_DOMAINS"
      ) || ""
    ).split(",").map(function(value) {
      return value.trim().toLowerCase().replace(/^www\./, "");
    }).filter(Boolean);
    official = official.concat(configured);
  }
  if (official.some(function(domain) {
    return host === domain || host.slice(-(domain.length + 1)) === "." + domain;
  })) return "HIGH";
  if (/(^|\.)openfoodfacts\.org$/.test(host)) return "MEDIUM";
  if (/\b(OFFICIAL|USDA)\b/.test(String(item && item.source_type || ""))) return "LOW";
  return "MEDIUM";
}

function confidenceRankAegisV290_(value) {
  return { LOW: 1, MEDIUM_LOW: 2, MEDIUM: 3, HIGH: 4 }[
    String(value || "LOW").toUpperCase()
  ] || 1;
}

function validateKineticNightlyResponseV290_(response, targetDate, batchId, jobs) {
  if (!response || response.batch_id !== batchId || response.food_date !== targetDate ||
      !Array.isArray(response.reviews) || response.reviews.length !== jobs.length) {
    throw new Error("Nightly KINETIC response did not preserve the complete batch contract.");
  }
  var byId = {};
  response.reviews.forEach(function(review) {
    var id = String(review && review.capture_id || "");
    if (!id || byId[id]) throw new Error("Nightly KINETIC returned a missing or duplicate capture ID.");
    byId[id] = review;
  });
  return jobs.map(function(job) {
    var review = byId[job.captureId];
    if (!review) throw new Error("Nightly KINETIC omitted capture " + job.captureId + ".");
    var segments = splitAegisNutritionItemsV284_(job.input);
    var result = enforceAegisNutritionItemIntegrityV284_({
      items: review.items,
      overall_confidence: review.items && review.items[0] && review.items[0].confidence || "LOW",
      lookup_depth: 4,
      allow_identity_portion_review: true
    }, segments);
    var validated = validateAegisNutritionResultV282_(result, job.input);
    validated.items.forEach(function(item, itemIndex) {
      var authority = classifyAegisNutritionSourceV290_(item);
      item.source_authority = authority;
      if ((item.source_type === "OFFICIAL" || item.source_type === "USDA") && authority !== "HIGH") {
        item.source_type = "MODEL_ESTIMATE";
        if (confidenceRankAegisV290_(item.confidence) > 2) item.confidence = "MEDIUM_LOW";
        item.assumptions = [
          String(item.assumptions || "").trim(),
          "Source authority was downgraded by AEGIS because the URL was not an approved official/government domain."
        ].filter(Boolean).join(" ");
      }
      if (typeof hardenAegisNightlyNutritionItemV2100_ === "function") {
        hardenAegisNightlyNutritionItemV2100_(
          segments[itemIndex],
          item,
          review.items[itemIndex]
        );
      }
    });
    return {
      job: job,
      review: review,
      validated: validated
    };
  });
}

function sumAegisCaloriesV290_(result) {
  return Math.round((result.items || []).reduce(function(sum, item) {
    return sum + (Number(item.calories) || 0);
  }, 0) * 10) / 10;
}

function decideAegisNutritionRevisionV290_(provisional, reviewed) {
  var original = sumAegisCaloriesV290_(provisional);
  var revised = sumAegisCaloriesV290_(reviewed);
  var delta = Math.round((revised - original) * 10) / 10;
  var percent = original > 0 ? Math.abs(delta) / original : 1;
  var threshold = Math.max(20, original * 0.05);
  var minimumConfidence = Math.min.apply(null, reviewed.items.map(function(item) {
    return confidenceRankAegisV290_(item.confidence);
  }));
  var hasStrongEvidence = reviewed.items.every(function(item) {
    return item.source_authority === "HIGH" || item.source_authority === "MEDIUM";
  });

  if (
    typeof hasAegisNutritionReviewMismatchV2100_ === "function" &&
    hasAegisNutritionReviewMismatchV2100_(reviewed)
  ) {
    return {
      decision: "NEEDS_REVIEW",
      delta: delta,
      percent: percent,
      applyReviewed: false,
      reason: "Product identity or portion equivalence did not pass deterministic validation."
    };
  }

  if (Math.abs(delta) <= threshold) {
    return { decision: "CONFIRM", delta: delta, percent: percent, applyReviewed: false };
  }
  if (percent > 0.30 && (minimumConfidence < 3 || !hasStrongEvidence)) {
    return { decision: "NEEDS_REVIEW", delta: delta, percent: percent, applyReviewed: false };
  }
  return { decision: "ADJUST", delta: delta, percent: percent, applyReviewed: true };
}

function nutritionItemFromSheetRowV290_(row) {
  return {
    item: row[2], portion: row[3], calories: Number(row[4]) || 0,
    protein: nullableSheetNumberV281_(row[5]),
    carbs: nullableSheetNumberV281_(row[6]),
    fat: nullableSheetNumberV281_(row[7]),
    sodium: nullableSheetNumberV281_(row[8]),
    saturated_fat: nullableSheetNumberV281_(row[10]),
    fiber: nullableSheetNumberV281_(row[11]),
    sugar: nullableSheetNumberV281_(row[12]),
    cholesterol: nullableSheetNumberV281_(row[13]),
    source_type: row[14], source_url: row[15], confidence: row[16],
    lookup_depth: Number(row[17]) || 1, assumptions: row[18],
    conservative_adjustment: row[19] === true
  };
}

function updateAegisNutritionCaptureRowsV290_(nutritionSheet, captureId, validated, status) {
  var matches = nutritionSheet.getRange(2, 21, nutritionSheet.getLastRow() - 1, 1)
    .createTextFinder(String(captureId))
    .matchEntireCell(true)
    .findAll();
  if (matches.length !== validated.items.length) {
    throw new Error("Nutrition row count changed for capture " + captureId + "; reconciliation stopped.");
  }
  matches.sort(function(a, b) { return a.getRow() - b.getRow(); });
  matches.forEach(function(match, index) {
    var rowNumber = match.getRow();
    var row = nutritionSheet.getRange(rowNumber, 1, 1, AEGIS_NUTRITION_HEADERS_V281.length)
      .getValues()[0];
    var item = validated.items[index];
    row[2] = item.item;
    row[3] = item.portion;
    row[4] = item.calories;
    row[5] = item.protein;
    row[6] = item.carbs;
    row[7] = item.fat;
    row[8] = item.sodium;
    row[9] = "Nightly KINETIC " + status + " via AEGIS 2.11.1";
    row[10] = item.saturated_fat;
    row[11] = item.fiber;
    row[12] = item.sugar;
    row[13] = item.cholesterol;
    row[14] = item.source_type;
    row[15] = item.source_url;
    row[16] = item.confidence;
    row[17] = item.lookup_depth;
    row[18] = item.assumptions;
    row[19] = item.conservative_adjustment;
    row[21] = status;
    row[22] = "2.11.1";
    nutritionSheet.getRange(rowNumber, 1, 1, row.length).setValues([row]);
  });
}

function upsertAegisNutritionEvidenceV290_(spreadsheet, segment, item) {
  if (confidenceRankAegisV290_(item.confidence) < 3) return;
  var sheet = getAegisNutritionEvidenceSheetV290_(spreadsheet);
  var fingerprint = nutritionFingerprintV282_(segment);
  var finder = sheet.getLastRow() < 2 ? null :
    sheet.getRange(2, 1, sheet.getLastRow() - 1, 1)
      .createTextFinder(fingerprint).matchEntireCell(true).findNext();
  var now = new Date().toISOString();
  var authority = item.source_authority || classifyAegisNutritionSourceV290_(item);
  var values = [
    fingerprint, segment, item.item, item.portion, JSON.stringify(item),
    authority, item.source_url || "", item.confidence, now, now, 1
  ];
  if (finder) {
    var existing = sheet.getRange(finder.getRow(), 1, 1, values.length).getValues()[0];
    values[10] = (Number(existing[10]) || 0) + 1;
    sheet.getRange(finder.getRow(), 1, 1, values.length).setValues([values]);
  } else {
    sheet.appendRow(values);
  }
}

function applyKineticNightlyReviewV290_(spreadsheet, targetDate, batchId, validatedReviews) {
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  var reconciliationSheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  var auditSheet = getAegisNutritionAuditSheetV290_(spreadsheet);
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) {
    throw new Error("Nutrition reconciliation is busy; no nightly adjustments were applied.");
  }

  try {
    // Preflight every target before changing any row. This prevents a missing or
    // duplicated capture from producing a partially applied nightly batch.
    if (nutritionSheet.getLastRow() < 2) {
      throw new Error("Nutrition tracker contains no data rows; no nightly adjustments were applied.");
    }
    validatedReviews.forEach(function(entry) {
      var matches = nutritionSheet
        .getRange(2, 21, nutritionSheet.getLastRow() - 1, 1)
        .createTextFinder(String(entry.job.captureId))
        .matchEntireCell(true)
        .findAll();
      if (matches.length !== entry.validated.items.length) {
        throw new Error(
          "Nutrition row count changed for capture " + entry.job.captureId +
          "; no nightly adjustments were applied."
        );
      }
    });

    var planned = validatedReviews.map(function(entry) {
      var decision = decideAegisNutritionRevisionV290_(
        entry.job.provisional,
        entry.validated
      );
      return {
        entry: entry,
        decision: decision,
        finalResult: decision.applyReviewed
          ? entry.validated
          : entry.job.provisional,
        rowStatus: decision.decision === "ADJUST"
          ? "ADJUSTED"
          : decision.decision === "CONFIRM"
            ? "VERIFIED"
            : "NEEDS_REVIEW"
      };
    });

    var results = [];
    planned.forEach(function(plan) {
      var entry = plan.entry;
      if (plan.decision.decision !== "NEEDS_REVIEW") {
        updateAegisNutritionCaptureRowsV290_(
          nutritionSheet,
          entry.job.captureId,
          plan.finalResult,
          plan.rowStatus
        );
        var segments = splitAegisNutritionItemsV284_(entry.job.input);
        // Cache the nightly reviewed evidence even when a small deviation keeps
        // the original displayed value. Future captures can then reuse the
        // strongest known product ruling without repeating research.
        entry.validated.items.forEach(function(item, index) {
          upsertAegisNutritionEvidenceV290_(spreadsheet, segments[index], item);
          if (typeof upsertAegisTrustedFoodV2100_ === "function") {
            upsertAegisTrustedFoodV2100_(
              spreadsheet,
              segments[index],
              item
            );
          }
        });
        var queueSheet = getAegisNutritionQueueSheetV282_(spreadsheet);
        var queueJob = findAegisNutritionQueueJobV282_(
          queueSheet,
          entry.job.captureId
        );
        if (queueJob) {
          updateAegisNutritionQueueJobV282_(queueSheet, queueJob.row, {
            resultJson: JSON.stringify(plan.finalResult),
            updatedAt: new Date()
          });
        }
      } else {
        setAegisNutritionRowVerificationV290_(
          nutritionSheet,
          entry.job.captureId,
          "NEEDS_REVIEW"
        );
      }

      var now = new Date().toISOString();
      auditSheet.appendRow([
        batchId,
        targetDate,
        entry.job.captureId,
        plan.decision.decision,
        JSON.stringify(entry.job.provisional),
        JSON.stringify(entry.validated),
        plan.decision.delta,
        String(entry.review.reason || ""),
        now
      ]);
      var existingCreatedAt =
        reconciliationSheet.getRange(entry.job.row, 9).getValue() || now;
      reconciliationSheet.getRange(
        entry.job.row,
        1,
        1,
        AEGIS_NUTRITION_RECONCILIATION_HEADERS_V290.length
      ).setValues([[
        batchId,
        targetDate,
        entry.job.captureId,
        plan.decision.decision === "NEEDS_REVIEW"
          ? "NEEDS_REVIEW"
          : "COMPLETED",
        entry.job.input,
        JSON.stringify(entry.job.provisional),
        JSON.stringify(entry.validated),
        plan.decision.decision,
        existingCreatedAt,
        now,
        now,
        entry.job.attempts + 1,
        "",
        ""
      ]]);
      results.push({
        capture_id: entry.job.captureId,
        decision: plan.decision.decision,
        calorie_delta: plan.decision.delta
      });
    });
    SpreadsheetApp.flush();
    return results;
  } finally {
    lock.releaseLock();
  }
}

function markKineticNightlyBatchDelayedV290_(sheet, jobs, batchId, error) {
  var now = new Date().toISOString();
  jobs.forEach(function(job) {
    var row = sheet.getRange(
      job.row, 1, 1, AEGIS_NUTRITION_RECONCILIATION_HEADERS_V290.length
    ).getValues()[0];
    row[0] = batchId;
    row[3] = "DELAYED";
    row[9] = now;
    row[11] = job.attempts + 1;
    row[12] = String(error && error.aegisCode || "KINETIC_NIGHTLY_FAILED");
    row[13] = String(error && error.message || error).slice(0, 1000);
    sheet.getRange(job.row, 1, 1, row.length).setValues([row]);
  });
}

function runKineticNightlyNutritionReviewV290(targetDate) {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var latestDate = previousAegisFoodDateV290_();
  var normalizedTarget = normalizeAegisFoodDateV290_(targetDate) ||
    findOldestPendingAegisFoodDateV290_(spreadsheet, latestDate) ||
    latestDate;
  var jobs = collectAegisNutritionReconciliationJobsV290_(spreadsheet, normalizedTarget);
  if (!jobs.length) {
    return {
      status: "NO_PENDING_ITEMS",
      food_date: normalizedTarget,
      reviewed_captures: 0,
      contract: AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290
    };
  }
  if (jobs.length > AEGIS_NUTRITION_NIGHTLY_MAX_CAPTURES_V290) {
    throw new Error(
      "Nightly nutrition batch exceeds " + AEGIS_NUTRITION_NIGHTLY_MAX_CAPTURES_V290 +
      " captures; run a bounded manual batch before enabling production scheduling."
    );
  }

  var batchId = "KINETIC-" + normalizedTarget + "-" +
    Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "yyyyMMdd-HHmmss");
  var reconciliationSheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  var localResults = [];
  try {
    if (typeof partitionAegisNutritionNightlyJobsV2111_ === "function") {
      var partitioned = partitionAegisNutritionNightlyJobsV2111_(jobs);
      if (partitioned.local.length) {
        localResults = applyKineticNightlyReviewV290_(
          spreadsheet,
          normalizedTarget,
          batchId,
          buildAegisLocalNightlyReviewsV2111_(partitioned.local)
        );
      }
      jobs = partitioned.provider;
      if (!jobs.length) {
        if (typeof recordAegisNutritionNightlySuccessV2111_ === "function") {
          recordAegisNutritionNightlySuccessV2111_(normalizedTarget, localResults.length);
        }
        return {
          status: "PASS",
          batch_id: batchId,
          food_date: normalizedTarget,
          reviewed_captures: localResults.length,
          locally_verified_captures: localResults.length,
          provider_reviewed_captures: 0,
          results: localResults,
          contract: AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290
        };
      }
    }
    var prompt = buildKineticNightlyPromptV290_(normalizedTarget, batchId, jobs);
    var response = callKineticNightlyGeminiV290_(prompt);
    var validated = validateKineticNightlyResponseV290_(
      response, normalizedTarget, batchId, jobs
    );
    var results = applyKineticNightlyReviewV290_(
      spreadsheet, normalizedTarget, batchId, validated
    );
    var combinedResults = localResults.concat(results);
    if (typeof recordAegisNutritionNightlySuccessV2111_ === "function") {
      recordAegisNutritionNightlySuccessV2111_(normalizedTarget, combinedResults.length);
    }
    return {
      status: "PASS",
      batch_id: batchId,
      food_date: normalizedTarget,
      reviewed_captures: combinedResults.length,
      locally_verified_captures: localResults.length,
      provider_reviewed_captures: results.length,
      results: combinedResults,
      contract: AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290
    };
  } catch (error) {
    markKineticNightlyBatchDelayedV290_(
      reconciliationSheet, jobs, batchId, error
    );
    var retry = typeof scheduleAegisNutritionNightlyRetryV2111_ === "function"
      ? scheduleAegisNutritionNightlyRetryV2111_(error)
      : { scheduled: false, next_attempt_at: null };
    if (typeof recordAegisNutritionNightlyFailureV2111_ === "function") {
      recordAegisNutritionNightlyFailureV2111_(normalizedTarget, error, retry);
    }
    var retryMessage = retry.scheduled
      ? "; retry trigger scheduled for " + retry.next_attempt_at
      : "; no retry trigger was scheduled";
    Logger.log(
      "KINETIC_NIGHTLY_DELAYED " +
      String(error && error.message || error) + retryMessage
    );
    return {
      status: "DELAYED",
      batch_id: batchId,
      food_date: normalizedTarget,
      reviewed_captures: 0,
      provisional_entries_preserved: true,
      diagnostic_code: String(error && error.aegisCode || "KINETIC_NIGHTLY_FAILED"),
      error: String(error && error.message || error),
      retry_scheduled: retry.scheduled === true,
      next_attempt_at: retry.next_attempt_at || null,
      contract: AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290
    };
  }
}


function runKineticNightlyNutritionReviewTodayV290() {
  var today = Utilities.formatDate(new Date(), CONFIG.TIMEZONE, "yyyy-MM-dd");
  return runKineticNightlyNutritionReviewV290(today);
}

function installAegisNutritionNightlyTriggerV290() {
  var managed = [
    "runKineticNightlyNutritionReviewV290",
    AEGIS_NUTRITION_NIGHTLY_HANDLER_V290,
    "runAegisNutritionNightlyWatchdogV2111"
  ];
  ScriptApp.getProjectTriggers().forEach(function(trigger) {
    if (managed.indexOf(trigger.getHandlerFunction()) >= 0) {
      ScriptApp.deleteTrigger(trigger);
    }
  });
  ScriptApp.newTrigger(AEGIS_NUTRITION_NIGHTLY_HANDLER_V290)
    .timeBased()
    .atHour(2)
    .everyDays(1)
    .inTimezone(CONFIG.TIMEZONE)
    .create();
  ScriptApp.newTrigger("runAegisNutritionNightlyWatchdogV2111")
    .timeBased()
    .atHour(4)
    .everyDays(1)
    .inTimezone(CONFIG.TIMEZONE)
    .create();
  return {
    status: "PASS",
    handler: AEGIS_NUTRITION_NIGHTLY_HANDLER_V290,
    timezone: CONFIG.TIMEZONE,
    schedule: "daily during the 2 AM hour",
    watchdog: "daily during the 4 AM hour"
  };
}

function getAegisNutritionNightlyHealthV290() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var sheet = getAegisNutritionReconciliationSheetV290_(spreadsheet);
  var counts = {};
  if (sheet.getLastRow() >= 2) {
    sheet.getRange(2, 4, sheet.getLastRow() - 1, 1).getDisplayValues()
      .forEach(function(row) {
        var status = String(row[0] || "UNKNOWN");
        counts[status] = (counts[status] || 0) + 1;
      });
  }
  return {
    status: "success",
    backend_version: typeof AEGIS_BACKEND_VERSION !== "undefined"
      ? AEGIS_BACKEND_VERSION
      : "2.11.1",
    model: getAegisNutritionNightlyModelV290_(),
    search_grounding_enabled: isKineticNightlySearchEnabledV290_(),
    counts: counts,
    contract: AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290,
    reliability: typeof getAegisNutritionNightlyReliabilityHealthV2111_ === "function"
      ? getAegisNutritionNightlyReliabilityHealthV2111_()
      : null
  };
}

function testAegisNutritionNightlyContractV290() {
  var provisional = {
    items: [{ calories: 400, confidence: "LOW", source_authority: "LOW" }]
  };
  var closeReview = {
    items: [{ calories: 418, confidence: "MEDIUM", source_authority: "MEDIUM" }]
  };
  var distantWeakReview = {
    items: [{ calories: 700, confidence: "LOW", source_authority: "LOW" }]
  };
  var close = decideAegisNutritionRevisionV290_(provisional, closeReview);
  var distant = decideAegisNutritionRevisionV290_(provisional, distantWeakReview);
  if (close.decision !== "CONFIRM") {
    throw new Error("2.9.0 reasonable-deviation confirmation failed.");
  }
  if (distant.decision !== "NEEDS_REVIEW") {
    throw new Error("2.9.0 weak large-deviation safeguard failed.");
  }
  var prompt = buildKineticNightlyPromptV290_(
    "2026-09-14",
    "KINETIC-TEST",
    [{
      captureId: "CAP-TEST",
      input: "local restaurant hamburger",
      provisional: {
        items: [estimateAegisNutritionProvisionalSegmentV290_(
          "local restaurant hamburger"
        )]
      }
    }]
  );
  if (prompt.indexOf("Review EVERY supplied food capture") < 0 ||
      prompt.indexOf("CAP-TEST") < 0) {
    throw new Error("2.9.0 nightly prompt contract failed.");
  }
  var quotaClassification = parseGeminiCapacityFailureV284_(
    429,
    '{"error":{"message":"quota exceeded","details":[{"quotaMetric":"requestsPerMinute","retryDelay":"60s"}]}}',
    null,
    "NIGHTLY"
  );
  if (quotaClassification.code !== "GEMINI_RPM_LIMITED") {
    throw new Error("2.9.0 nightly quota classification failed.");
  }
  var retryDelay = extractKineticNightlyRetryAfterMsV290_(
    '{"error":{"details":[{"retryDelay":"16.639s"}]}}',
    null
  );
  if (retryDelay !== 16639) {
    throw new Error("2.9.0 nightly retry-delay parsing failed.");
  }
  var dateCell = new Date("2026-09-14T12:00:00-04:00");
  var pendingDateRow = ["", dateCell, "CAP-DATE", "PENDING"];
  if (!isAegisNutritionReconciliationCandidateV290_(
        pendingDateRow, "2026-09-14")) {
    throw new Error("2.9.0 Sheets Date eligibility normalization failed.");
  }
  var testArtifactRow = ["", dateCell, "V282-TEST-ARTIFACT", "PENDING"];
  if (isAegisNutritionReconciliationCandidateV290_(
        testArtifactRow, "2026-09-14")) {
    throw new Error("2.9.0 test capture exclusion failed.");
  }
  var ramen = estimateAegisNutritionProvisionalSegmentV290_(
    "1 Pack Chicken Ramen"
  );
  if (ramen.assumptions.indexOf("instant-noodle package") < 0) {
    throw new Error("2.9.0 ramen precedence over poultry failed.");
  }
  var result = {
    status: "PASS",
    close_deviation: close.decision,
    large_weak_deviation: distant.decision,
    quota_classification: "PASS",
    retry_delay_parsing: "PASS",
    sheets_date_eligibility: "PASS",
    test_capture_exclusion: "PASS",
    ramen_classifier_precedence: "PASS",
    contract: AEGIS_NUTRITION_NIGHTLY_CONTRACT_V290
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}
