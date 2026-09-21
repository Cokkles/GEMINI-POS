const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const source281 = read("patches/2.8.1-nutrition-reliability.gs");
const source284 = read("patches/2.8.4-nutrition-provider-hardening.gs");
const source290 = read("patches/2.9.0-nightly-nutrition-reconciliation.gs");
const source2111 = read("patches/2.11.1-nutrition-nightly-reliability.gs");

for (const [name, source] of Object.entries({
  source281, source284, source290, source2111,
})) {
  assert.doesNotThrow(() => new vm.Script(source, { filename: name }));
}

assert.doesNotMatch(source284, /retry scheduled in approximately/);
assert.match(source284, /retry recommended in approximately/);
assert.match(source290, /retry_scheduled: retry\.scheduled === true/);
assert.match(source290, /runAegisNutritionNightlyControllerV2111/);

const properties = new Map([
  ["AEGIS_NUTRITION_NIGHTLY_MODEL", "primary-model"],
  ["AEGIS_NUTRITION_NIGHTLY_FALLBACK_MODEL", "fallback-model"],
]);
const propertyApi = {
  getProperty: (key) => properties.get(key) || null,
  setProperty: (key, value) => properties.set(key, String(value)),
  deleteProperty: (key) => properties.delete(key),
};
const deleted = [];
const created = [];
const retryTrigger = {
  getHandlerFunction: () => "runAegisNutritionNightlyRetryV2111",
};
const dailyTrigger = {
  getHandlerFunction: () => "runAegisNutritionNightlyControllerV2111",
};
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
  console,
  Logger: { log() {} },
  PropertiesService: { getScriptProperties: () => propertyApi },
  ScriptApp: {
    getProjectTriggers: () => [retryTrigger, dailyTrigger],
    deleteTrigger: (trigger) => deleted.push(trigger),
    newTrigger: (handler) => ({
      timeBased() { return this; },
      after(delay) { this.delay = delay; return this; },
      create() { created.push({ handler, delay: this.delay }); return {}; },
    }),
  },
};
vm.createContext(sandbox);
vm.runInContext(source281, sandbox, { filename: "NutritionReliability281.gs" });
vm.runInContext(source284, sandbox, { filename: "NutritionProviderReliability284.gs" });
vm.runInContext(source290, sandbox, { filename: "NutritionNightlyReconciliation290.gs" });
vm.runInContext(source2111, sandbox, { filename: "NutritionNightlyReliability2111.gs" });

const calledModels = [];
sandbox.callKineticNightlyGeminiModelV290_ = (_prompt, model) => {
  calledModels.push(model);
  if (model === "primary-model") {
    const error = new Error("high demand");
    error.aegisCode = "KINETIC_NIGHTLY_GEMINI_HIGH_VOLUME";
    error.aegisRetryable = true;
    error.retryAfterMs = 15 * 60 * 1000;
    throw error;
  }
  return { ok: true, model };
};
const failover = sandbox.callKineticNightlyProviderV2111_("prompt");
assert.equal(failover.model, "fallback-model");
assert.deepEqual(calledModels, ["primary-model", "fallback-model"]);

const capacity = new Error("high demand");
capacity.aegisCode = "KINETIC_NIGHTLY_GEMINI_HIGH_VOLUME";
capacity.aegisRetryable = true;
capacity.retryAfterMs = 15 * 60 * 1000;
const scheduled = sandbox.scheduleAegisNutritionNightlyRetryV2111_(capacity);
assert.equal(scheduled.scheduled, true);
assert.equal(created.length, 1);
assert.equal(created[0].handler, "runAegisNutritionNightlyRetryV2111");
assert.ok(created[0].delay >= 15 * 60 * 1000);
assert.deepEqual(deleted, [retryTrigger], "daily trigger must not be deleted");

const permanent = new Error("bad request");
permanent.aegisCode = "KINETIC_NIGHTLY_HTTP_400";
permanent.aegisRetryable = false;
assert.equal(
  sandbox.scheduleAegisNutritionNightlyRetryV2111_(permanent).scheduled,
  false,
);

const partitioned = sandbox.partitionAegisNutritionNightlyJobsV2111_([
  {
    captureId: "trusted",
    provisional: { items: [{
      confidence: "HIGH",
      source_url: "https://manufacturer.example/item",
      assumptions: "Reused from the trusted KINETIC food catalog; portion arithmetic was performed locally.",
    }] },
  },
  {
    captureId: "unresolved",
    provisional: { items: [{
      confidence: "LOW",
      source_url: "",
      assumptions: "Provisional estimate; nightly review required.",
    }] },
  },
]);
assert.equal(partitioned.local.length, 1);
assert.equal(partitioned.provider.length, 1);
assert.equal(partitioned.local[0].captureId, "trusted");

console.log("PASS backend 2.11.1 real retry, model failover, and local confirmation validation");
