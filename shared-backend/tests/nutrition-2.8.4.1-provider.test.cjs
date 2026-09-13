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
assert.equal(
  sandbox.tryKnownFoodNutritionV284_("2oz Fig Newton, 1 banana"),
  null,
  "a catalog match must never swallow another comma-separated item",
);
const mixedLocal = sandbox.tryResolveAegisNutritionLocallyV284_("2oz Fig Newton, 1 banana");
assert.equal(mixedLocal.items.length, 2);
assert.equal(mixedLocal.items[0].item, "Fig Newton cookies");
assert.equal(mixedLocal.items[1].item, "Medium banana");
assert.equal(fetches.length, 0, "fully local multi-item captures must not call Gemini");

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

const suppliedFiveItemFixture = "2 Servings Tyson Frozen Grilled Chicken, 2 servings Kirkland Salsa, 1 Medium Mission Flour Tortilla, 2 tablespoons Texas Pete Hotter Hot Sauce, 2 Servings Rice-A-Roni Chicken";
const fixtureSegments = sandbox.splitAegisNutritionItemsV284_(suppliedFiveItemFixture);
assert.deepEqual(Array.from(fixtureSegments), [
  "2 Servings Tyson Frozen Grilled Chicken",
  "2 servings Kirkland Salsa",
  "1 Medium Mission Flour Tortilla",
  "2 tablespoons Texas Pete Hotter Hot Sauce",
  "2 Servings Rice-A-Roni Chicken",
]);
assert.ok(fixtureSegments.every((segment) => sandbox.requiresGroundedNutritionV284_(segment)));

const brandedItems = [
  ["Tyson Frozen Grilled Chicken", "2 servings"],
  ["Kirkland Salsa", "2 servings"],
  ["Mission Flour Tortilla", "1 medium tortilla"],
  ["Texas Pete Hotter Hot Sauce", "2 tablespoons"],
  ["Rice-A-Roni Chicken", "2 servings"],
].map(([item, portion], index) => ({
  item,
  portion,
  calories: 100 + index * 25,
  protein: 5 + index,
  carbs: 10 + index,
  fat: 2 + index,
  saturated_fat: 1,
  fiber: 1,
  sugar: 2,
  sodium: 100 + index * 50,
  cholesterol: 10,
  source_type: "OFFICIAL",
  source_url: "https://example.invalid/product-" + index,
  confidence: "MEDIUM",
  lookup_depth: 2,
  assumptions: "Fixture item " + (index + 1),
  conservative_adjustment: false,
}));
nextResponse = {
  getResponseCode: () => 200,
  getContentText: () => JSON.stringify({
    candidates: [{ content: { parts: [{ text: JSON.stringify({
      items: brandedItems,
      overall_confidence: "MEDIUM",
      lookup_depth: 2,
    }) }] } }],
  }),
  getAllHeaders: () => ({}),
};
const fixtureFetchCount = fetches.length;
const fiveItemResult = sandbox.resolveAegisNutritionV284_(suppliedFiveItemFixture);
assert.equal(fiveItemResult.items.length, 5);
assert.equal(fetches.length, fixtureFetchCount + 1, "the five-item bundle should use one provider call");
const fixturePayload = JSON.parse(fetches.at(-1).options.payload);
assert.ok(fixturePayload.tools, "branded products must use grounded lookup");
assert.match(fixturePayload.contents[0].parts[0].text, /exactly 5 separately logged food items/);
assert.throws(
  () => sandbox.enforceAegisNutritionItemIntegrityV284_({
    items: [{ item: "Generic Mixed Meal Portion", portion: "1 serving" }],
  }, fixtureSegments),
  (error) => error.aegisCode === "NUTRITION_ITEM_COUNT_MISMATCH",
);
assert.throws(
  () => sandbox.enforceAegisNutritionItemIntegrityV284_({
    items: fixtureSegments.map(() => ({
      item: "Generic Mixed Meal Portion",
      portion: "2 servings",
      source_type: "MODEL_ESTIMATE",
      confidence: "LOW",
    })),
  }, fixtureSegments),
  (error) => error.aegisCode === "NUTRITION_GENERIC_AGGREGATE_REJECTED",
);

const originalResolver = sandbox.resolveAegisNutritionV284_;
const originalDataSheetResolver = sandbox.getAegisNutritionDataSheetV282_;
const originalCommit = sandbox.commitAegisNutritionResultV282_;
const originalRelease = sandbox.releaseAegisNutritionJobAfterFailureV282_;
let atomicCommitCalls = 0;
let atomicReleaseCalls = 0;
sandbox.SpreadsheetApp = {
  openById: () => ({
    getSheets: () => [{ getSheetId: () => 7 }],
  }),
};
sandbox.getAegisNutritionDataSheetV282_ = () => ({});
sandbox.resolveAegisNutritionV284_ = () => {
  const error = new Error("collapsed result rejected");
  error.aegisCode = "NUTRITION_ITEM_COUNT_MISMATCH";
  error.aegisRetryable = true;
  throw error;
};
sandbox.commitAegisNutritionResultV282_ = () => { atomicCommitCalls += 1; };
sandbox.releaseAegisNutritionJobAfterFailureV282_ = () => { atomicReleaseCalls += 1; };
sandbox.processClaimedAegisNutritionJobV282_({
  spreadsheetId: "sheet-1",
  queueSheetId: 7,
  row: 2,
  captureId: "fixture-capture",
  input: suppliedFiveItemFixture,
  attempts: 1,
});
assert.equal(atomicCommitCalls, 0, "an invalid bundle must not write any nutrition rows");
assert.equal(atomicReleaseCalls, 1, "an invalid bundle must return to queue handling");
sandbox.resolveAegisNutritionV284_ = originalResolver;
sandbox.getAegisNutritionDataSheetV282_ = originalDataSheetResolver;
sandbox.commitAegisNutritionResultV282_ = originalCommit;
sandbox.releaseAegisNutritionJobAfterFailureV282_ = originalRelease;

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

console.log("PASS nutrition 2.8.4.1 tiered provider, multi-item integrity, quota, and circuit validation");
