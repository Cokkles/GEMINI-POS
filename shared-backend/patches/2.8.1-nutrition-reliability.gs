/**
 * AEGIS shared backend 2.8.1 -- additive nutrition reliability module.
 *
 * Installation against the authoritative 2.8.0 Code.gs:
 *   1. Add this file to the Apps Script project.
 *   2. Add the router stanza documented in 2.8.1-ROUTER-INSTALL.md before the
 *      legacy /calories branch in doPost(e).
 *   3. Change AEGIS_BACKEND_VERSION to "2.8.1" only in the authoritative
 *      Code.gs, then deploy a new web-app version and run the test functions.
 *
 * This module never changes columns A:J. Existing Windows/PWA/Android clients
 * and KINETIC therefore retain their current sheet contract.
 */

var AEGIS_NUTRITION_CONTRACT_V281 = "AEGIS_NUTRITION_CAPTURE_V2";
var AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281 = "AEGIS_CAPTURE_RELIABILITY_V1";
var AEGIS_NUTRITION_MODEL_DEFAULT_V281 = "gemini-3.6-flash";

var AEGIS_NUTRITION_HEADERS_V281 = [
  "Date",
  "Time",
  "Food Item",
  "Portion",
  "Calories",
  "Protein (g)",
  "Carbs (g)",
  "Fat (g)",
  "Sodium (mg)",
  "Notes",
  "Saturated Fat (g)",
  "Fiber (g)",
  "Sugar (g)",
  "Cholesterol (mg)",
  "Source Type",
  "Source URL",
  "Confidence",
  "Lookup Depth",
  "Assumptions",
  "Conservative Adjustment",
  "Capture ID",
  "Capture Status",
  "Backend Version",
  "Logged At ISO"
];

function handleAegisNutritionCaptureV281_(contents) {
  var input = normalizeAegisNutritionInputV281_(contents && contents.message || "");
  var captureId = normalizeAegisCaptureIdV281_(
    contents && (contents.capture_id || contents.submission_id)
  );

  if (!input) {
    return nutritionErrorV281_(
      "NUTRITION_INPUT_EMPTY",
      "Please provide food details to log.",
      false,
      "NOT_STARTED",
      captureId
    );
  }

  var sheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID).getActiveSheet();
  ensureAegisNutritionHeadersV281_(sheet);

  var existing = findAegisNutritionCaptureV281_(sheet, captureId);
  if (existing) {
    existing.deduplicated = true;
    existing.totalCalories = getTodayCaloriesFromSheet();
    return existing;
  }

  var parsed;
  try {
    parsed = callGeminiForNutritionV281_(input);
  } catch (error) {
    return nutritionFailureFromErrorV281_(error, captureId);
  }

  var validated;
  try {
    validated = validateAegisNutritionResultV281_(parsed, input);
  } catch (error) {
    return nutritionErrorV281_(
      "NUTRITION_RESULT_INVALID",
      String(error && error.message || error),
      false,
      "NOT_STARTED",
      captureId
    );
  }

  var lock = LockService.getScriptLock();
  if (!lock.tryLock(15000)) {
    return nutritionErrorV281_(
      "NUTRITION_WRITE_BUSY",
      "Nutrition is temporarily busy. AEGIS can retry this capture safely.",
      true,
      "NOT_STARTED",
      captureId
    );
  }

  try {
    // Recheck under the lock so two clients cannot append the same capture.
    existing = findAegisNutritionCaptureV281_(sheet, captureId);
    if (existing) {
      existing.deduplicated = true;
      existing.totalCalories = getTodayCaloriesFromSheet();
      return existing;
    }

    appendAegisNutritionRowsV281_(sheet, validated.items, captureId);
  } catch (writeError) {
    return nutritionErrorV281_(
      "NUTRITION_WRITE_FAILED",
      String(writeError && writeError.message || writeError),
      false,
      "UNKNOWN",
      captureId
    );
  } finally {
    lock.releaseLock();
  }

  var summary = renderAegisNutritionSummaryV281_(validated.items);
  return {
    status: "success",
    capture_status: "CONFIRMED",
    contract: AEGIS_NUTRITION_CONTRACT_V281,
    reliability_contract: AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281,
    backend_version: "2.8.1",
    capture_id: captureId,
    deduplicated: false,
    result: summary,
    items: validated.items,
    totals: sumAegisNutritionItemsV281_(validated.items),
    totalCalories: getTodayCaloriesFromSheet(),
    source_policy: "OFFICIAL_USDA_OPEN_FOOD_FACTS_COMPONENT_ESTIMATE_V1",
    lookup_depth: validated.lookup_depth,
    confidence: validated.confidence
  };
}

function normalizeAegisNutritionInputV281_(message) {
  return String(message || "")
    .replace(/^\/calories\s*/i, "")
    .replace(/\r\n?/g, "\n")
    .trim()
    .slice(0, 8000);
}

function normalizeAegisCaptureIdV281_(value) {
  var text = String(value || "").trim();
  if (!text) text = Utilities.getUuid();
  if (!/^[A-Za-z0-9._:-]{8,128}$/.test(text)) {
    throw new Error("Capture ID contains unsupported characters.");
  }
  return text;
}

function getAegisNutritionModelV281_() {
  var props = PropertiesService.getScriptProperties();
  return String(
    props.getProperty("AEGIS_NUTRITION_MODEL") ||
    props.getProperty("GEMINI_MODEL") ||
    AEGIS_NUTRITION_MODEL_DEFAULT_V281
  ).trim();
}

function callGeminiForNutritionV281_(foodText) {
  var cfg = getGeminiConfig();
  var model = getAegisNutritionModelV281_();
  var url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent";
  var prompt = buildAegisNutritionPromptV281_(foodText);
  var attempts = 3;
  var delays = [0, 2000, 5000];
  var lastError = null;

  for (var attempt = 0; attempt < attempts; attempt++) {
    if (delays[attempt]) Utilities.sleep(delays[attempt]);
    var response;
    try {
      response = UrlFetchApp.fetch(url, {
        method: "post",
        contentType: "application/json",
        headers: { "x-goog-api-key": cfg.apiKey },
        payload: JSON.stringify({
          contents: [{ role: "user", parts: [{ text: prompt }] }],
          tools: [{ google_search: {} }],
          generationConfig: {
            temperature: 0.15,
            responseMimeType: "application/json"
          }
        }),
        muteHttpExceptions: true
      });
    } catch (transportError) {
      lastError = taggedNutritionErrorV281_(
        "GEMINI_TRANSPORT",
        String(transportError && transportError.message || transportError),
        true
      );
      continue;
    }

    var code = response.getResponseCode();
    var body = response.getContentText();
    if (code === 429 || code === 503) {
      lastError = taggedNutritionErrorV281_(
        code === 429 ? "GEMINI_RATE_LIMITED" : "GEMINI_HIGH_VOLUME",
        "Gemini is temporarily at high volume (HTTP " + code + ").",
        true
      );
      continue;
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
      return JSON.parse(raw.replace(/^```json\s*/i, "").replace(/```\s*$/i, "").trim());
    } catch (parseResultError) {
      throw taggedNutritionErrorV281_(
        "GEMINI_JSON_INVALID",
        "Gemini returned nutrition data in an invalid format.",
        false
      );
    }
  }

  throw lastError || taggedNutritionErrorV281_(
    "GEMINI_HIGH_VOLUME",
    "Gemini is temporarily unavailable.",
    true
  );
}

function buildAegisNutritionPromptV281_(foodText) {
  return [
    "You are the KINETIC nutrition evidence and estimation engine for AEGIS.",
    "Identify and log every food in the user's description. Commas, semicolons, pipes, and line breaks may separate multiple items, but preserve commas inside a restaurant or branded item name when context requires it.",
    "Use Google Search grounding and follow this evidence order:",
    "1. Official restaurant or manufacturer nutrition and menu pages.",
    "2. USDA FoodData Central.",
    "3. Open Food Facts for packaged foods.",
    "4. Ingredient-level reconstruction using authoritative component values.",
    "5. A transparent best-effort estimate.",
    "Do not fail merely because exact published nutrition or weights are unavailable.",
    "When portions or preparation are uncertain, use reasonable assumptions and prefer a modest conservative overestimate rather than an underestimate.",
    "Never use zero to represent an unknown nutrient. Estimate it or use null.",
    "Do not include sides, drinks, sauces, or modifications unless the user stated them or an official menu description clearly includes them.",
    "Return ONLY one JSON object using this exact structure:",
    '{"items":[{"item":"specific name","portion":"estimated or stated serving","calories":850,"protein":44,"carbs":44,"fat":55,"saturated_fat":22,"fiber":2,"sugar":7,"sodium":1550,"cholesterol":150,"source_type":"OFFICIAL|USDA|OPEN_FOOD_FACTS|COMPONENT_ESTIMATE|MODEL_ESTIMATE","source_url":"https://... or empty","confidence":"HIGH|MEDIUM|MEDIUM_LOW|LOW","lookup_depth":1,"assumptions":"brief explicit assumptions","conservative_adjustment":true}],"overall_confidence":"MEDIUM_LOW","lookup_depth":5}',
    "All numeric nutrients are per returned portion. Calories are kcal; protein/carbs/fat/saturated_fat/fiber/sugar are grams; sodium/cholesterol are milligrams.",
    "USER FOOD DESCRIPTION:",
    foodText
  ].join("\n");
}

function taggedNutritionErrorV281_(code, message, retryable) {
  var error = new Error(message);
  error.aegisCode = code;
  error.aegisRetryable = retryable === true;
  return error;
}

function nutritionFailureFromErrorV281_(error, captureId) {
  return nutritionErrorV281_(
    error && error.aegisCode || "NUTRITION_LOOKUP_FAILED",
    String(error && error.message || error),
    error && error.aegisRetryable === true,
    "NOT_STARTED",
    captureId
  );
}

function nutritionErrorV281_(code, message, retryable, writeState, captureId) {
  return {
    status: "error",
    capture_status: retryable ? "QUEUED" : "FAILED",
    contract: AEGIS_NUTRITION_CONTRACT_V281,
    reliability_contract: AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281,
    backend_version: "2.8.1",
    code: code,
    error: message,
    retryable: retryable === true,
    write_state: writeState,
    capture_id: captureId || null
  };
}

function validateAegisNutritionResultV281_(result, originalInput) {
  if (!result || !Array.isArray(result.items) || !result.items.length) {
    throw new Error("Nutrition result contained no food items.");
  }
  if (result.items.length > 30) throw new Error("Nutrition result exceeded the 30-item safety limit.");

  var items = result.items.map(function(raw) {
    var item = {
      item: clipNutritionTextV281_(raw.item || originalInput, 300),
      portion: clipNutritionTextV281_(raw.portion || "1 serving", 160),
      calories: requiredNutritionNumberV281_(raw.calories, "calories", 0, 10000),
      protein: optionalNutritionNumberV281_(raw.protein, 0, 1000),
      carbs: optionalNutritionNumberV281_(raw.carbs, 0, 2000),
      fat: optionalNutritionNumberV281_(raw.fat, 0, 1000),
      saturated_fat: optionalNutritionNumberV281_(raw.saturated_fat, 0, 1000),
      fiber: optionalNutritionNumberV281_(raw.fiber, 0, 500),
      sugar: optionalNutritionNumberV281_(raw.sugar, 0, 1000),
      sodium: optionalNutritionNumberV281_(raw.sodium, 0, 100000),
      cholesterol: optionalNutritionNumberV281_(raw.cholesterol, 0, 10000),
      source_type: normalizeNutritionEnumV281_(raw.source_type, ["OFFICIAL", "USDA", "OPEN_FOOD_FACTS", "COMPONENT_ESTIMATE", "MODEL_ESTIMATE"], "MODEL_ESTIMATE"),
      source_url: normalizeNutritionUrlV281_(raw.source_url),
      confidence: normalizeNutritionEnumV281_(raw.confidence, ["HIGH", "MEDIUM", "MEDIUM_LOW", "LOW"], "LOW"),
      lookup_depth: Math.max(1, Math.min(5, Math.round(Number(raw.lookup_depth) || Number(result.lookup_depth) || 5))),
      assumptions: clipNutritionTextV281_(raw.assumptions || "", 600),
      conservative_adjustment: raw.conservative_adjustment === true
    };
    if (!item.item) throw new Error("A nutrition item was missing its name.");
    return item;
  });

  return {
    items: items,
    confidence: normalizeNutritionEnumV281_(result.overall_confidence, ["HIGH", "MEDIUM", "MEDIUM_LOW", "LOW"], items[0].confidence),
    lookup_depth: Math.max.apply(null, items.map(function(item) { return item.lookup_depth; }))
  };
}

function requiredNutritionNumberV281_(value, name, min, max) {
  var number = Number(value);
  if (!isFinite(number) || number < min || number > max) {
    throw new Error("Nutrition " + name + " was missing or outside its safety range.");
  }
  return Math.round(number * 10) / 10;
}

function optionalNutritionNumberV281_(value, min, max) {
  if (value === null || typeof value === "undefined" || value === "") return null;
  var number = Number(value);
  if (!isFinite(number) || number < min || number > max) return null;
  return Math.round(number * 10) / 10;
}

function normalizeNutritionEnumV281_(value, allowed, fallback) {
  var normalized = String(value || "").trim().toUpperCase();
  return allowed.indexOf(normalized) >= 0 ? normalized : fallback;
}

function normalizeNutritionUrlV281_(value) {
  var url = String(value || "").trim();
  return /^https:\/\//i.test(url) ? url.slice(0, 2000) : "";
}

function clipNutritionTextV281_(value, max) {
  var text = String(value == null ? "" : value).replace(/\s+/g, " ").trim();
  return text.slice(0, max);
}

function ensureAegisNutritionHeadersV281_(sheet) {
  var width = Math.max(sheet.getLastColumn(), AEGIS_NUTRITION_HEADERS_V281.length);
  var existing = sheet.getRange(1, 1, 1, width).getDisplayValues()[0];
  var changed = false;
  for (var i = 0; i < AEGIS_NUTRITION_HEADERS_V281.length; i++) {
    if (!String(existing[i] || "").trim()) {
      existing[i] = AEGIS_NUTRITION_HEADERS_V281[i];
      changed = true;
    } else if (i < 10) {
      // A:J are legacy-owned. Never rename or reorder them.
      continue;
    } else if (String(existing[i]).trim() !== AEGIS_NUTRITION_HEADERS_V281[i]) {
      throw new Error("Nutrition extension column " + (i + 1) + " is already owned by '" + existing[i] + "'.");
    }
  }
  if (changed) sheet.getRange(1, 1, 1, existing.length).setValues([existing]);
}

function appendAegisNutritionRowsV281_(sheet, items, captureId) {
  var now = new Date();
  var date = Utilities.formatDate(now, CONFIG.TIMEZONE, "M/d/yyyy");
  var time = Utilities.formatDate(now, CONFIG.TIMEZONE, "h:mm:ss a");
  var renderedItems = typeof normalizeAegisNutritionItemsForSheetV2120_ === "function"
    ? normalizeAegisNutritionItemsForSheetV2120_(items)
    : items;
  var rows = renderedItems.map(function(item) {
    return [
      date, time, item.item, item.portion, item.calories,
      item.protein, item.carbs, item.fat, item.sodium,
      "Logged via AEGIS Nutrition V2",
      item.saturated_fat, item.fiber, item.sugar, item.cholesterol,
      item.source_type, item.source_url, item.confidence, item.lookup_depth,
      item.assumptions, item.conservative_adjustment,
      captureId, "CONFIRMED", "2.8.1", now.toISOString()
    ];
  });
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, AEGIS_NUTRITION_HEADERS_V281.length).setValues(rows);
  if (typeof formatAegisNutritionSheetV2120_ === "function") {
    formatAegisNutritionSheetV2120_(sheet);
  }
}

function findAegisNutritionCaptureV281_(sheet, captureId) {
  if (!captureId || sheet.getLastRow() < 2 || sheet.getLastColumn() < 21) return null;
  var finder = sheet.getRange(2, 21, sheet.getLastRow() - 1, 1)
    .createTextFinder(captureId)
    .matchEntireCell(true)
    .findNext();
  if (!finder) return null;
  var row = sheet.getRange(finder.getRow(), 1, 1, Math.max(24, sheet.getLastColumn())).getValues()[0];
  return {
    status: "success",
    capture_status: "CONFIRMED",
    contract: AEGIS_NUTRITION_CONTRACT_V281,
    reliability_contract: AEGIS_CAPTURE_RELIABILITY_CONTRACT_V281,
    backend_version: String(row[22] || "2.8.1"),
    capture_id: captureId,
    result: "Nutrition capture was already confirmed; no duplicate row was written.",
    items: [{
      item: row[2], portion: row[3], calories: Number(row[4]) || 0,
      protein: nullableSheetNumberV281_(row[5]), carbs: nullableSheetNumberV281_(row[6]),
      fat: nullableSheetNumberV281_(row[7]), sodium: nullableSheetNumberV281_(row[8]),
      saturated_fat: nullableSheetNumberV281_(row[10]), fiber: nullableSheetNumberV281_(row[11]),
      sugar: nullableSheetNumberV281_(row[12]), cholesterol: nullableSheetNumberV281_(row[13]),
      source_type: row[14], source_url: row[15], confidence: row[16],
      lookup_depth: Number(row[17]) || null, assumptions: row[18],
      conservative_adjustment: row[19] === true
    }]
  };
}

function nullableSheetNumberV281_(value) {
  return value === "" || value === null ? null : Number(value);
}

function sumAegisNutritionItemsV281_(items) {
  var keys = ["calories", "protein", "carbs", "fat", "saturated_fat", "fiber", "sugar", "sodium", "cholesterol"];
  var totals = {};
  keys.forEach(function(key) {
    var observed = items.filter(function(item) { return item[key] !== null; });
    totals[key] = observed.length ? Math.round(observed.reduce(function(sum, item) { return sum + Number(item[key]); }, 0) * 10) / 10 : null;
  });
  return totals;
}

function renderAegisNutritionSummaryV281_(items) {
  var lines = items.map(function(item) {
    return "• " + item.item + " (" + item.portion + "): " + item.calories +
      " kcal | " + displayNutritionNumberV281_(item.protein) + "g P | " +
      displayNutritionNumberV281_(item.carbs) + "g C | " +
      displayNutritionNumberV281_(item.fat) + "g F | " +
      displayNutritionNumberV281_(item.saturated_fat) + "g sat | " +
      displayNutritionNumberV281_(item.fiber) + "g fiber | " +
      displayNutritionNumberV281_(item.sugar) + "g sugar | " +
      displayNutritionNumberV281_(item.sodium) + "mg sodium | " +
      displayNutritionNumberV281_(item.cholesterol) + "mg cholesterol" +
      "\n  Source: " + item.source_type.replace(/_/g, " ") +
      " • Confidence: " + item.confidence.replace(/_/g, " ") +
      " • Lookup depth: " + item.lookup_depth +
      (item.conservative_adjustment ? " • Conservatively adjusted" : "");
  });
  return "✅ LOGGED NUTRITION VIA AEGIS:\n" + lines.join("\n");
}

function displayNutritionNumberV281_(value) {
  return value === null ? "—" : String(value);
}

function testAegisNutritionValidationV281() {
  var result = validateAegisNutritionResultV281_({
    overall_confidence: "MEDIUM_LOW",
    lookup_depth: 5,
    items: [{
      item: "Black & Blue Burger",
      portion: "1 burger",
      calories: 850,
      protein: 44,
      carbs: 44,
      fat: 55,
      saturated_fat: 22,
      fiber: 2,
      sugar: 7,
      sodium: 1550,
      cholesterol: 150,
      source_type: "COMPONENT_ESTIMATE",
      source_url: "https://www.williamsgk.com/",
      confidence: "MEDIUM_LOW",
      lookup_depth: 5,
      assumptions: "Burger only; approximately 6 oz raw patty; no side or drink.",
      conservative_adjustment: true
    }]
  }, "Black and Bleu burger from Williams Gourmet Kitchen");
  if (result.items[0].calories !== 850 || result.lookup_depth !== 5) {
    throw new Error("Nutrition validation regression failed.");
  }
  Logger.log(JSON.stringify(result, null, 2));
  return { status: "PASS", contract: AEGIS_NUTRITION_CONTRACT_V281, result: result };
}

function testAegisNutritionInputV281() {
  var cases = [
    ["/calories eggs, toast, coffee", "eggs, toast, coffee"],
    ["/calories Noodles & Company Buffalo Chicken Mac regular, 2 potstickers", "Noodles & Company Buffalo Chicken Mac regular, 2 potstickers"],
    ["/calories\r\nprotein drink\r\nbanana", "protein drink\nbanana"]
  ];
  var results = cases.map(function(test) {
    var actual = normalizeAegisNutritionInputV281_(test[0]);
    return { input: test[0], expected: test[1], actual: actual, pass: actual === test[1] };
  });
  if (!results.every(function(item) { return item.pass; })) {
    throw new Error("Nutrition input normalization regression failed.");
  }
  Logger.log(JSON.stringify(results, null, 2));
  return { status: "PASS", results: results };
}
