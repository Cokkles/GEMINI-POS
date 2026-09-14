const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const code = read("Code.gs");
const nutrition281 = read("patches/2.8.1-nutrition-reliability.gs");
const queue282 = read("patches/2.8.2-durable-nutrition-queue.gs");
const provider284 = read("patches/2.8.4-nutrition-provider-hardening.gs");
const nightly290 = read("patches/2.9.0-nightly-nutrition-reconciliation.gs");

for (const [name, source] of Object.entries({
  code, nutrition281, queue282, provider284, nightly290,
})) {
  assert.doesNotThrow(() => new vm.Script(source, { filename: name }));
}

const functionNames = [code, nutrition281, queue282, provider284, nightly290]
  .flatMap((source) =>
    [...source.matchAll(/^function\s+([\w$]+)\s*\(/gm)].map((match) => match[1]),
  );
assert.equal(
  new Set(functionNames).size,
  functionNames.length,
  "Apps Script function names must remain unique",
);

assert.match(code, /const AEGIS_BACKEND_VERSION = "2\.9\.0";/);
for (const capability of [
  "nutrition_provisional_logging_v1",
  "nutrition_nightly_reconciliation_v1",
  "nutrition_evidence_catalog_v1",
  "nutrition_revision_audit_v1",
]) {
  assert.match(code, new RegExp(capability + ": true"));
}
for (const action of [
  "get_nutrition_verification_status",
  "run_nutrition_nightly_review",
]) {
  assert.match(code, new RegExp('action === "' + action + '"'));
}

const enqueueStart = queue282.indexOf("function enqueueAegisNutritionCaptureV282_(");
const enqueueEnd = queue282.indexOf("\nfunction ", enqueueStart + 20);
const enqueue = queue282.slice(enqueueStart, enqueueEnd);
assert.match(enqueue, /buildAegisNutritionProvisionalV290_/);
assert.match(enqueue, /registerAegisNutritionReconciliationV290_/);
assert.doesNotMatch(enqueue, /callGeminiNutritionV284_/);

const workerStart = queue282.indexOf("function processClaimedAegisNutritionJobV282_(");
const workerEnd = queue282.indexOf("\nfunction ", workerStart + 20);
const worker = queue282.slice(workerStart, workerEnd);
assert.match(worker, /buildAegisNutritionProvisionalV290_/);
assert.doesNotMatch(worker, /resolveAegisNutritionV284_/);
assert.doesNotMatch(worker, /callGemini/);

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
  console,
  Logger: { log() {} },
};
vm.createContext(sandbox);
vm.runInContext(nutrition281, sandbox, { filename: "NutritionReliability281.gs" });
vm.runInContext(queue282, sandbox, { filename: "NutritionQueue282.gs" });
vm.runInContext(provider284, sandbox, { filename: "NutritionProviderReliability284.gs" });
vm.runInContext(nightly290, sandbox, { filename: "NutritionNightlyReconciliation290.gs" });

const fixture =
  "2 Servings Tyson Frozen Grilled Chicken, 2 servings Kirkland Salsa, " +
  "1 Medium Mission Flour Tortilla, 2 tablespoons Texas Pete Hotter Hot Sauce, " +
  "2 Servings Rice-A-Roni Chicken";
const segments = sandbox.splitAegisNutritionItemsV284_(fixture);
assert.equal(segments.length, 5);

const burger = sandbox.estimateAegisNutritionProvisionalSegmentV290_(
  "one local restaurant hamburger",
);
assert.equal(burger.item, "one local restaurant hamburger");
assert.equal(burger.calories, 450);
assert.equal(burger.confidence, "LOW");
assert.match(burger.assumptions, /nightly KINETIC review required/);

const close = sandbox.decideAegisNutritionRevisionV290_(
  { items: [{ calories: 400 }] },
  { items: [{ calories: 418, confidence: "MEDIUM", source_authority: "MEDIUM" }] },
);
assert.equal(close.decision, "CONFIRM");
assert.equal(close.applyReviewed, false);

const meaningful = sandbox.decideAegisNutritionRevisionV290_(
  { items: [{ calories: 400 }] },
  { items: [{ calories: 500, confidence: "HIGH", source_authority: "HIGH" }] },
);
assert.equal(meaningful.decision, "ADJUST");
assert.equal(meaningful.applyReviewed, true);

const weakOutlier = sandbox.decideAegisNutritionRevisionV290_(
  { items: [{ calories: 400 }] },
  { items: [{ calories: 700, confidence: "LOW", source_authority: "LOW" }] },
);
assert.equal(weakOutlier.decision, "NEEDS_REVIEW");
assert.equal(weakOutlier.applyReviewed, false);

assert.equal(
  sandbox.classifyAegisNutritionSourceV290_({
    source_type: "OFFICIAL",
    source_url: "https://www.texaspete.com/products/hotter-hot-sauce",
  }),
  "HIGH",
);
assert.equal(
  sandbox.classifyAegisNutritionSourceV290_({
    source_type: "OFFICIAL",
    source_url: "https://example-retailer.invalid/product",
  }),
  "LOW",
);

const prompt = sandbox.buildKineticNightlyPromptV290_(
  "2026-09-14",
  "KINETIC-TEST",
  [{
    captureId: "CAP-FIVE",
    input: fixture,
    provisional: { items: segments.map((segment) => ({ item: segment })) },
  }],
);
assert.match(prompt, /Review EVERY supplied food capture/);
assert.match(prompt, /Do not label a retailer, aggregator, or crowdsourced page as OFFICIAL/);
assert.match(prompt, /HIGH=5, MEDIUM=3, LOW=1/);
assert.match(prompt, /exclude a materially conflicting LOW source as an outlier/);
assert.match(prompt, /CAP-FIVE/);
assert.match(prompt, /Tyson Frozen Grilled Chicken/);

console.log(
  "PASS backend 2.9.0 provisional capture, daily batch, deviation, and source-authority validation",
);
