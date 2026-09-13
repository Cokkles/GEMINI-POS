const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const source281 = fs.readFileSync(path.join(root, "patches", "2.8.1-nutrition-reliability.gs"), "utf8");
const source282 = fs.readFileSync(path.join(root, "patches", "2.8.2-durable-nutrition-queue.gs"), "utf8");
const source284 = fs.readFileSync(path.join(root, "patches", "2.8.4-nutrition-provider-hardening.gs"), "utf8");

const properties = new Map([["GEMINI_API_KEY", "test-key"]]);
const propertyApi = {
  getProperty: (key) => properties.get(key) || null,
  setProperty: (key, value) => properties.set(key, String(value)),
  deleteProperty: (key) => properties.delete(key),
};
const fetches = [];
let nextResponse = null;
const sandbox = {
  Array,
  Boolean,
  Date,
  Error,
  JSON,
  Math,
  Number,
  Object,
  RegExp,
  String,
  isFinite,
  encodeURIComponent,
  Logger: { log() {} },
  PropertiesService: { getScriptProperties: () => propertyApi },
  Utilities: {},
  UrlFetchApp: {
    fetch: (url, options) => {
      fetches.push({ url, options });
      assert.ok(nextResponse, "test must configure a provider response");
      return nextResponse;
    },
  },
  getGeminiConfig: () => ({ apiKey: "test-key", model: "gemini-3.6-flash" }),
};
vm.createContext(sandbox);
vm.runInContext(source281, sandbox, { filename: "NutritionReliability281.gs" });
vm.runInContext(source282, sandbox, { filename: "NutritionQueue282.gs" });
vm.runInContext(source284, sandbox, { filename: "NutritionProviderReliability284.gs" });

const figNewton = sandbox.tryKnownFoodNutritionV284_("2oz Fig Newton");
assert.equal(figNewton.items.length, 1);
assert.equal(figNewton.items[0].item, "Fig Newton cookies");
assert.ok(figNewton.items[0].calories >= 195);
assert.equal(fetches.length, 0, "known foods must not call Gemini");

assert.equal(sandbox.requiresGroundedNutritionV284_("2 eggs and toast"), false);
assert.equal(
  sandbox.requiresGroundedNutritionV284_("Noodles & Company Buffalo Chicken Mac regular"),
  true,
);

const rpm = sandbox.parseGeminiCapacityFailureV284_(
  429,
  JSON.stringify({ error: { message: "Quota exceeded for requests per minute. Retry in 16.639s." } }),
  null,
  "SIMPLE",
);
assert.equal(rpm.code, "GEMINI_RPM_LIMITED");
assert.equal(rpm.retryAfterMs, 60000);

const daily = sandbox.parseGeminiCapacityFailureV284_(
  429,
  JSON.stringify({ error: { message: "Quota exceeded for requests per day." } }),
  null,
  "SIMPLE",
);
assert.equal(daily.code, "GEMINI_DAILY_QUOTA");
assert.equal(daily.retryAfterMs, 6 * 60 * 60 * 1000);

const search = sandbox.parseGeminiCapacityFailureV284_(
  429,
  JSON.stringify({ error: { message: "Google Search grounding quota exceeded." } }),
  null,
  "GROUNDED",
);
assert.equal(search.code, "GEMINI_SEARCH_QUOTA");

const successfulEnvelope = JSON.stringify({
  candidates: [{
    content: {
      parts: [{ text: JSON.stringify({
        items: [{
          item: "Baked potato",
          portion: "1 medium",
          calories: 190,
          protein: 4,
          carbs: 43,
          fat: 0.2,
          saturated_fat: 0,
          fiber: 4,
          sugar: 2,
          sodium: 15,
          cholesterol: 0,
          source_type: "MODEL_ESTIMATE",
          source_url: "",
          confidence: "MEDIUM_LOW",
          lookup_depth: 4,
          assumptions: "Plain baked potato.",
          conservative_adjustment: true,
        }],
        overall_confidence: "MEDIUM_LOW",
        lookup_depth: 4,
      }) }],
    },
  }],
});
nextResponse = {
  getResponseCode: () => 200,
  getContentText: () => successfulEnvelope,
  getAllHeaders: () => ({}),
};
const simple = sandbox.resolveAegisNutritionV284_("one medium baked potato");
assert.equal(simple.items[0].calories, 190);
assert.match(fetches.at(-1).url, /gemini-3\.5-flash-lite/);
assert.equal(JSON.parse(fetches.at(-1).options.payload).tools, undefined);

fetches.length = 0;
nextResponse = {
  getResponseCode: () => 429,
  getContentText: () => JSON.stringify({ error: { message: "Google Search grounding quota exceeded. Retry in 60s." } }),
  getAllHeaders: () => ({ "Retry-After": "60" }),
};
let firstGroundedError;
try {
  sandbox.callGeminiNutritionV284_("Restaurant menu item", {
    lane: "GROUNDED",
    model: "gemini-3.6-flash",
    grounded: true,
  });
} catch (error) {
  firstGroundedError = error;
}
assert.equal(firstGroundedError.aegisCode, "GEMINI_SEARCH_QUOTA");
assert.equal(firstGroundedError.retryAfterMs, 60000);
assert.ok(JSON.parse(fetches[0].options.payload).tools);

try {
  sandbox.callGeminiNutritionV284_("Restaurant menu item", {
    lane: "GROUNDED",
    model: "gemini-3.6-flash",
    grounded: true,
  });
} catch (_) {}
assert.ok(sandbox.getAegisNutritionCircuitDelayV284_("GROUNDED") > 0);
const fetchCount = fetches.length;
assert.throws(
  () => sandbox.callGeminiNutritionV284_("Restaurant menu item", {
    lane: "GROUNDED",
    model: "gemini-3.6-flash",
    grounded: true,
  }),
  (error) => error.aegisCode === "GEMINI_GROUNDED_CIRCUIT_OPEN",
);
assert.equal(fetches.length, fetchCount, "open circuit must not call Gemini");

let releasedChanges = null;
sandbox.updateAegisNutritionQueueJobV282_ = (_sheet, _row, changes) => {
  releasedChanges = changes;
};
sandbox.ensureAegisNutritionWorkerTriggerV282_ = () => {};
const durableCapacityError = new Error("quota");
durableCapacityError.aegisCode = "GEMINI_DAILY_QUOTA";
durableCapacityError.aegisRetryable = true;
durableCapacityError.aegisCapacity = true;
durableCapacityError.retryAfterMs = 6 * 60 * 60 * 1000;
sandbox.releaseAegisNutritionJobAfterFailureV282_(
  {},
  2,
  "capture-1",
  12,
  durableCapacityError,
);
assert.equal(releasedChanges.status, "RETRY_SCHEDULED");
assert.equal(releasedChanges.attempts, 12);
assert.ok(releasedChanges.nextAttemptAt instanceof Date);

const circuitOpenError = new Error("open");
circuitOpenError.aegisCode = "GEMINI_SIMPLE_CIRCUIT_OPEN";
circuitOpenError.aegisRetryable = true;
circuitOpenError.aegisCapacity = true;
circuitOpenError.retryAfterMs = 15 * 60 * 1000;
sandbox.releaseAegisNutritionJobAfterFailureV282_(
  {},
  2,
  "capture-2",
  7,
  circuitOpenError,
);
assert.equal(releasedChanges.status, "RETRY_SCHEDULED");
assert.equal(releasedChanges.attempts, 6, "an open circuit must not consume an AI attempt");

console.log("PASS nutrition 2.8.4 tiered provider, quota parsing, and circuit-breaker validation");
