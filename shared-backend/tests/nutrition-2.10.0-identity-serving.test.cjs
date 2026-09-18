const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const sources = {
  nutrition281: read("patches/2.8.1-nutrition-reliability.gs"),
  queue282: read("patches/2.8.2-durable-nutrition-queue.gs"),
  provider284: read("patches/2.8.4-nutrition-provider-hardening.gs"),
  nightly290: read("patches/2.9.0-nightly-nutrition-reconciliation.gs"),
  hardening2100: read("patches/2.10.0-nutrition-identity-serving.gs"),
};

for (const [name, source] of Object.entries(sources)) {
  assert.doesNotThrow(() => new vm.Script(source, { filename: name }));
}

const functionNames = Object.values(sources).flatMap((source) =>
  [...source.matchAll(/^function\s+([\w$]+)\s*\(/gm)].map((match) => match[1]),
);
assert.equal(
  new Set(functionNames).size,
  functionNames.length,
  "Apps Script function names must remain unique",
);

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
  PropertiesService: {
    getScriptProperties() {
      return { getProperty() { return null; } };
    },
  },
};
vm.createContext(sandbox);
for (const [name, source] of Object.entries(sources)) {
  vm.runInContext(source, sandbox, { filename: name });
}

assert.equal(
  sandbox.tryDeterministicNutritionV282_("Chobani Coffee Yogurt"),
  null,
  "Coffee-flavored yogurt must not resolve as black coffee",
);
assert.equal(
  sandbox.tryDeterministicNutritionV282_("12 oz black coffee").items[0].item,
  "Black coffee",
);

const chobaniMismatch = sandbox.validateAegisNutritionIdentityV2100_(
  "Chobani Coffee Yogurt",
  "Black coffee",
);
assert.equal(chobaniMismatch.ok, false);
assert.match(chobaniMismatch.reason, /brand|category/i);

const chobaniMatch = sandbox.validateAegisNutritionIdentityV2100_(
  "1 serving Chobani Coffee Yogurt",
  "Chobani Coffee Greek Yogurt",
);
assert.equal(chobaniMatch.ok, true);

const safeReview = {
  items: [{
    calories: 140,
    confidence: "HIGH",
    source_authority: "HIGH",
    identity_status: "MISMATCH",
    portion_status: "MATCH",
  }],
};
assert.equal(
  sandbox.decideAegisNutritionRevisionV290_(
    { items: [{ calories: 140 }] },
    safeReview,
  ).decision,
  "NEEDS_REVIEW",
  "An identity mismatch must never overwrite the provisional row",
);

const reviewedMismatch = sandbox.validateKineticNightlyResponseV290_(
  {
    batch_id: "KINETIC-MISMATCH",
    food_date: "2026-09-18",
    reviews: [{
      capture_id: "CAP-CHOBANI",
      decision: "ADJUST",
      reason: "Incorrect generic keyword match",
      items: [{
        item: "Black coffee",
        portion: "12 fl oz",
        calories: 5,
        protein: 0.3,
        carbs: 0,
        fat: 0,
        saturated_fat: 0,
        fiber: 0,
        sugar: 0,
        sodium: 10,
        cholesterol: 0,
        source_type: "MODEL_ESTIMATE",
        source_url: "",
        confidence: "LOW",
        lookup_depth: 1,
        assumptions: "",
        conservative_adjustment: false,
        canonical_serving_amount: 12,
        canonical_serving_unit: "fl oz",
        submitted_serving_factor: 1,
      }],
    }],
  },
  "2026-09-18",
  "KINETIC-MISMATCH",
  [{
    captureId: "CAP-CHOBANI",
    input: "1 serving Chobani Coffee Yogurt",
    provisional: {
      items: [{
        item: "1 serving Chobani Coffee Yogurt",
        portion: "1 serving",
        calories: 140,
      }],
    },
  }],
);
assert.equal(reviewedMismatch[0].validated.items[0].identity_status, "MISMATCH");
assert.equal(
  sandbox.decideAegisNutritionRevisionV290_(
    reviewedMismatch[0].job.provisional,
    reviewedMismatch[0].validated,
  ).decision,
  "NEEDS_REVIEW",
);

const fig = sandbox.builtInAegisTrustedFoodV2100_("4 Fig Newton cookies");
assert.ok(fig);
const fourCookies = sandbox.parseAegisNutritionPortionV2100_(
  "4 Fig Newton cookies",
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(fourCookies, fig),
  1,
);
const twoCookies = sandbox.parseAegisNutritionPortionV2100_(
  "2 Fig Newton cookies",
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(twoCookies, fig),
  0.5,
);
const eightCookies = sandbox.parseAegisNutritionPortionV2100_(
  "8 Fig Newton cookies",
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(eightCookies, fig),
  2,
);
const twentyNineGrams = sandbox.parseAegisNutritionPortionV2100_(
  "29 g Fig Newton cookies",
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(twentyNineGrams, fig),
  0.5,
);

const figItem = sandbox.scaleAegisTrustedNutritionV2100_(
  fig,
  twoCookies,
  0.5,
);
assert.equal(figItem.calories, 100);
assert.equal(figItem.portion, "2 cookies (29 g)");

const priorChobani = {
  productKey: "chobani-coffee-yogurt",
  brand: "chobani",
  canonicalItem: "Chobani Coffee Yogurt",
  category: "YOGURT",
  servingAmount: 1,
  servingUnit: "serving",
  servingGrams: 150,
  servingMilliliters: null,
  countUnit: "",
  countPerServing: null,
  perServing: { calories: 140 },
  aliases: [],
  sourceAuthority: "HIGH",
  sourceUrl: "https://www.chobani.com/",
  identityConfidence: "HIGH",
  nutritionConfidence: "HIGH",
  status: "TRUSTED",
};
assert.equal(
  sandbox.selectAegisTrustedFoodV2100_(
    [priorChobani],
    "2 servings Chobani Coffee Yogurt",
  ),
  priorChobani,
  "A prior trusted product must be reusable independently of its amount",
);
assert.equal(
  sandbox.selectAegisTrustedFoodV2100_(
    [priorChobani],
    "12 fl oz black coffee",
  ),
  null,
  "A prior yogurt record must never match a coffee beverage",
);

const importedServing = sandbox.parseAegisCanonicalServingV2100_(
  {},
  "0.5 cup (60 g)",
);
assert.equal(importedServing.amount, 0.5);
assert.equal(importedServing.unit, "cup");
assert.equal(importedServing.grams, 60);
assert.equal(
  sandbox.parseAegisNutritionPortionV2100_(
    "0.5 cup (60 g) Bear Naked Granola",
  ).identity_text,
  "Bear Naked Granola",
);
assert.equal(
  sandbox.classifyAegisNutritionSourceV290_({
    source_type: "OFFICIAL",
    source_url: "https://www.chobani.com/products/yogurt/example",
  }),
  "HIGH",
);

const peanutButter = {
  servingAmount: 2,
  servingUnit: "tbsp",
  servingGrams: 32,
  servingMilliliters: null,
  countUnit: "",
  countPerServing: null,
};
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("1 tbsp peanut butter"),
    peanutButter,
  ),
  0.5,
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("16 g peanut butter"),
    peanutButter,
  ),
  0.5,
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("1 tbsp olive oil"),
    {
      servingAmount: 1,
      servingUnit: "serving",
      servingGrams: null,
      servingMilliliters: null,
      countUnit: "",
      countPerServing: null,
    },
  ),
  null,
  "Volume-to-serving conversion must fail without a product-specific mapping",
);

const cereal = {
  servingAmount: 0.5,
  servingUnit: "cup",
  servingGrams: 60,
  servingMilliliters: null,
  countUnit: "",
  countPerServing: null,
};
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("0.25 cup granola"),
    cereal,
  ),
  0.5,
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("30 g granola"),
    cereal,
  ),
  0.5,
);
assert.ok(
  Math.abs(
    sandbox.calculateAegisServingFactorV2100_(
      sandbox.parseAegisNutritionPortionV2100_("1 oz granola"),
      cereal,
    ) - (28.349523125 / 60),
  ) < 1e-12,
);

const liquid = {
  servingAmount: 1,
  servingUnit: "cup",
  servingGrams: null,
  servingMilliliters: 236.5882365,
  countUnit: "",
  countPerServing: null,
};
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("118.29411825 ml soup"),
    liquid,
  ),
  0.5,
);
assert.ok(
  Math.abs(
    sandbox.calculateAegisServingFactorV2100_(
      sandbox.parseAegisNutritionPortionV2100_("4 fl oz soup"),
      liquid,
    ) - 0.5,
  ) < 1e-12,
);
assert.ok(
  Math.abs(
    sandbox.calculateAegisServingFactorV2100_(
      sandbox.parseAegisNutritionPortionV2100_("8 tbsp soup"),
      liquid,
    ) - 0.5,
  ) < 1e-12,
);
assert.ok(
  Math.abs(
    sandbox.calculateAegisServingFactorV2100_(
      sandbox.parseAegisNutritionPortionV2100_("24 tsp soup"),
      liquid,
    ) - 0.5,
  ) < 1e-12,
);

const ramenPackage = {
  servingAmount: 1,
  servingUnit: "package",
  servingGrams: 85,
  servingMilliliters: null,
  countUnit: "",
  countPerServing: null,
};
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("2 packages chicken ramen"),
    ramenPackage,
  ),
  2,
);
assert.equal(
  sandbox.calculateAegisServingFactorV2100_(
    sandbox.parseAegisNutritionPortionV2100_("1/2 serving chicken ramen"),
    ramenPackage,
  ),
  0.5,
);

const prompt = sandbox.buildKineticNightlyPromptV290_(
  "2026-09-18",
  "KINETIC-IDENTITY-TEST",
  [{
    captureId: "CAP-CHOBANI",
    input: "Chobani Coffee Yogurt",
    provisional: { items: [{ item: "Chobani Coffee Yogurt" }] },
  }],
);
assert.match(prompt, /coffee yogurt is yogurt, not black coffee/i);
assert.match(prompt, /canonical_serving_amount/);
assert.match(prompt, /Do not invent mass, volume, density, count equivalence, or package size/);

console.log(
  "PASS backend 2.10.0 identity guard, trusted-food reuse, and deterministic serving conversion",
);
