const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const read = (name) => fs.readFileSync(path.join(root, name), "utf8");
const source = read("patches/2.12.0-nutrition-sheet-normalization.gs");
const nutrition281 = read("patches/2.8.1-nutrition-reliability.gs");
const queue282 = read("patches/2.8.2-durable-nutrition-queue.gs");
const nightly290 = read("patches/2.9.0-nightly-nutrition-reconciliation.gs");

assert.doesNotThrow(() => new vm.Script(source, {
  filename: "NutritionSheetNormalization2120.gs",
}));
assert.match(nutrition281, /normalizeAegisNutritionItemsForSheetV2120_/);
assert.match(queue282, /normalizeAegisNutritionItemsForSheetV2120_/);
assert.match(nightly290, /normalizeAegisNutritionItemForSheetV2120_/);

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
  normalizeNutritionIdentityTextV284_(value) {
    return String(value || "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, " ")
      .trim();
  },
  taggedNutritionErrorV281_(code, message, retryable) {
    const error = new Error(message);
    error.aegisCode = code;
    error.aegisRetryable = retryable;
    return error;
  },
};
vm.createContext(sandbox);
vm.runInContext(source, sandbox, {
  filename: "NutritionSheetNormalization2120.gs",
});

const fixtures = [
  ["1 Pack Chicken Ramen", "1 Pack Chicken Ramen", "Chicken Ramen", "1 package"],
  [
    "1 Serving Bear Naked Cacao & Cashew Butter",
    "1 Serving Bear Naked Cacao & Cashew Butter",
    "Bear Naked Cacao & Cashew Butter",
    "1 serving",
  ],
  ["2tbl spoon chobani sweet cream creamer", "2 tbsp", "Chobani Sweet Cream Creamer", "2 tbsp"],
  ["Sheetz Fries 1 bag", "Sheetz Fries 1 bag", "Sheetz Fries", "1 bag"],
  ["fig Newtons", "4 cookies (58g)", "Fig Newtons", "4 cookies (58 g)"],
  ["chobani coffee yogurt", "1 container (5.3 oz)", "Chobani Coffee Yogurt", "1 container (5.3 oz)"],
];

for (const [item, portion, expectedItem, expectedPortion] of fixtures) {
  const normalized = sandbox.normalizeAegisNutritionItemForSheetV2120_(
    { item, portion, calories: 100 },
    item,
  );
  assert.equal(normalized.item, expectedItem, item);
  assert.equal(normalized.portion, expectedPortion, item);
  assert.equal(normalized.calories, 100, "nutrition fields must remain unchanged");
}

const idempotent = sandbox.normalizeAegisNutritionItemForSheetV2120_(
  { item: "Chobani Coffee Yogurt", portion: "1 container (5.3 oz)" },
  "chobani coffee yogurt",
);
assert.deepEqual(
  JSON.parse(JSON.stringify(idempotent)),
  { item: "Chobani Coffee Yogurt", portion: "1 container (5.3 oz)" },
);

assert.throws(
  () => sandbox.validateAegisNutritionSheetItemV2120_({
    item: "2 Bags Sheetz Fries",
    portion: "2 bags",
  }),
  (error) => error.aegisCode === "NUTRITION_SHEET_NAME_CONTAINS_PORTION",
);
assert.throws(
  () => sandbox.validateAegisNutritionSheetItemV2120_({
    item: "Chicken Ramen",
    portion: "Chicken Ramen",
  }),
  (error) => error.aegisCode === "NUTRITION_SHEET_DUPLICATE_DISPLAY",
);

assert.match(source, /historical_rows_changed:\s*0/);
assert.match(source, /Explicit nutrition row numbers are required/);
assert.match(source, /selected\.length > 100/);
assert.match(source, /skipped_unchanged_rows/);
assert.match(source, /NO_APPROVED_PROPOSED_ROWS/);
assert.match(source, /_AEGIS_NUTRITION_NORMALIZATION_REVIEW_V1/);
assert.match(source, /insertCheckboxes/);

const unchangedPlan = sandbox.buildAegisNutritionNormalizationPlanV2120_(
  42,
  ["", "", "Chicken Ramen", "1 package"],
);
assert.equal(unchangedPlan.changed, false);

const changedPlan = sandbox.buildAegisNutritionNormalizationPlanV2120_(
  57,
  ["", "", "1 Pack Chicken Ramen", "1 Pack Chicken Ramen"],
);
assert.equal(changedPlan.changed, true);
assert.equal(changedPlan.normalized.item, "Chicken Ramen");
assert.equal(changedPlan.normalized.portion, "1 package");

console.log("PASS backend 2.12.0.1 visible review and safe normalization validation");
