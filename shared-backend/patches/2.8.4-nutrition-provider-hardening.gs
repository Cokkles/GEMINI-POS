/**
 * AEGIS shared backend 2.8.4 -- tiered nutrition provider reliability.
 *
 * Install beside Code.gs, NutritionReliability281.gs,
 * NutritionQueue282.gs, and DeviceSessions283.gs. The durable 2.8.2 queue
 * delegates nutrition resolution to this module; client routes and the
 * legacy /calories behavior remain unchanged.
 */

var AEGIS_NUTRITION_PROVIDER_CONTRACT_V284 = "AEGIS_NUTRITION_PROVIDER_ROUTING_V1";
var AEGIS_NUTRITION_SIMPLE_MODEL_DEFAULT_V284 = "gemini-3.5-flash-lite";
var AEGIS_NUTRITION_CIRCUIT_THRESHOLD_V284 = 2;
var AEGIS_NUTRITION_CIRCUIT_DEFAULT_MS_V284 = 15 * 60 * 1000;
var AEGIS_NUTRITION_CIRCUIT_MAX_MS_V284 = 12 * 60 * 60 * 1000;

function resolveAegisNutritionV284_(foodText) {
  var known = tryKnownFoodNutritionV284_(foodText);
  if (known) return known;

  var deterministic = tryDeterministicNutritionV282_(foodText);
  if (deterministic) return deterministic;

  if (!requiresGroundedNutritionV284_(foodText)) {
    return callGeminiNutritionV284_(foodText, {
      lane: "SIMPLE",
      model: getAegisNutritionSimpleModelV284_(),
      grounded: false
    });
  }

  try {
    return callGeminiNutritionV284_(foodText, {
      lane: "GROUNDED",
      model: getAegisNutritionGroundedModelV284_(),
      grounded: true
    });
  } catch (groundedError) {
    if (!isGeminiCapacityErrorV284_(groundedError)) throw groundedError;
    try {
      var degraded = callGeminiNutritionV284_(foodText, {
        lane: "SIMPLE",
        model: getAegisNutritionSimpleModelV284_(),
        grounded: false
      });
      return markAegisNutritionDegradedV284_(degraded);
    } catch (simpleError) {
      if (isGeminiCapacityErrorV284_(simpleError)) {
        simpleError.retryAfterMs = Math.max(
          Number(simpleError.retryAfterMs) || 0,
          Number(groundedError.retryAfterMs) || 0
        );
        simpleError.message = String(simpleError.message || simpleError) +
          " Grounded lookup also failed: " +
          String(groundedError.aegisCode || "GEMINI_GROUNDED_UNAVAILABLE") + ".";
      }
      throw simpleError;
    }
  }
}

function getAegisNutritionSimpleModelV284_() {
  var props = PropertiesService.getScriptProperties();
  return String(
    props.getProperty("AEGIS_NUTRITION_SIMPLE_MODEL") ||
    AEGIS_NUTRITION_SIMPLE_MODEL_DEFAULT_V284
  ).trim();
}

function getAegisNutritionGroundedModelV284_() {
  var props = PropertiesService.getScriptProperties();
  return String(
    props.getProperty("AEGIS_NUTRITION_GROUNDED_MODEL") ||
    props.getProperty("AEGIS_NUTRITION_MODEL") ||
    props.getProperty("GEMINI_MODEL") ||
    AEGIS_NUTRITION_MODEL_DEFAULT_V281
  ).trim();
}

function requiresGroundedNutritionV284_(foodText) {
  var text = String(foodText || "").toLowerCase();
  return /\b(restaurant|menu|cafe|café|diner|bistro|grill|kitchen|tavern|eatery)\b/.test(text) ||
    /\b(mcdonald'?s|burger king|wendy'?s|taco bell|chipotle|subway|panera|starbucks|chick-fil-a|popeyes|kfc|five guys|shake shack|olive garden|applebee'?s|chili'?s|buffalo wild wings|noodles?\s*(?:&|and)\s*(?:co|company)|williams gourmet kitchen)\b/.test(text);
}

function callGeminiNutritionV284_(foodText, options) {
  var lane = String(options && options.lane || "SIMPLE").toUpperCase();
  var grounded = options && options.grounded === true;
  var model = String(options && options.model || "").trim();
  var circuitDelay = getAegisNutritionCircuitDelayV284_(lane);
  if (circuitDelay > 0) {
    var circuitError = taggedNutritionErrorV281_(
      "GEMINI_" + lane + "_CIRCUIT_OPEN",
      "Gemini " + lane.toLowerCase() + " requests are paused after repeated capacity failures; the capture remains safely queued.",
      true
    );
    circuitError.retryAfterMs = circuitDelay;
    circuitError.aegisCapacity = true;
    throw circuitError;
  }

  var cfg = getGeminiConfig();
  var url = "https://generativelanguage.googleapis.com/v1beta/models/" +
    encodeURIComponent(model) + ":generateContent";
  var payload = {
    contents: [{
      role: "user",
      parts: [{
        text: grounded
          ? buildAegisNutritionPromptV281_(foodText)
          : buildAegisNutritionSimplePromptV284_(foodText)
      }]
    }],
    generationConfig: {
      temperature: 0.15,
      responseMimeType: "application/json"
    }
  };
  if (grounded) payload.tools = [{ google_search: {} }];

  var response;
  try {
    response = UrlFetchApp.fetch(url, {
      method: "post",
      contentType: "application/json",
      headers: { "x-goog-api-key": cfg.apiKey },
      payload: JSON.stringify(payload),
      muteHttpExceptions: true
    });
  } catch (transportError) {
    var transport = taggedNutritionErrorV281_(
      "GEMINI_" + lane + "_TRANSPORT",
      String(transportError && transportError.message || transportError),
      true
    );
    transport.aegisCapacity = true;
    throw transport;
  }

  var code = response.getResponseCode();
  var body = response.getContentText();
  if (code === 429 || code === 503) {
    var detail = parseGeminiCapacityFailureV284_(code, body, response, lane);
    registerAegisNutritionCapacityFailureV284_(lane, detail.retryAfterMs, detail.code);
    var capacityError = taggedNutritionErrorV281_(detail.code, detail.message, true);
    capacityError.retryAfterMs = detail.retryAfterMs;
    capacityError.aegisCapacity = true;
    capacityError.providerLane = lane;
    capacityError.httpStatus = code;
    throw capacityError;
  }
  if (code < 200 || code >= 300) {
    throw taggedNutritionErrorV281_(
      "GEMINI_" + lane + "_HTTP_" + code,
      "Gemini " + lane.toLowerCase() + " nutrition lookup failed with HTTP " +
        code + ". " + compactGeminiProviderDetailV284_(body),
      false
    );
  }

  resetAegisNutritionCircuitV284_(lane);
  return parseGeminiNutritionEnvelopeV284_(body, lane);
}

function buildAegisNutritionSimplePromptV284_(foodText) {
  return [
    "You are the KINETIC conservative nutrition estimation engine for AEGIS.",
    "Interpret the user's food description and portion without external tools or web search.",
    "Use generally established food composition knowledge and ingredient-level reconstruction.",
    "Do not claim an official, USDA, or manufacturer lookup occurred.",
    "Use source_type COMPONENT_ESTIMATE or MODEL_ESTIMATE and leave source_url empty.",
    "Prefer a modest conservative overestimate when uncertainty remains.",
    "Never use zero for an unknown nutrient; estimate it or use null.",
    "Return ONLY one JSON object using this exact structure:",
    '{"items":[{"item":"specific name","portion":"estimated or stated serving","calories":200,"protein":4,"carbs":30,"fat":8,"saturated_fat":2,"fiber":2,"sugar":12,"sodium":200,"cholesterol":0,"source_type":"COMPONENT_ESTIMATE|MODEL_ESTIMATE","source_url":"","confidence":"MEDIUM_LOW|LOW","lookup_depth":4,"assumptions":"brief explicit assumptions","conservative_adjustment":true}],"overall_confidence":"MEDIUM_LOW","lookup_depth":4}',
    "All numeric nutrients are per returned portion. Calories are kcal; protein/carbs/fat/saturated_fat/fiber/sugar are grams; sodium/cholesterol are milligrams.",
    "USER FOOD DESCRIPTION:",
    foodText
  ].join("\n");
}

function parseGeminiNutritionEnvelopeV284_(body, lane) {
  var envelope;
  try {
    envelope = JSON.parse(body);
  } catch (parseEnvelopeError) {
    throw taggedNutritionErrorV281_(
      "GEMINI_" + lane + "_ENVELOPE_INVALID",
      "Gemini returned an invalid response envelope.",
      false
    );
  }
  var parts = envelope.candidates && envelope.candidates[0] &&
    envelope.candidates[0].content && envelope.candidates[0].content.parts;
  var raw = (parts || []).map(function(part) { return part.text || ""; }).join("").trim();
  if (!raw) {
    throw taggedNutritionErrorV281_(
      "GEMINI_" + lane + "_EMPTY_RESULT",
      "Gemini returned no nutrition result.",
      false
    );
  }
  try {
    return JSON.parse(raw.replace(/^\`\`\`json\s*/i, "").replace(/\`\`\`\s*$/i, "").trim());
  } catch (parseResultError) {
    throw taggedNutritionErrorV281_(
      "GEMINI_" + lane + "_JSON_INVALID",
      "Gemini returned nutrition data in an invalid format.",
      false
    );
  }
}

function parseGeminiCapacityFailureV284_(httpCode, body, response, lane) {
  var raw = String(body || "");
  var lower = raw.toLowerCase();
  var code = httpCode === 503 ? "GEMINI_HIGH_VOLUME" : "GEMINI_RATE_LIMITED";
  if (/google.?search|grounding|grounded/.test(lower)) {
    code = "GEMINI_SEARCH_QUOTA";
  } else if (/requests?.?per.?day|per day|daily|rpd|generate_content.*day/.test(lower)) {
    code = "GEMINI_DAILY_QUOTA";
  } else if (/tokens?.?per.?minute|tpm/.test(lower)) {
    code = "GEMINI_TPM_LIMITED";
  } else if (/requests?.?per.?minute|rpm/.test(lower)) {
    code = "GEMINI_RPM_LIMITED";
  }
  var retryAfterMs = extractGeminiRetryAfterMsV284_(body, response);
  if (!retryAfterMs) {
    retryAfterMs = code === "GEMINI_DAILY_QUOTA"
      ? 6 * 60 * 60 * 1000
      : AEGIS_NUTRITION_CIRCUIT_DEFAULT_MS_V284;
  }
  retryAfterMs = Math.max(60 * 1000, Math.min(
    AEGIS_NUTRITION_CIRCUIT_MAX_MS_V284,
    retryAfterMs
  ));
  var laneName = String(lane || "Gemini").toLowerCase();
  return {
    code: code,
    retryAfterMs: retryAfterMs,
    message: "Gemini " + laneName + " provider returned HTTP " + httpCode +
      "; retry scheduled in approximately " +
      Math.ceil(retryAfterMs / 60000) + " minute(s). Provider detail: " +
      compactGeminiProviderDetailV284_(body)
  };
}

function extractGeminiRetryAfterMsV284_(body, response) {
  var headerValue = "";
  try {
    var headers = response && response.getAllHeaders
      ? response.getAllHeaders()
      : response && response.getHeaders
        ? response.getHeaders()
        : {};
    Object.keys(headers || {}).some(function(key) {
      if (String(key).toLowerCase() !== "retry-after") return false;
      headerValue = Array.isArray(headers[key]) ? headers[key][0] : headers[key];
      return true;
    });
  } catch (ignoredHeaders) {}
  var seconds = Number(headerValue);
  if (isFinite(seconds) && seconds > 0) return Math.ceil(seconds * 1000);
  if (headerValue) {
    var headerDate = new Date(headerValue);
    if (!isNaN(headerDate.getTime())) return Math.max(0, headerDate.getTime() - Date.now());
  }

  var text = String(body || "");
  var duration = text.match(/"retryDelay"\s*:\s*"([0-9]+(?:\.[0-9]+)?)s"/i);
  if (!duration) duration = text.match(/retry\s+(?:in|after)\s+([0-9]+(?:\.[0-9]+)?)s/i);
  return duration ? Math.ceil(Number(duration[1]) * 1000) : 0;
}

function compactGeminiProviderDetailV284_(body) {
  var text = String(body || "").replace(/\s+/g, " ").trim();
  try {
    var parsed = JSON.parse(body);
    text = String(parsed && parsed.error && parsed.error.message || text);
  } catch (ignoredJson) {}
  return text.slice(0, 650) || "No provider detail returned.";
}

function isGeminiCapacityErrorV284_(error) {
  return !!(error && (
    error.aegisCapacity === true ||
    /^GEMINI_(?:RATE_LIMITED|HIGH_VOLUME|SEARCH_QUOTA|DAILY_QUOTA|TPM_LIMITED|RPM_LIMITED|SIMPLE_CIRCUIT_OPEN|GROUNDED_CIRCUIT_OPEN)$/.test(String(error.aegisCode || ""))
  ));
}

function circuitPropertyNameV284_(lane, suffix) {
  return "AEGIS_NUTRITION_CIRCUIT_" + String(lane || "SIMPLE").toUpperCase() + "_" + suffix;
}

function getAegisNutritionCircuitDelayV284_(lane) {
  var value = PropertiesService.getScriptProperties()
    .getProperty(circuitPropertyNameV284_(lane, "OPEN_UNTIL"));
  var until = Number(value) || 0;
  if (until <= Date.now()) return 0;
  return until - Date.now();
}

function registerAegisNutritionCapacityFailureV284_(lane, retryAfterMs, code) {
  var props = PropertiesService.getScriptProperties();
  var failuresKey = circuitPropertyNameV284_(lane, "FAILURES");
  var failures = Math.max(0, Number(props.getProperty(failuresKey)) || 0) + 1;
  props.setProperty(failuresKey, String(failures));
  props.setProperty(circuitPropertyNameV284_(lane, "LAST_CODE"), String(code || ""));
  if (failures >= AEGIS_NUTRITION_CIRCUIT_THRESHOLD_V284) {
    props.setProperty(
      circuitPropertyNameV284_(lane, "OPEN_UNTIL"),
      String(Date.now() + Math.max(
        AEGIS_NUTRITION_CIRCUIT_DEFAULT_MS_V284,
        Number(retryAfterMs) || 0
      ))
    );
  }
}

function resetAegisNutritionCircuitV284_(lane) {
  var props = PropertiesService.getScriptProperties();
  props.deleteProperty(circuitPropertyNameV284_(lane, "FAILURES"));
  props.deleteProperty(circuitPropertyNameV284_(lane, "OPEN_UNTIL"));
  props.deleteProperty(circuitPropertyNameV284_(lane, "LAST_CODE"));
}

function markAegisNutritionDegradedV284_(result) {
  var confidenceOrder = { HIGH: 4, MEDIUM: 3, MEDIUM_LOW: 2, LOW: 1 };
  (result.items || []).forEach(function(item) {
    item.source_type = item.source_type === "COMPONENT_ESTIMATE"
      ? "COMPONENT_ESTIMATE"
      : "MODEL_ESTIMATE";
    item.source_url = "";
    if ((confidenceOrder[String(item.confidence || "LOW")] || 1) > 2) {
      item.confidence = "MEDIUM_LOW";
    }
    item.assumptions = [
      String(item.assumptions || "").trim(),
      "Grounded menu lookup was unavailable; returned a conservative ungrounded estimate."
    ].filter(function(value) { return value; }).join(" ");
    item.conservative_adjustment = true;
  });
  result.confidence = "MEDIUM_LOW";
  result.overall_confidence = "MEDIUM_LOW";
  return result;
}

function tryKnownFoodNutritionV284_(foodText) {
  var text = String(foodText || "").trim();
  var normalized = text.toLowerCase();
  if (!/\bfig\s+newtons?\b/.test(normalized)) return null;

  var grams = parseNutritionWeightGramsV284_(normalized);
  var countMatch = normalized.match(/\b([0-9]+(?:\.[0-9]+)?)\s*(?:cookies?|bars?)\b/);
  if (!grams && countMatch) grams = Number(countMatch[1]) * 14.5;
  if (!grams) grams = 29;
  var factor = grams / 29;
  var item = deterministicScaleV282_(
    "Fig Newton cookies",
    Math.round(grams * 10) / 10 + " g",
    factor,
    [100, 1, 21, 2, 0, 1, 12, 95, 0],
    "Matched a common packaged-food label reference; verify the package if flavor or formulation differs."
  );
  item.source_type = "OFFICIAL";
  item.source_url = "https://www.snackworks.com/";
  item.confidence = "MEDIUM";
  item.lookup_depth = 2;
  item.conservative_adjustment = true;
  return { items: [item], confidence: "MEDIUM", lookup_depth: 2 };
}

function parseNutritionWeightGramsV284_(text) {
  var match = String(text || "").match(/\b([0-9]+(?:\.[0-9]+)?)\s*(oz|ounce|ounces|g|gram|grams)\b/);
  if (!match) return null;
  var amount = Number(match[1]);
  return /^o/.test(match[2]) ? amount * 28.349523125 : amount;
}

function getAegisNutritionProviderHealthV284_() {
  return {
    status: "success",
    backend_version: "2.8.4",
    contract: AEGIS_NUTRITION_PROVIDER_CONTRACT_V284,
    simple_model: getAegisNutritionSimpleModelV284_(),
    grounded_model: getAegisNutritionGroundedModelV284_(),
    simple_circuit_retry_after_ms: getAegisNutritionCircuitDelayV284_("SIMPLE"),
    grounded_circuit_retry_after_ms: getAegisNutritionCircuitDelayV284_("GROUNDED")
  };
}

function resetAegisNutritionProviderCircuitsV284() {
  resetAegisNutritionCircuitV284_("SIMPLE");
  resetAegisNutritionCircuitV284_("GROUNDED");
  var result = getAegisNutritionProviderHealthV284_();
  result.status = "PASS";
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function testAegisNutritionProviderRoutingV284() {
  var known = tryKnownFoodNutritionV284_("2oz Fig Newton");
  var quota = parseGeminiCapacityFailureV284_(
    429,
    JSON.stringify({
      error: {
        message: "Quota exceeded for requests per minute. Please retry in 16.639s.",
        details: [{ retryDelay: "16.639s" }]
      }
    }),
    null,
    "SIMPLE"
  );
  if (!known || known.items[0].calories <= 0) {
    throw new Error("2.8.4 known-food resolution failed.");
  }
  if (requiresGroundedNutritionV284_("2 eggs and toast")) {
    throw new Error("2.8.4 simple-food routing failed.");
  }
  if (!requiresGroundedNutritionV284_("Noodles & Company Buffalo Chicken Mac")) {
    throw new Error("2.8.4 restaurant routing failed.");
  }
  if (quota.code !== "GEMINI_RPM_LIMITED" || quota.retryAfterMs !== 60000) {
    throw new Error("2.8.4 quota parsing failed.");
  }
  var result = {
    status: "PASS",
    known_food_calories: known.items[0].calories,
    simple_model: getAegisNutritionSimpleModelV284_(),
    grounded_model: getAegisNutritionGroundedModelV284_(),
    parsed_quota_code: quota.code,
    parsed_retry_after_ms: quota.retryAfterMs
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function testAegisNutritionSimpleProviderV284() {
  var result = callGeminiNutritionV284_("one medium plain baked potato", {
    lane: "SIMPLE",
    model: getAegisNutritionSimpleModelV284_(),
    grounded: false
  });
  var validated = validateAegisNutritionResultV282_(result, "one medium plain baked potato");
  var output = {
    status: "PASS",
    model: getAegisNutritionSimpleModelV284_(),
    grounded: false,
    items: validated.items.length,
    calories: validated.items[0].calories
  };
  Logger.log(JSON.stringify(output, null, 2));
  return output;
}
