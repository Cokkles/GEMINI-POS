/**
 * AEGIS shared backend 2.12.0 -- canonical nutrition sheet renderer.
 *
 * Identity, portion, nutrition, and presentation remain separate. Every new
 * or reconciled A:X row passes through this deterministic renderer. Historical
 * cleanup is preview-first and requires explicit row numbers before mutation.
 */

var AEGIS_NUTRITION_SHEET_NORMALIZATION_CONTRACT_V2120 =
  "AEGIS_NUTRITION_SHEET_NORMALIZATION_V1";
var AEGIS_NUTRITION_NORMALIZATION_AUDIT_SHEET_V2120 =
  "_AEGIS_NUTRITION_NORMALIZATION_AUDIT_V1";
var AEGIS_NUTRITION_NORMALIZATION_AUDIT_HEADERS_V2120 = [
  "Applied At ISO", "Row", "Capture ID", "Original Item", "Original Portion",
  "Normalized Item", "Normalized Portion", "Backend Version"
];

function normalizeAegisNutritionWhitespaceV2120_(value) {
  return String(value == null ? "" : value)
    .replace(/^\s*\/calories\b\s*/i, "")
    .replace(/(\d)\s*tbl\s*spoons?\b/gi, "$1 tbsp")
    .replace(/\b(?:tbl\s*spoons?|tblsp|tbs)\b/gi, "tbsp")
    .replace(/\btable\s*spoons?\b/gi, "tbsp")
    .replace(/\btea\s*spoons?\b/gi, "tsp")
    .replace(/(\d)\s*(tbsp|tsp|g|kg|oz|ml|l)\b/gi, "$1 $2")
    .replace(/\s+/g, " ")
    .trim();
}

function parseAegisDisplayAmountV2120_(text) {
  var value = normalizeAegisNutritionWhitespaceV2120_(text);
  var number = "(\\d+(?:\\.\\d+)?|\\d+\\s+\\d+\\/\\d+|\\d+\\/\\d+)";
  var units = "(fluid\\s+ounces?|fl\\s*oz|tablespoons?|tbsp|teaspoons?|tsp|kilograms?|kg|grams?|g|pounds?|lb|ounces?|oz|milliliters?|ml|liters?|l|cups?|servings?|containers?|packages?|packs?|packets?|pouches?|bags?|bottles?|cans?|cookies?|bars?|slices?|pieces?|items?)";
  var leading = new RegExp(
    "^" + number + "\\s*" + units + "\\b(?:\\s*(\\([^)]*\\)))?\\s*",
    "i"
  );
  var trailing = new RegExp("\\s+" + number + "\\s*" + units + "(?:\\s*(\\([^)]*\\)))?\\s*$", "i");
  var match = value.match(leading);
  if (match) {
    return {
      amount_text: match[1],
      unit_text: match[2],
      detail_text: match[3] || "",
      identity_text: value.slice(match[0].length).trim(),
      explicit: true,
      position: "LEADING"
    };
  }
  match = value.match(trailing);
  if (match) {
    return {
      amount_text: match[1],
      unit_text: match[2],
      detail_text: match[3] || "",
      identity_text: value.slice(0, match.index).trim(),
      explicit: true,
      position: "TRAILING"
    };
  }
  return {
    amount_text: "",
    unit_text: "",
    detail_text: "",
    identity_text: value,
    explicit: false,
    position: "NONE"
  };
}

function parseAegisDisplayNumberV2120_(value) {
  var text = String(value || "").trim();
  var mixed = text.match(/^(\d+)\s+(\d+)\/(\d+)$/);
  if (mixed && Number(mixed[3])) {
    return Number(mixed[1]) + Number(mixed[2]) / Number(mixed[3]);
  }
  var fraction = text.match(/^(\d+)\/(\d+)$/);
  if (fraction && Number(fraction[2])) {
    return Number(fraction[1]) / Number(fraction[2]);
  }
  return Number(text) || 0;
}

function singularAegisDisplayUnitV2120_(value) {
  var unit = String(value || "").toLowerCase().replace(/\./g, "").trim();
  var aliases = {
    gram: "g", grams: "g", g: "g",
    kilogram: "kg", kilograms: "kg", kg: "kg",
    ounce: "oz", ounces: "oz", oz: "oz",
    pound: "lb", pounds: "lb", lb: "lb",
    milliliter: "mL", milliliters: "mL", ml: "mL",
    liter: "L", liters: "L", l: "L",
    "fluid ounce": "fl oz", "fluid ounces": "fl oz", "fl oz": "fl oz",
    tablespoon: "tbsp", tablespoons: "tbsp", tbsp: "tbsp",
    teaspoon: "tsp", teaspoons: "tsp", tsp: "tsp",
    cup: "cup", cups: "cup",
    serving: "serving", servings: "serving",
    container: "container", containers: "container",
    package: "package", packages: "package", pack: "package", packs: "package",
    packet: "packet", packets: "packet", pouch: "pouch", pouches: "pouch",
    bag: "bag", bags: "bag", bottle: "bottle", bottles: "bottle",
    can: "can", cans: "can", cookie: "cookie", cookies: "cookie",
    bar: "bar", bars: "bar", slice: "slice", slices: "slice",
    piece: "piece", pieces: "piece", item: "item", items: "item"
  };
  return aliases[unit] || unit;
}

function pluralizeAegisDisplayUnitV2120_(unit, amount) {
  if (Math.abs(Number(amount) - 1) < 0.000001) return unit;
  if (["g", "kg", "oz", "lb", "mL", "L", "fl oz", "tbsp", "tsp"].indexOf(unit) >= 0) {
    return unit;
  }
  if (unit === "pouch") return "pouches";
  return unit + "s";
}

function normalizeAegisNutritionParentheticalV2120_(value) {
  var text = normalizeAegisNutritionWhitespaceV2120_(value);
  return text.replace(
    /\(\s*(\d+(?:\.\d+)?)\s*(g|kg|oz|lb|ml|l|fl\s*oz)\s*\)/ig,
    function(_whole, amount, unit) {
      return "(" + amount + " " + singularAegisDisplayUnitV2120_(unit) + ")";
    }
  );
}

function renderAegisDisplayPortionV2120_(parsed) {
  if (!parsed || !parsed.explicit) return "";
  var amount = parseAegisDisplayNumberV2120_(parsed.amount_text);
  var unit = singularAegisDisplayUnitV2120_(parsed.unit_text);
  var label = pluralizeAegisDisplayUnitV2120_(unit, amount);
  var detail = normalizeAegisNutritionParentheticalV2120_(parsed.detail_text);
  return [String(parsed.amount_text).trim(), label, detail]
    .filter(Boolean).join(" ").trim();
}

function applyAegisKnownBrandCasingV2120_(value) {
  var text = String(value || "");
  var brands = [
    ["williams gourmet kitchen", "Williams Gourmet Kitchen"],
    ["international delight", "International Delight"],
    ["nature's bakery", "Nature's Bakery"],
    ["nestle toll house", "Nestlé Toll House"],
    ["papa john's", "Papa John's"],
    ["rice-a-roni", "Rice-A-Roni"],
    ["rice a roni", "Rice-A-Roni"],
    ["texas pete", "Texas Pete"],
    ["bear naked", "Bear Naked"],
    ["fig newtons", "Fig Newtons"],
    ["fig newton", "Fig Newton"],
    ["chobani", "Chobani"],
    ["oikos", "Oikos"],
    ["sheetz", "Sheetz"],
    ["tyson", "Tyson"],
    ["kirkland", "Kirkland"],
    ["mission", "Mission"],
    ["melinda's", "Melinda's"]
  ];
  brands.forEach(function(pair) {
    text = text.replace(new RegExp("\\b" + pair[0].replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "\\b", "ig"), pair[1]);
  });
  return text;
}

function titleCaseAegisNutritionNameV2120_(value) {
  var text = normalizeAegisNutritionWhitespaceV2120_(value);
  if (!text) return "";
  if (text === text.toLowerCase() || text === text.toUpperCase()) {
    var minor = { and: true, of: true, with: true, in: true };
    text = text.toLowerCase().split(" ").map(function(word, index) {
      if (index > 0 && minor[word]) return word;
      return word.replace(/(^|[-/])([a-z])/g, function(_whole, prefix, letter) {
        return prefix + letter.toUpperCase();
      });
    }).join(" ");
  }
  return applyAegisKnownBrandCasingV2120_(text);
}

function cleanAegisNutritionDisplayNameV2120_(value) {
  var parsed = parseAegisDisplayAmountV2120_(value);
  var name = parsed.identity_text || normalizeAegisNutritionWhitespaceV2120_(value);
  name = name
    .replace(/^\(?\s*approx\.?\s*\d+(?:\.\d+)?\s*(?:g|oz|ml|fl\s*oz)\s*\)?\s*/i, "")
    .replace(/[,:;\-]+\s*$/, "")
    .trim();
  return titleCaseAegisNutritionNameV2120_(name);
}

function chooseAegisNutritionPortionV2120_(item, submittedText) {
  var candidates = [item && item.portion, submittedText, item && item.item];
  for (var i = 0; i < candidates.length; i++) {
    var parsed = parseAegisDisplayAmountV2120_(candidates[i]);
    if (parsed.explicit) {
      var rendered = renderAegisDisplayPortionV2120_(parsed);
      if (rendered) return rendered;
    }
  }
  var existing = normalizeAegisNutritionParentheticalV2120_(
    item && item.portion
  );
  if (
    existing && existing.length <= 80 &&
    normalizeNutritionIdentityTextV284_(existing) !==
      normalizeNutritionIdentityTextV284_(item && item.item)
  ) return existing;
  return "1 serving";
}

function validateAegisNutritionSheetItemV2120_(item) {
  var name = String(item && item.item || "").trim();
  var portion = String(item && item.portion || "").trim();
  if (!name || !portion) {
    throw taggedNutritionErrorV281_(
      "NUTRITION_SHEET_FORMAT_INVALID",
      "Canonical nutrition name and portion are required.",
      false
    );
  }
  if (parseAegisDisplayAmountV2120_(name).explicit &&
      parseAegisDisplayAmountV2120_(name).position === "LEADING") {
    throw taggedNutritionErrorV281_(
      "NUTRITION_SHEET_NAME_CONTAINS_PORTION",
      "Meal / Item must not begin with a quantity and unit.",
      false
    );
  }
  var normalizedName = normalizeNutritionIdentityTextV284_(name);
  var normalizedPortion = normalizeNutritionIdentityTextV284_(portion);
  if (
    normalizedName === normalizedPortion ||
    (normalizedName.length >= 8 && normalizedPortion.indexOf(normalizedName) >= 0)
  ) {
    throw taggedNutritionErrorV281_(
      "NUTRITION_SHEET_DUPLICATE_DISPLAY",
      "Portion Details must not repeat Meal / Item.",
      false
    );
  }
  return item;
}

function normalizeAegisNutritionItemForSheetV2120_(item, submittedText) {
  var normalized = {};
  Object.keys(item || {}).forEach(function(key) { normalized[key] = item[key]; });
  var submitted = normalizeAegisNutritionWhitespaceV2120_(submittedText);
  var itemCandidate = cleanAegisNutritionDisplayNameV2120_(item && item.item);
  var submittedCandidate = cleanAegisNutritionDisplayNameV2120_(submitted);
  normalized.item = itemCandidate || submittedCandidate;
  normalized.portion = chooseAegisNutritionPortionV2120_(item, submitted);
  return validateAegisNutritionSheetItemV2120_(normalized);
}

function normalizeAegisNutritionItemsForSheetV2120_(items, submittedTexts) {
  return (items || []).map(function(item, index) {
    var submitted = Array.isArray(submittedTexts)
      ? submittedTexts[index]
      : submittedTexts;
    return normalizeAegisNutritionItemForSheetV2120_(
      item,
      submitted || item.item
    );
  });
}

function formatAegisNutritionSheetV2120_(sheet) {
  if (!sheet || sheet.getLastRow() < 2) return;
  var rows = sheet.getLastRow() - 1;
  sheet.getRange(2, 5, rows, 5).setNumberFormat("0.##");
  sheet.getRange(2, 11, rows, 4).setNumberFormat("0.##");
  [3, 4, 10, 16, 19].forEach(function(column) {
    sheet.getRange(2, column, rows, 1).setWrap(true);
  });
}

function getAegisNutritionNormalizationAuditSheetV2120_(spreadsheet) {
  return getAegisManagedSheetV290_(
    spreadsheet,
    AEGIS_NUTRITION_NORMALIZATION_AUDIT_SHEET_V2120,
    AEGIS_NUTRITION_NORMALIZATION_AUDIT_HEADERS_V2120
  );
}

function previewAegisNutritionSheetNormalizationV2120(limit) {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var sheet = getAegisNutritionDataSheetV282_(spreadsheet);
  if (sheet.getLastRow() < 2) return { status: "PASS", changes: [] };
  var max = Math.max(1, Math.min(500, Number(limit) || 200));
  var start = Math.max(2, sheet.getLastRow() - max + 1);
  var values = sheet.getRange(
    start,
    1,
    sheet.getLastRow() - start + 1,
    AEGIS_NUTRITION_HEADERS_V281.length
  ).getValues();
  var changes = [];
  values.forEach(function(row, index) {
    var current = { item: row[2], portion: row[3] };
    var normalized;
    try {
      normalized = normalizeAegisNutritionItemForSheetV2120_(current, row[2]);
    } catch (error) {
      changes.push({
        row: start + index,
        status: "NEEDS_REVIEW",
        current_item: row[2],
        current_portion: row[3],
        diagnostic_code: String(error && error.aegisCode || "NUTRITION_SHEET_FORMAT_INVALID")
      });
      return;
    }
    if (normalized.item !== row[2] || normalized.portion !== row[3]) {
      changes.push({
        row: start + index,
        status: "PROPOSED",
        current_item: row[2],
        current_portion: row[3],
        normalized_item: normalized.item,
        normalized_portion: normalized.portion,
        capture_id: row[20] || null
      });
    }
  });
  return {
    status: "PASS",
    scanned_rows: values.length,
    proposed_changes: changes.length,
    changes: changes,
    applied: false,
    contract: AEGIS_NUTRITION_SHEET_NORMALIZATION_CONTRACT_V2120
  };
}

function applyAegisNutritionSheetNormalizationV2120(rowNumbers) {
  if (!Array.isArray(rowNumbers) || !rowNumbers.length) {
    throw new Error("Explicit nutrition row numbers are required.");
  }
  var selected = rowNumbers.map(Number).filter(function(row, index, values) {
    return row >= 2 && Math.floor(row) === row && values.indexOf(row) === index;
  });
  if (!selected.length || selected.length > 100) {
    throw new Error("Select between 1 and 100 explicit nutrition rows.");
  }
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var sheet = getAegisNutritionDataSheetV282_(spreadsheet);
  var audit = getAegisNutritionNormalizationAuditSheetV2120_(spreadsheet);
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) throw new Error("Nutrition normalization is busy.");
  try {
    var plans = selected.map(function(rowNumber) {
      if (rowNumber > sheet.getLastRow()) {
        throw new Error("Nutrition row " + rowNumber + " does not exist.");
      }
      var row = sheet.getRange(
        rowNumber, 1, 1, AEGIS_NUTRITION_HEADERS_V281.length
      ).getValues()[0];
      var normalized = normalizeAegisNutritionItemForSheetV2120_(
        { item: row[2], portion: row[3] },
        row[2]
      );
      return { rowNumber: rowNumber, row: row, normalized: normalized };
    });
    plans.forEach(function(plan) {
      var now = new Date().toISOString();
      audit.appendRow([
        now,
        plan.rowNumber,
        plan.row[20] || "",
        plan.row[2],
        plan.row[3],
        plan.normalized.item,
        plan.normalized.portion,
        "2.12.0"
      ]);
      sheet.getRange(plan.rowNumber, 3, 1, 2).setValues([[
        plan.normalized.item,
        plan.normalized.portion
      ]]);
      sheet.getRange(plan.rowNumber, 23).setValue("2.12.0");
      sheet.getRange(plan.rowNumber, 24).setValue(now);
    });
    formatAegisNutritionSheetV2120_(sheet);
    SpreadsheetApp.flush();
    return {
      status: "PASS",
      applied_rows: plans.map(function(plan) { return plan.rowNumber; }),
      contract: AEGIS_NUTRITION_SHEET_NORMALIZATION_CONTRACT_V2120
    };
  } finally {
    lock.releaseLock();
  }
}

function installAegisNutritionSheetNormalizationV2120() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var sheet = getAegisNutritionDataSheetV282_(spreadsheet);
  ensureAegisNutritionHeadersV281_(sheet);
  getAegisNutritionNormalizationAuditSheetV2120_(spreadsheet);
  formatAegisNutritionSheetV2120_(sheet);
  return {
    status: "PASS",
    backend_version: "2.12.0",
    historical_rows_changed: 0,
    preview_required_before_cleanup: true,
    contract: AEGIS_NUTRITION_SHEET_NORMALIZATION_CONTRACT_V2120
  };
}

function getAegisNutritionSheetNormalizationHealthV2120_() {
  return {
    status: "success",
    backend_version: "2.12.0",
    single_renderer: true,
    historical_cleanup_mode: "EXPLICIT_ROWS_ONLY",
    contract: AEGIS_NUTRITION_SHEET_NORMALIZATION_CONTRACT_V2120
  };
}

function testAegisNutritionSheetNormalizationV2120() {
  var fixtures = [
    ["1 Pack Chicken Ramen", "1 Pack Chicken Ramen", "Chicken Ramen", "1 package"],
    ["1 Serving Bear Naked Cacao & Cashew Butter", "1 Serving Bear Naked Cacao & Cashew Butter", "Bear Naked Cacao & Cashew Butter", "1 serving"],
    ["2tbl spoon chobani sweet cream creamer", "2 tbsp", "Chobani Sweet Cream Creamer", "2 tbsp"],
    ["Sheetz Fries 1 bag", "Sheetz Fries 1 bag", "Sheetz Fries", "1 bag"],
    ["fig Newtons", "4 cookies (58g)", "Fig Newtons", "4 cookies (58 g)"],
    ["chobani coffee yogurt", "1 container (5.3 oz)", "Chobani Coffee Yogurt", "1 container (5.3 oz)"]
  ];
  fixtures.forEach(function(fixture) {
    var normalized = normalizeAegisNutritionItemForSheetV2120_({
      item: fixture[0],
      portion: fixture[1]
    }, fixture[0]);
    if (normalized.item !== fixture[2] || normalized.portion !== fixture[3]) {
      throw new Error(
        "2.12.0 normalization failed for " + fixture[0] + ": " +
        JSON.stringify(normalized)
      );
    }
  });
  return {
    status: "PASS",
    fixture_count: fixtures.length,
    contract: AEGIS_NUTRITION_SHEET_NORMALIZATION_CONTRACT_V2120
  };
}
