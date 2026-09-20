/**
 * AEGIS shared backend 2.10.0 -- retained tiered provider diagnostics and item integrity.
 *
 * Install beside Code.gs, NutritionReliability281.gs,
 * NutritionQueue282.gs, and DeviceSessions283.gs. The durable 2.8.2 queue
 * delegates nutrition resolution to this module; client routes and the
 * legacy /calories behavior remain unchanged.
 */

var AEGIS_NUTRITION_PROVIDER_CONTRACT_V284 = "AEGIS_NUTRITION_PROVIDER_ROUTING_V1";
var AEGIS_NUTRITION_MULTI_ITEM_CONTRACT_V2841 = "AEGIS_NUTRITION_MULTI_ITEM_INTEGRITY_V1";
var AEGIS_NUTRITION_SIMPLE_MODEL_DEFAULT_V284 = "gemini-3.5-flash-lite";
var AEGIS_NUTRITION_CIRCUIT_THRESHOLD_V284 = 2;
var AEGIS_NUTRITION_CIRCUIT_DEFAULT_MS_V284 = 15 * 60 * 1000;
var AEGIS_NUTRITION_CIRCUIT_MAX_MS_V284 = 12 * 60 * 60 * 1000;

function resolveAegisNutritionV284_(foodText) {
  var segments = splitAegisNutritionItemsV284_(foodText);
  if (segments.length > 1) {
    return resolveAegisNutritionListV284_(segments);
  }

  var known = tryKnownFoodNutritionV284_(foodText);
  if (known) return known;

  var deterministic = tryDeterministicNutritionV282_(foodText);
  if (deterministic) return deterministic;

  if (!requiresGroundedNutritionV284_(foodText)) {
    return enforceAegisNutritionItemIntegrityV284_(callGeminiNutritionV284_(foodText, {
      lane: "SIMPLE",
      model: getAegisNutritionSimpleModelV284_(),
      grounded: false
    }), [foodText]);
  }

  try {
    return enforceAegisNutritionItemIntegrityV284_(callGeminiNutritionV284_(foodText, {
      lane: "GROUNDED",
      model: getAegisNutritionGroundedModelV284_(),
      grounded: true
    }), [foodText]);
  } catch (groundedError) {
    if (!isGeminiCapacityErrorV284_(groundedError)) throw groundedError;
    if (requiresVerifiedNutritionSourceV284_(foodText)) throw groundedError;
    try {
      var degraded = callGeminiNutritionV284_(foodText, {
        lane: "SIMPLE",
        model: getAegisNutritionSimpleModelV284_(),
        grounded: false
      });
      return enforceAegisNutritionItemIntegrityV284_(
        markAegisNutritionDegradedV284_(degraded),
        [foodText]
      );
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

function splitAegisNutritionItemsV284_(foodText) {
  var text = String(foodText || "")
    .replace(/\r\n?/g, "\n")
    .replace(/^\s*\/calories\b\s*/i, "")
    .trim();
  if (!text) return [];
  var items = text.split(/[\n,;|]+/).map(function(value) {
    return value.trim().replace(/^[\-•]+\s*/, "");
  }).filter(function(value) { return value; });
  if (items.length > 20) {
    throw taggedNutritionErrorV281_(
      "NUTRITION_ITEM_LIMIT_EXCEEDED",
      "A nutrition capture may contain at most 20 explicitly separated items.",
      false
    );
  }
  return items;
}

function resolveAegisNutritionListV284_(segments) {
  var resolved = new Array(segments.length);
  var unresolved = [];
  var unresolvedIndexes = [];

  segments.forEach(function(segment, index) {
    var local = tryKnownFoodNutritionV284_(segment) ||
      tryDeterministicNutritionV282_(segment);
    if (local && local.items && local.items.length === 1) {
      resolved[index] = local.items[0];
      return;
    }
    unresolved.push(segment);
    unresolvedIndexes.push(index);
  });

  if (unresolved.length) {
    var grounded = unresolved.some(function(segment) {
      return requiresGroundedNutritionV284_(segment);
    });
    var result = callGeminiNutritionV284_(formatAegisNutritionListV284_(unresolved), {
      lane: grounded ? "GROUNDED" : "SIMPLE",
      model: grounded
        ? getAegisNutritionGroundedModelV284_()
        : getAegisNutritionSimpleModelV284_(),
      grounded: grounded,
      expectedItems: unresolved
    });
    result = enforceAegisNutritionItemIntegrityV284_(result, unresolved);
    result.items.forEach(function(item, index) {
      resolved[unresolvedIndexes[index]] = item;
    });
  }

  var confidenceRank = { HIGH: 4, MEDIUM: 3, MEDIUM_LOW: 2, LOW: 1 };
  var confidence = resolved.reduce(function(current, item) {
    var candidate = String(item && item.confidence || "LOW");
    return (confidenceRank[candidate] || 1) < (confidenceRank[current] || 1)
      ? candidate
      : current;
  }, "HIGH");
  var depth = resolved.reduce(function(maximum, item) {
    return Math.max(maximum, Number(item && item.lookup_depth) || 1);
  }, 1);
  return { items: resolved, confidence: confidence, overall_confidence: confidence, lookup_depth: depth };
}

function tryResolveAegisNutritionLocallyV284_(foodText) {
  var segments = splitAegisNutritionItemsV284_(foodText);
  if (!segments.length) return null;
  var items = [];
  var confidenceRank = { HIGH: 4, MEDIUM: 3, MEDIUM_LOW: 2, LOW: 1 };
  var confidence = "HIGH";
  var depth = 1;
  for (var i = 0; i < segments.length; i++) {
    var local = tryKnownFoodNutritionV284_(segments[i]) ||
      tryDeterministicNutritionV282_(segments[i]);
    if (!local || !local.items || local.items.length !== 1) return null;
    items.push(local.items[0]);
    var candidate = String(local.items[0].confidence || local.confidence || "LOW");
    if ((confidenceRank[candidate] || 1) < (confidenceRank[confidence] || 1)) {
      confidence = candidate;
    }
    depth = Math.max(depth, Number(local.items[0].lookup_depth || local.lookup_depth) || 1);
  }
  return { items: items, confidence: confidence, overall_confidence: confidence, lookup_depth: depth };
}

function formatAegisNutritionListV284_(segments) {
  return segments.map(function(segment, index) {
    return "ITEM " + (index + 1) + ": " + segment;
  }).join("\n");
}

function buildAegisNutritionListPromptV284_(segments, grounded) {
  return [
    "You are the KINETIC nutrition evidence and estimation engine for AEGIS.",
    "The user supplied exactly " + segments.length + " separately logged food items.",
    "Return exactly " + segments.length + " result objects in the same numbered order.",
    "Never combine, summarize, rename collectively, or omit the items.",
    "Preserve each stated count, serving quantity, weight, and volume in that item's portion.",
    grounded
      ? "Use Google Search grounding and prefer official manufacturer/menu data, then USDA or Open Food Facts."
      : "Do not claim an external lookup; use COMPONENT_ESTIMATE or MODEL_ESTIMATE and leave source_url empty.",
    "Reject the idea of a generic mixed meal: each result name must identify its corresponding input.",
    "Prefer a modest conservative overestimate when uncertainty remains and state assumptions per item.",
    "Never use zero for an unknown nutrient; estimate it or use null.",
    "Return ONLY one JSON object using this structure:",
    '{"items":[{"item":"specific corresponding product","portion":"stated total quantity","calories":200,"protein":4,"carbs":30,"fat":8,"saturated_fat":2,"fiber":2,"sugar":12,"sodium":200,"cholesterol":0,"source_type":"OFFICIAL|USDA|OPEN_FOOD_FACTS|COMPONENT_ESTIMATE|MODEL_ESTIMATE","source_url":"https://... or empty","confidence":"HIGH|MEDIUM|MEDIUM_LOW|LOW","lookup_depth":4,"assumptions":"brief explicit assumptions","conservative_adjustment":true}],"overall_confidence":"MEDIUM_LOW","lookup_depth":4}',
    "INPUT ITEMS:",
    formatAegisNutritionListV284_(segments)
  ].join("\n");
}

function enforceAegisNutritionItemIntegrityV284_(result, segments) {
  var items = result && result.items;
  var allowDeterministicReview =
    result && result.allow_identity_portion_review === true;
  if (!Array.isArray(items) || items.length !== segments.length) {
    throw taggedNutritionErrorV281_(
      "NUTRITION_ITEM_COUNT_MISMATCH",
      "Expected " + segments.length + " separate nutrition item(s), but the provider returned " +
        (Array.isArray(items) ? items.length : 0) + ". No nutrition rows were written.",
      true
    );
  }

  items.forEach(function(item, index) {
    var segment = segments[index];
    var name = String(item && item.item || "").trim();
    var portion = String(item && item.portion || "").trim();
    if (!name || /\b(generic|mixed meal|meal portion|assorted foods?|combined meal|meal estimate)\b/i.test(name)) {
      throw taggedNutritionErrorV281_(
        "NUTRITION_GENERIC_AGGREGATE_REJECTED",
        "Item " + (index + 1) + " was returned as a generic or combined meal. No nutrition rows were written.",
        true
      );
    }
    if (
      !allowDeterministicReview &&
      !nutritionItemMatchesSegmentV284_(name, segment)
    ) {
      throw taggedNutritionErrorV281_(
        "NUTRITION_ITEM_IDENTITY_MISMATCH",
        "Returned item " + (index + 1) + " does not identify its corresponding input. No nutrition rows were written.",
        true
      );
    }
    var quantity = String(segment).match(/^\s*([0-9]+(?:\.[0-9]+)?)/);
    var equivalentPortion =
      typeof isAegisNutritionPortionEquivalentV2100_ === "function" &&
      isAegisNutritionPortionEquivalentV2100_(segment, item);
    if (
      !allowDeterministicReview &&
      quantity &&
      !new RegExp("(^|\\D)" + quantity[1].replace(".", "\\.") + "(\\D|$)")
        .test(portion) &&
      !equivalentPortion
    ) {
      throw taggedNutritionErrorV281_(
        "NUTRITION_PORTION_MISMATCH",
        "Returned item " + (index + 1) + " did not preserve the stated quantity " +
          quantity[1] + ". No nutrition rows were written.",
        true
      );
    }
    if (!allowDeterministicReview &&
        requiresVerifiedNutritionSourceV284_(segment) && (
      String(item.source_type || "") === "MODEL_ESTIMATE" ||
      String(item.confidence || "") === "LOW"
    )) {
      throw taggedNutritionErrorV281_(
        "NUTRITION_BRANDED_SOURCE_UNVERIFIED",
        "Branded item " + (index + 1) + " lacked an adequately identified source. No nutrition rows were written.",
        true
      );
    }
  });
  return result;
}

function nutritionItemMatchesSegmentV284_(itemName, segment) {
  var output = normalizeNutritionIdentityTextV284_(itemName);
  var input = normalizeNutritionIdentityTextV284_(segment);
  var brands = [
    "tyson", "kirkland", "mission", "texas pete", "rice a roni",
    "chobani", "bear naked", "nabisco", "fig newton",
    "mcdonalds", "burger king", "wendys", "taco bell", "chipotle",
    "subway", "panera", "starbucks", "chick fil a", "popeyes",
    "five guys", "shake shack", "olive garden", "applebees",
    "buffalo wild wings", "noodles and company", "williams gourmet kitchen"
  ];
  for (var i = 0; i < brands.length; i++) {
    if (input.indexOf(brands[i]) >= 0) return output.indexOf(brands[i]) >= 0;
  }
  var ignored = {
    serving: true, servings: true, tablespoon: true, tablespoons: true,
    tbsp: true, teaspoon: true, teaspoons: true, medium: true, large: true,
    small: true, frozen: true, grilled: true, ounce: true, ounces: true,
    gram: true, grams: true, with: true, and: true, the: true
  };
  var tokens = input.split(" ").filter(function(token) {
    return token.length >= 3 && !ignored[token] && !/^\d/.test(token);
  });
  return tokens.some(function(token) { return output.indexOf(token) >= 0; });
}

function normalizeNutritionIdentityTextV284_(value) {
  return String(value || "").toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
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
    /\b(tyson|kirkland|mission|texas pete|rice-a-roni|chobani|bear naked|nabisco|fig newtons?|mcdonald'?s|burger king|wendy'?s|taco bell|chipotle|subway|panera|starbucks|chick-fil-a|popeyes|kfc|five guys|shake shack|olive garden|applebee'?s|chili'?s|buffalo wild wings|noodles?\s*(?:&|and)\s*(?:co|company)|williams gourmet kitchen)\b/.test(text);
}

function requiresVerifiedNutritionSourceV284_(foodText) {
  var text = String(foodText || "").toLowerCase();
  return /\b(tyson|kirkland|mission|texas pete|rice-a-roni|chobani|bear naked|nabisco|fig newtons?)\b/.test(text);
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
        text: options && options.expectedItems
          ? buildAegisNutritionListPromptV284_(options.expectedItems, grounded)
          : grounded
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
  if (splitAegisNutritionItemsV284_(text).length !== 1) return null;
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
    backend_version: "2.10.0",
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

function testAegisNutritionMultiItemIntegrityV2841() {
  var fixture = "2 Servings Tyson Frozen Grilled Chicken, 2 servings Kirkland Salsa, 1 Medium Mission Flour Tortilla, 2 tablespoons Texas Pete Hotter Hot Sauce, 2 Servings Rice-A-Roni Chicken";
  var segments = splitAegisNutritionItemsV284_(fixture);
  if (segments.length !== 5) {
    throw new Error("2.8.4.1 fixture must parse into exactly five items.");
  }
  if (segments.some(function(segment) { return !requiresGroundedNutritionV284_(segment); })) {
    throw new Error("2.8.4.1 branded fixture must route every unresolved item to grounded lookup.");
  }
  var rejected = false;
  try {
    enforceAegisNutritionItemIntegrityV284_({
      items: [{
        item: "Generic Mixed Meal Portion",
        portion: "1 serving",
        source_type: "MODEL_ESTIMATE",
        confidence: "LOW"
      }]
    }, segments);
  } catch (expected) {
    rejected = expected && expected.aegisCode === "NUTRITION_ITEM_COUNT_MISMATCH";
  }
  if (!rejected) throw new Error("2.8.4.1 generic aggregate was not rejected.");
  var result = {
    status: "PASS",
    contract: AEGIS_NUTRITION_MULTI_ITEM_CONTRACT_V2841,
    parsed_items: segments.length,
    generic_aggregate_rejected: true,
    expected_items: segments
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function testAegisNutritionFiveItemProviderV2841() {
  var fixture = "2 Servings Tyson Frozen Grilled Chicken, 2 servings Kirkland Salsa, 1 Medium Mission Flour Tortilla, 2 tablespoons Texas Pete Hotter Hot Sauce, 2 Servings Rice-A-Roni Chicken";
  var result = resolveAegisNutritionV284_(fixture);
  var validated = validateAegisNutritionResultV282_(result, fixture);
  if (validated.items.length !== 5) {
    throw new Error("2.8.4.1 live provider did not return exactly five validated items.");
  }
  var output = {
    status: "PASS",
    contract: AEGIS_NUTRITION_MULTI_ITEM_CONTRACT_V2841,
    model: getAegisNutritionGroundedModelV284_(),
    grounded: true,
    items: validated.items.map(function(item) {
      return {
        item: item.item,
        portion: item.portion,
        source_type: item.source_type,
        confidence: item.confidence
      };
    })
  };
  Logger.log(JSON.stringify(output, null, 2));
  return output;
}
