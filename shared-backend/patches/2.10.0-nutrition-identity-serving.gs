/**
 * AEGIS shared backend 2.10.0 -- KINETIC identity, trusted-food, and serving hardening.
 *
 * This module is additive to the 2.9.0 nightly reconciliation architecture.
 * It keeps AI out of capture-time arithmetic, separates product identity from
 * portion interpretation, and reuses only trusted product-specific records.
 */

var AEGIS_NUTRITION_IDENTITY_CONTRACT_V2100 =
  "AEGIS_NUTRITION_IDENTITY_GUARD_V1";
var AEGIS_NUTRITION_TRUSTED_FOOD_CONTRACT_V2100 =
  "AEGIS_NUTRITION_TRUSTED_FOOD_CATALOG_V2";
var AEGIS_NUTRITION_PORTION_CONTRACT_V2100 =
  "AEGIS_NUTRITION_PORTION_CONVERSION_V1";
var AEGIS_NUTRITION_TRUSTED_FOOD_SHEET_V2100 =
  "_AEGIS_TRUSTED_FOODS_V2";

var AEGIS_NUTRITION_TRUSTED_FOOD_HEADERS_V2100 = [
  "Product Key", "Brand", "Canonical Item", "Category",
  "Serving Amount", "Serving Unit", "Serving Grams",
  "Serving Milliliters", "Count Unit", "Count Per Serving",
  "Per Serving JSON", "Aliases JSON", "Source Authority", "Source URL",
  "Identity Confidence", "Nutrition Confidence", "Verified At ISO",
  "Last Used ISO", "Use Count", "Status"
];

function roundAegisNutritionV2100_(value) {
  return Math.round(Number(value) * 10) / 10;
}

function parseAegisQuantityNumberV2100_(value) {
  var text = String(value || "").trim().toLowerCase();
  var mixed = text.match(/^(\d+)\s+(\d+)\/(\d+)\b/);
  if (mixed && Number(mixed[3])) {
    return {
      value: Number(mixed[1]) + Number(mixed[2]) / Number(mixed[3]),
      token: mixed[0]
    };
  }
  var fraction = text.match(/^(\d+)\/(\d+)\b/);
  if (fraction && Number(fraction[2])) {
    return {
      value: Number(fraction[1]) / Number(fraction[2]),
      token: fraction[0]
    };
  }
  var decimal = text.match(/^(\d+(?:\.\d+)?)\b/);
  if (decimal) return { value: Number(decimal[1]), token: decimal[0] };
  var words = {
    a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5,
    six: 6, seven: 7, eight: 8, nine: 9, ten: 10, half: 0.5
  };
  var word = text.match(/^(a|an|one|two|three|four|five|six|seven|eight|nine|ten|half)\b/);
  return word ? { value: words[word[1]], token: word[0] } : null;
}

function canonicalAegisNutritionUnitV2100_(value) {
  var unit = String(value || "").toLowerCase()
    .replace(/\./g, "")
    .replace(/\s+/g, " ")
    .trim();
  var aliases = {
    g: "g", gram: "g", grams: "g",
    kg: "kg", kilogram: "kg", kilograms: "kg",
    oz: "oz", ounce: "oz", ounces: "oz",
    lb: "lb", pound: "lb", pounds: "lb",
    ml: "ml", milliliter: "ml", milliliters: "ml",
    l: "l", liter: "l", liters: "l",
    "fl oz": "fl oz", "fluid ounce": "fl oz", "fluid ounces": "fl oz",
    cup: "cup", cups: "cup",
    tbsp: "tbsp", tablespoon: "tbsp", tablespoons: "tbsp",
    tsp: "tsp", teaspoon: "tsp", teaspoons: "tsp",
    serving: "serving", servings: "serving", count: "count",
    pack: "package", packs: "package", packet: "package", packets: "package",
    package: "package", packages: "package", pouch: "package", pouches: "package",
    bottle: "bottle", bottles: "bottle", can: "can", cans: "can",
    cookie: "count", cookies: "count", bar: "count", bars: "count",
    slice: "count", slices: "count", piece: "count", pieces: "count",
    item: "count", items: "count"
  };
  return aliases[unit] || "";
}

function aegisNutritionUnitDimensionV2100_(unit) {
  if (["g", "kg", "oz", "lb"].indexOf(unit) >= 0) return "MASS";
  if (["ml", "l", "fl oz", "cup", "tbsp", "tsp"].indexOf(unit) >= 0) {
    return "VOLUME";
  }
  if (unit === "count") return "COUNT";
  if (["package", "bottle", "can"].indexOf(unit) >= 0) return "PACKAGE";
  if (unit === "serving") return "SERVING";
  return "UNKNOWN";
}

function aegisNutritionUnitBaseFactorV2100_(unit) {
  return {
    g: 1, kg: 1000, oz: 28.349523125, lb: 453.59237,
    ml: 1, l: 1000, "fl oz": 29.5735295625,
    cup: 236.5882365, tbsp: 14.78676478125, tsp: 4.92892159375
  }[unit] || 1;
}

function singularAegisCountUnitV2100_(value) {
  var text = String(value || "").toLowerCase().replace(/[^a-z]/g, "");
  var known = {
    cookies: "cookie", cookie: "cookie", bars: "bar", bar: "bar",
    slices: "slice", slice: "slice", pieces: "piece", piece: "piece",
    bottles: "bottle", bottle: "bottle", cans: "can", can: "can",
    packs: "package", pack: "package", packets: "package", packet: "package",
    packages: "package", package: "package", pouches: "package", pouch: "package"
  };
  return known[text] || (text.replace(/s$/, "") || "item");
}

function parseAegisNutritionPortionV2100_(input) {
  var original = String(input || "")
    .replace(/^\s*\/calories\b\s*/i, "")
    .replace(/\s+/g, " ")
    .trim();
  var quantity = parseAegisQuantityNumberV2100_(original);
  if (!quantity) {
    return {
      amount: 1,
      unit: "serving",
      dimension: "SERVING",
      count_unit: "",
      explicit: false,
      identity_text: original,
      original: original
    };
  }

  var remainder = original.slice(quantity.token.length).trim();
  var unitMatch = remainder.match(
    /^(fluid\s+ounces?|fl\.?\s*oz\.?|tablespoons?|tbsp\.?|teaspoons?|tsp\.?|kilograms?|kg|grams?|g|pounds?|lb|ounces?|oz|milliliters?|ml|liters?|l|cups?|servings?|packages?|packs?|packets?|pouches?|bottles?|cans?)\b/i
  );
  var rawUnit = unitMatch ? unitMatch[0] : "";
  var unit = canonicalAegisNutritionUnitV2100_(rawUnit);
  var countUnit = "";
  var identityText = remainder;
  if (unitMatch) {
    identityText = remainder.slice(unitMatch[0].length).trim();
    identityText = identityText.replace(
      /^\(\s*\d+(?:\.\d+)?\s*(?:g|grams?|ml|milliliters?)\s*\)\s*/i,
      ""
    );
    if (aegisNutritionUnitDimensionV2100_(unit) === "PACKAGE") {
      countUnit = singularAegisCountUnitV2100_(rawUnit);
    }
  } else {
    var trailing = remainder.match(
      /\b(cookies?|bars?|slices?|pieces?|bottles?|cans?|packages?|packs?|packets?|pouches?)\s*$/i
    );
    if (trailing) {
      unit = canonicalAegisNutritionUnitV2100_(trailing[1]);
      countUnit = singularAegisCountUnitV2100_(trailing[1]);
    } else {
      unit = "count";
      countUnit = "item";
    }
  }

  return {
    amount: Math.max(0, Number(quantity.value) || 0),
    unit: unit || "count",
    dimension: aegisNutritionUnitDimensionV2100_(unit || "count"),
    count_unit: countUnit,
    explicit: true,
    identity_text: identityText || original,
    original: original
  };
}

function normalizeAegisProductTokenV2100_(token) {
  var value = String(token || "").toLowerCase();
  var known = {
    cookies: "cookie",
    brownies: "brownie",
    candies: "candy",
    berries: "berry"
  };
  if (known[value]) return known[value];
  if (/ies$/.test(value) && value.length > 4) return value.slice(0, -3) + "y";
  if (/s$/.test(value) && !/ss$/.test(value) && value.length > 3) {
    return value.slice(0, -1);
  }
  return value;
}

function aegisNutritionIdentityTokensV2100_(value) {
  var parsed = parseAegisNutritionPortionV2100_(value);
  var ignored = {
    a: true, an: true, the: true, of: true, with: true, and: true,
    serving: true, package: true, pack: true, packet: true, pouch: true,
    medium: true, large: true, small: true, frozen: true, prepared: true
  };
  return normalizeNutritionIdentityTextV284_(parsed.identity_text)
    .split(" ")
    .map(normalizeAegisProductTokenV2100_)
    .filter(function(token) {
      return token.length >= 2 && !ignored[token] && !/^\d/.test(token);
    });
}

function aegisNutritionProductKeyV2100_(value) {
  return aegisNutritionIdentityTokensV2100_(value).join("-");
}

function classifyAegisNutritionCategoryV2100_(value) {
  var text = normalizeNutritionIdentityTextV284_(
    parseAegisNutritionPortionV2100_(value).identity_text
  );
  var rules = [
    ["YOGURT", /\b(yogurt|yoghurt)\b/],
    ["GRANOLA", /\b(granola|muesli)\b/],
    ["NOODLES", /\b(ramen|noodle|noodles)\b/],
    ["COOKIE", /\b(cookie|cookies|fig newton|newtons)\b/],
    ["NUT_SPREAD", /\b(peanut butter|almond butter|cashew butter|nut butter)\b/],
    ["POULTRY", /\b(chicken|turkey)\b/],
    ["BURGER", /\b(burger|hamburger|cheeseburger)\b/],
    ["BREAD", /\b(bread|toast|bagel|tortilla|wrap)\b/],
    ["EGG", /\b(egg|eggs)\b/],
    ["COFFEE_BEVERAGE", /\b(coffee|espresso|latte|cappuccino|americano)\b/],
    ["BEVERAGE", /\b(drink|shake|smoothie|juice|soda|water)\b/],
    ["SAUCE", /\b(sauce|salsa|dressing|syrup)\b/],
    ["RICE", /\b(rice|rice a roni)\b/],
    ["FRUIT", /\b(banana|apple|orange|berry|berries)\b/]
  ];
  for (var i = 0; i < rules.length; i++) {
    if (rules[i][1].test(text)) return rules[i][0];
  }
  return "UNCLASSIFIED";
}

function detectAegisNutritionBrandV2100_(value) {
  var text = normalizeNutritionIdentityTextV284_(value);
  var brands = [
    "williams gourmet kitchen", "rice a roni", "texas pete", "bear naked",
    "fig newton", "chobani", "tyson", "kirkland", "mission", "nabisco",
    "mcdonalds", "burger king", "wendys", "taco bell", "chipotle",
    "subway", "panera", "starbucks", "chick fil a", "popeyes", "kfc",
    "five guys", "shake shack", "olive garden", "applebees",
    "buffalo wild wings", "noodles and company"
  ];
  for (var i = 0; i < brands.length; i++) {
    if (text.indexOf(brands[i]) >= 0) return brands[i];
  }
  return "";
}

function scoreAegisNutritionIdentityV2100_(input, candidate) {
  var inputTokens = aegisNutritionIdentityTokensV2100_(input);
  var outputTokens = aegisNutritionIdentityTokensV2100_(candidate);
  var outputSet = {};
  outputTokens.forEach(function(token) { outputSet[token] = true; });
  var matched = inputTokens.filter(function(token) { return outputSet[token]; });
  return inputTokens.length ? matched.length / inputTokens.length : 0;
}

function validateAegisNutritionIdentityV2100_(input, candidate) {
  var inputBrand = detectAegisNutritionBrandV2100_(input);
  var candidateBrand = detectAegisNutritionBrandV2100_(candidate);
  var inputCategory = classifyAegisNutritionCategoryV2100_(input);
  var candidateCategory = classifyAegisNutritionCategoryV2100_(candidate);
  var score = scoreAegisNutritionIdentityV2100_(input, candidate);

  if (inputBrand && candidateBrand !== inputBrand) {
    return {
      ok: false,
      confidence: "LOW",
      score: score,
      reason: "The returned item did not preserve the submitted brand/product identity."
    };
  }
  if (
    inputCategory !== "UNCLASSIFIED" &&
    candidateCategory !== "UNCLASSIFIED" &&
    inputCategory !== candidateCategory
  ) {
    return {
      ok: false,
      confidence: "LOW",
      score: score,
      reason: "The returned item changed food category from " +
        inputCategory + " to " + candidateCategory + "."
    };
  }
  if (score < (inputBrand ? 0.5 : 0.6)) {
    return {
      ok: false,
      confidence: "LOW",
      score: score,
      reason: "The returned item did not retain enough product-specific terms."
    };
  }
  return {
    ok: true,
    confidence: score >= 0.9 ? "HIGH" : "MEDIUM",
    score: score,
    reason: ""
  };
}

function isAegisNutritionIdentitySafeV2100_(input, item) {
  return validateAegisNutritionIdentityV2100_(
    input,
    item && item.item
  ).ok;
}

function parseAegisCanonicalServingV2100_(raw, fallbackPortion) {
  var amount = Number(raw && raw.canonical_serving_amount);
  var unit = canonicalAegisNutritionUnitV2100_(
    raw && raw.canonical_serving_unit
  );
  if (!(amount > 0) || !unit) {
    var fallback = parseAegisNutritionPortionV2100_(fallbackPortion);
    amount = fallback.amount;
    unit = fallback.unit;
  }
  var grams = Number(raw && raw.canonical_serving_grams);
  var milliliters = Number(raw && raw.canonical_serving_milliliters);
  var fallbackText = String(fallbackPortion || "");
  var metric = fallbackText.match(
    /(?:\(|\b)(\d+(?:\.\d+)?)\s*(g|grams?|ml|milliliters?)(?:\)|\b)/i
  );
  if (!(grams > 0) && metric && /^g/i.test(metric[2])) {
    grams = Number(metric[1]);
  }
  if (!(milliliters > 0) && metric && /^m/i.test(metric[2])) {
    milliliters = Number(metric[1]);
  }
  var countPerServing = Number(raw && raw.canonical_count_per_serving);
  var countUnit = singularAegisCountUnitV2100_(
    raw && raw.canonical_count_unit
  );
  return {
    amount: amount > 0 ? amount : 1,
    unit: unit || "serving",
    grams: grams > 0 ? grams : null,
    milliliters: milliliters > 0 ? milliliters : null,
    count_unit: countUnit === "item" && !countPerServing ? "" : countUnit,
    count_per_serving: countPerServing > 0 ? countPerServing : null
  };
}

function calculateAegisServingFactorV2100_(requested, record) {
  if (!requested || !(requested.amount > 0) || !record) return null;
  var unit = requested.unit;
  var dimension = aegisNutritionUnitDimensionV2100_(unit);
  if (dimension === "SERVING") return requested.amount;
  if (dimension === "MASS" && Number(record.servingGrams) > 0) {
    return requested.amount * aegisNutritionUnitBaseFactorV2100_(unit) /
      Number(record.servingGrams);
  }
  if (dimension === "VOLUME" && Number(record.servingMilliliters) > 0) {
    return requested.amount * aegisNutritionUnitBaseFactorV2100_(unit) /
      Number(record.servingMilliliters);
  }
  if (
    dimension === "COUNT" &&
    Number(record.countPerServing) > 0 &&
    (!record.countUnit || !requested.count_unit ||
      record.countUnit === requested.count_unit)
  ) {
    return requested.amount / Number(record.countPerServing);
  }
  var servingUnit = canonicalAegisNutritionUnitV2100_(record.servingUnit);
  if (unit === servingUnit && Number(record.servingAmount) > 0) {
    return requested.amount / Number(record.servingAmount);
  }
  return null;
}

function formatAegisPortionAmountV2100_(value) {
  var number = roundAegisNutritionV2100_(value);
  return String(number).replace(/\.0$/, "");
}

function formatAegisRequestedPortionV2100_(requested, record, factor) {
  var unit = requested.unit;
  var label = unit;
  if (unit === "count") label = requested.count_unit || "item";
  if (
    requested.amount !== 1 &&
    !/s$/.test(label) &&
    ["g", "kg", "oz", "lb", "ml", "l", "fl oz", "tbsp", "tsp"]
      .indexOf(label) < 0
  ) label += "s";
  var text = formatAegisPortionAmountV2100_(requested.amount) + " " + label;
  if (
    factor !== null &&
    Number(record && record.servingGrams) > 0 &&
    requested.dimension !== "MASS"
  ) {
    text += " (" +
      formatAegisPortionAmountV2100_(Number(record.servingGrams) * factor) +
      " g)";
  }
  return text;
}

function scaleAegisTrustedNutritionV2100_(record, requested, factor) {
  var base = record.perServing || {};
  function scaled(name) {
    if (base[name] === null || typeof base[name] === "undefined") return null;
    return roundAegisNutritionV2100_(Number(base[name]) * factor);
  }
  return {
    item: record.canonicalItem,
    portion: formatAegisRequestedPortionV2100_(requested, record, factor),
    calories: scaled("calories"),
    protein: scaled("protein"),
    carbs: scaled("carbs"),
    fat: scaled("fat"),
    saturated_fat: scaled("saturated_fat"),
    fiber: scaled("fiber"),
    sugar: scaled("sugar"),
    sodium: scaled("sodium"),
    cholesterol: scaled("cholesterol"),
    source_type: base.source_type || "MODEL_ESTIMATE",
    source_url: record.sourceUrl || base.source_url || "",
    confidence: record.nutritionConfidence || base.confidence || "MEDIUM",
    lookup_depth: Number(base.lookup_depth) || 1,
    assumptions: [
      String(base.assumptions || "").trim(),
      "Reused from the trusted KINETIC food catalog; portion arithmetic was performed locally."
    ].filter(Boolean).join(" "),
    conservative_adjustment: base.conservative_adjustment === true,
    identity_confidence: record.identityConfidence || "MEDIUM",
    portion_confidence: "HIGH",
    nutrition_confidence: record.nutritionConfidence || base.confidence || "MEDIUM",
    source_authority: record.sourceAuthority || ""
  };
}

function builtInAegisTrustedFoodV2100_(segment) {
  var key = aegisNutritionProductKeyV2100_(segment);
  if (
    key !== "fig-newton-cookie" &&
    key !== "fig-newton" &&
    key !== "nabisco-fig-newton-cookie"
  ) return null;
  return {
    productKey: "fig-newton-cookie",
    brand: "fig newton",
    canonicalItem: "Fig Newton cookies",
    category: "COOKIE",
    servingAmount: 1,
    servingUnit: "serving",
    servingGrams: 58,
    servingMilliliters: null,
    countUnit: "cookie",
    countPerServing: 4,
    perServing: {
      calories: 200, protein: 2, carbs: 42, fat: 4,
      saturated_fat: 0, fiber: 2, sugar: 24, sodium: 190,
      cholesterol: 0, source_type: "OFFICIAL",
      source_url: "https://www.snackworks.com/", confidence: "MEDIUM",
      lookup_depth: 2,
      assumptions: "Stored product-specific serving reference; verify the package if formulation differs.",
      conservative_adjustment: true
    },
    aliases: ["fig-newton", "nabisco-fig-newton-cookie"],
    sourceAuthority: "HIGH",
    sourceUrl: "https://www.snackworks.com/",
    identityConfidence: "HIGH",
    nutritionConfidence: "MEDIUM",
    status: "TRUSTED"
  };
}

function getAegisTrustedFoodsSheetV2100_(spreadsheet) {
  return getAegisManagedSheetV290_(
    spreadsheet,
    AEGIS_NUTRITION_TRUSTED_FOOD_SHEET_V2100,
    AEGIS_NUTRITION_TRUSTED_FOOD_HEADERS_V2100
  );
}

function trustedAegisFoodFromRowV2100_(row) {
  var perServing;
  var aliases;
  try {
    perServing = JSON.parse(String(row[10] || "{}"));
    aliases = JSON.parse(String(row[11] || "[]"));
  } catch (ignored) {
    return null;
  }
  if (String(row[19] || "").toUpperCase() !== "TRUSTED") return null;
  return {
    productKey: String(row[0] || ""),
    brand: String(row[1] || ""),
    canonicalItem: String(row[2] || ""),
    category: String(row[3] || "UNCLASSIFIED"),
    servingAmount: Number(row[4]) || 1,
    servingUnit: String(row[5] || "serving"),
    servingGrams: Number(row[6]) || null,
    servingMilliliters: Number(row[7]) || null,
    countUnit: String(row[8] || ""),
    countPerServing: Number(row[9]) || null,
    perServing: perServing,
    aliases: Array.isArray(aliases) ? aliases : [],
    sourceAuthority: String(row[12] || ""),
    sourceUrl: String(row[13] || ""),
    identityConfidence: String(row[14] || "MEDIUM"),
    nutritionConfidence: String(row[15] || "MEDIUM"),
    status: "TRUSTED"
  };
}

function touchAegisTrustedFoodV2100_(sheet, rowNumber, useCount) {
  sheet.getRange(rowNumber, 18).setValue(new Date().toISOString());
  sheet.getRange(rowNumber, 19).setValue((Number(useCount) || 0) + 1);
}

function selectAegisTrustedFoodV2100_(records, segment) {
  var key = aegisNutritionProductKeyV2100_(segment);
  var inputBrand = detectAegisNutritionBrandV2100_(segment);
  var inputCategory = classifyAegisNutritionCategoryV2100_(segment);
  var best = null;
  (records || []).forEach(function(record) {
    if (!record || record.status !== "TRUSTED") return;
    var keys = [record.productKey].concat(record.aliases || []);
    var exact = keys.indexOf(key) >= 0;
    var candidateBrand = record.brand || detectAegisNutritionBrandV2100_(
      record.canonicalItem
    );
    var sameBrand = !inputBrand || candidateBrand === inputBrand;
    var sameCategory = inputCategory === "UNCLASSIFIED" ||
      record.category === "UNCLASSIFIED" ||
      record.category === inputCategory;
    var score = scoreAegisNutritionIdentityV2100_(
      segment,
      record.canonicalItem
    );
    if (
      sameBrand &&
      sameCategory &&
      (exact || score >= 0.8) &&
      (!best || score > best.score || (exact && !best.exact))
    ) {
      best = { record: record, score: exact ? 1 : score, exact: exact };
    }
  });
  return best ? best.record : null;
}

function findAegisTrustedFoodV2100_(spreadsheet, segment) {
  var builtIn = builtInAegisTrustedFoodV2100_(segment);
  if (builtIn) return builtIn;
  var sheet = getAegisTrustedFoodsSheetV2100_(spreadsheet);
  if (sheet.getLastRow() < 2) return null;
  var rows = sheet.getRange(
    2, 1, sheet.getLastRow() - 1,
    AEGIS_NUTRITION_TRUSTED_FOOD_HEADERS_V2100.length
  ).getValues();
  var records = rows.map(function(row, index) {
    var record = trustedAegisFoodFromRowV2100_(row);
    if (!record) return null;
    record._sheetRowV2100 = index + 2;
    record._useCountV2100 = row[18];
    return record;
  }).filter(Boolean);
  var selected = selectAegisTrustedFoodV2100_(records, segment);
  if (!selected) return null;
  touchAegisTrustedFoodV2100_(
    sheet,
    selected._sheetRowV2100,
    selected._useCountV2100
  );
  return selected;
}

function resolveAegisTrustedNutritionV2100_(spreadsheet, segment) {
  var record = findAegisTrustedFoodV2100_(spreadsheet, segment);
  if (!record) return null;
  var requested = parseAegisNutritionPortionV2100_(segment);
  var factor = calculateAegisServingFactorV2100_(requested, record);
  if (!(factor > 0) || factor > 100) return null;
  return {
    items: [scaleAegisTrustedNutritionV2100_(record, requested, factor)],
    confidence: record.nutritionConfidence || "MEDIUM",
    overall_confidence: record.nutritionConfidence || "MEDIUM",
    lookup_depth: 1,
    trusted_food_match: true
  };
}

function isAegisNutritionPortionEquivalentV2100_(segment, item) {
  var requested = parseAegisNutritionPortionV2100_(segment);
  var returned = parseAegisNutritionPortionV2100_(
    item && item.portion
  );
  if (!requested.explicit) return true;
  if (!returned.explicit) return false;
  if (
    requested.unit === returned.unit &&
    Math.abs(requested.amount - returned.amount) < 0.001
  ) return true;
  var raw = item || {};
  var serving = parseAegisCanonicalServingV2100_(raw, item && item.portion);
  var record = {
    servingAmount: serving.amount,
    servingUnit: serving.unit,
    servingGrams: serving.grams,
    servingMilliliters: serving.milliliters,
    countUnit: serving.count_unit,
    countPerServing: serving.count_per_serving
  };
  var expected = calculateAegisServingFactorV2100_(requested, record);
  var stated = Number(raw.submitted_serving_factor);
  return expected !== null && stated > 0 &&
    Math.abs(expected - stated) <= Math.max(0.01, expected * 0.02);
}

function hardenAegisNightlyNutritionItemV2100_(segment, validated, raw) {
  var identity = validateAegisNutritionIdentityV2100_(
    segment,
    validated.item
  );
  validated.identity_status = identity.ok ? "MATCH" : "MISMATCH";
  validated.identity_confidence = identity.confidence;
  validated.identity_reason = identity.reason;
  validated.portion_status = isAegisNutritionPortionEquivalentV2100_(
    segment,
    raw || validated
  ) ? "MATCH" : "MISMATCH";
  validated.portion_confidence =
    validated.portion_status === "MATCH" ? "HIGH" : "LOW";
  validated.nutrition_confidence = validated.confidence;
  validated.aegis_serving = parseAegisCanonicalServingV2100_(
    raw,
    validated.portion
  );
  if (identity.ok) {
    validated.item = parseAegisNutritionPortionV2100_(segment).identity_text;
  } else {
    validated.assumptions = [
      String(validated.assumptions || "").trim(),
      identity.reason
    ].filter(Boolean).join(" ");
  }
  return validated;
}

function hasAegisNutritionReviewMismatchV2100_(reviewed) {
  return (reviewed.items || []).some(function(item) {
    return item.identity_status === "MISMATCH" ||
      item.portion_status === "MISMATCH";
  });
}

function nutritionValuesPerServingV2100_(item, factor) {
  var divisor = factor > 0 ? factor : 1;
  function divided(name) {
    if (item[name] === null || typeof item[name] === "undefined") return null;
    return roundAegisNutritionV2100_(Number(item[name]) / divisor);
  }
  return {
    calories: divided("calories"),
    protein: divided("protein"),
    carbs: divided("carbs"),
    fat: divided("fat"),
    saturated_fat: divided("saturated_fat"),
    fiber: divided("fiber"),
    sugar: divided("sugar"),
    sodium: divided("sodium"),
    cholesterol: divided("cholesterol"),
    source_type: item.source_type,
    source_url: item.source_url,
    confidence: item.confidence,
    lookup_depth: item.lookup_depth,
    assumptions: item.assumptions,
    conservative_adjustment: item.conservative_adjustment === true
  };
}

function buildAegisTrustedFoodRecordV2100_(segment, item) {
  if (
    !item ||
    item.identity_status === "MISMATCH" ||
    item.portion_status === "MISMATCH" ||
    confidenceRankAegisV290_(item.confidence) < 3
  ) return null;
  var identity = parseAegisNutritionPortionV2100_(segment);
  var serving = item.aegis_serving ||
    parseAegisCanonicalServingV2100_(item, item.portion);
  var recordForFactor = {
    servingAmount: serving.amount,
    servingUnit: serving.unit,
    servingGrams: serving.grams,
    servingMilliliters: serving.milliliters,
    countUnit: serving.count_unit,
    countPerServing: serving.count_per_serving
  };
  var factor = calculateAegisServingFactorV2100_(identity, recordForFactor);
  if (!(factor > 0)) {
    serving.amount = identity.amount;
    serving.unit = identity.unit;
    serving.count_unit = identity.count_unit;
    serving.count_per_serving = identity.dimension === "COUNT"
      ? identity.amount
      : null;
    serving.grams = identity.dimension === "MASS"
      ? identity.amount * aegisNutritionUnitBaseFactorV2100_(identity.unit)
      : null;
    serving.milliliters = identity.dimension === "VOLUME"
      ? identity.amount * aegisNutritionUnitBaseFactorV2100_(identity.unit)
      : null;
    factor = 1;
  }
  return {
    productKey: aegisNutritionProductKeyV2100_(identity.identity_text),
    brand: detectAegisNutritionBrandV2100_(identity.identity_text),
    canonicalItem: identity.identity_text,
    category: classifyAegisNutritionCategoryV2100_(identity.identity_text),
    servingAmount: serving.amount,
    servingUnit: serving.unit,
    servingGrams: serving.grams,
    servingMilliliters: serving.milliliters,
    countUnit: serving.count_unit,
    countPerServing: serving.count_per_serving,
    perServing: nutritionValuesPerServingV2100_(item, factor),
    aliases: [aegisNutritionProductKeyV2100_(item.item)],
    sourceAuthority: item.source_authority ||
      classifyAegisNutritionSourceV290_(item),
    sourceUrl: item.source_url || "",
    identityConfidence: item.identity_confidence || "MEDIUM",
    nutritionConfidence: item.confidence,
    status: "TRUSTED"
  };
}

function upsertAegisTrustedFoodV2100_(spreadsheet, segment, item) {
  var record = buildAegisTrustedFoodRecordV2100_(segment, item);
  if (!record) return false;
  if (["HIGH", "MEDIUM"].indexOf(record.sourceAuthority) < 0) return false;
  var sheet = getAegisTrustedFoodsSheetV2100_(spreadsheet);
  var finder = sheet.getLastRow() < 2 ? null :
    sheet.getRange(2, 1, sheet.getLastRow() - 1, 1)
      .createTextFinder(record.productKey)
      .matchEntireCell(true)
      .findNext();
  var now = new Date().toISOString();
  var previousUses = finder
    ? Number(sheet.getRange(finder.getRow(), 19).getValue()) || 0
    : 0;
  var values = [
    record.productKey, record.brand, record.canonicalItem, record.category,
    record.servingAmount, record.servingUnit, record.servingGrams || "",
    record.servingMilliliters || "", record.countUnit || "",
    record.countPerServing || "", JSON.stringify(record.perServing),
    JSON.stringify(record.aliases), record.sourceAuthority, record.sourceUrl,
    record.identityConfidence, record.nutritionConfidence,
    now, now, previousUses + 1, "TRUSTED"
  ];
  if (finder) {
    sheet.getRange(finder.getRow(), 1, 1, values.length).setValues([values]);
  } else {
    sheet.appendRow(values);
  }
  return true;
}

function importAegisTrustedNutritionHistoryV2100() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  var nutritionSheet = getAegisNutritionDataSheetV282_(spreadsheet);
  getAegisTrustedFoodsSheetV2100_(spreadsheet);
  if (nutritionSheet.getLastRow() < 2) {
    return { status: "PASS", imported: 0, skipped: 0 };
  }
  var rows = nutritionSheet.getRange(
    2, 1, nutritionSheet.getLastRow() - 1,
    Math.max(24, nutritionSheet.getLastColumn())
  ).getValues();
  var imported = 0;
  var skipped = 0;
  rows.forEach(function(row) {
    var item = nutritionItemFromSheetRowV290_(row);
    item.source_authority = classifyAegisNutritionSourceV290_(item);
    item.identity_status = "MATCH";
    item.identity_confidence = "MEDIUM";
    item.portion_status = "MATCH";
    item.portion_confidence = "MEDIUM";
    item.nutrition_confidence = item.confidence;
    item.aegis_serving = parseAegisCanonicalServingV2100_(
      item,
      item.portion
    );
    var verification = String(row[21] || "").toUpperCase();
    var safeStatus = ["VERIFIED", "ADJUSTED", "CONFIRMED"].indexOf(
      verification
    ) >= 0;
    if (
      safeStatus &&
      confidenceRankAegisV290_(item.confidence) >= 3 &&
      ["HIGH", "MEDIUM"].indexOf(item.source_authority) >= 0 &&
      upsertAegisTrustedFoodV2100_(
        spreadsheet,
        String(item.portion || "1 serving") + " " + String(item.item || ""),
        item
      )
    ) {
      imported++;
    } else {
      skipped++;
    }
  });
  return {
    status: "PASS",
    backend_version: "2.10.0",
    imported: imported,
    skipped: skipped,
    policy: "Only previously confirmed medium/high-confidence rows with medium/high source authority were trusted automatically."
  };
}

function installAegisNutritionHardeningV2100() {
  var spreadsheet = SpreadsheetApp.openById(CONFIG.CALORIES_SHEET_ID);
  getAegisTrustedFoodsSheetV2100_(spreadsheet);
  var result = getAegisNutritionHardeningHealthV2100_();
  result.status = "PASS";
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}

function getAegisNutritionHardeningHealthV2100_() {
  return {
    status: "success",
    backend_version: "2.10.0",
    identity_contract: AEGIS_NUTRITION_IDENTITY_CONTRACT_V2100,
    trusted_food_contract: AEGIS_NUTRITION_TRUSTED_FOOD_CONTRACT_V2100,
    portion_contract: AEGIS_NUTRITION_PORTION_CONTRACT_V2100
  };
}

function testAegisNutritionIdentityServingV2100() {
  var chobani = validateAegisNutritionIdentityV2100_(
    "Chobani Coffee Yogurt",
    "Black coffee"
  );
  if (chobani.ok) throw new Error("2.10.0 Chobani category guard failed.");
  var fig = resolveAegisTrustedNutritionV2100_(
    { getSheetByName: function() { return null; } },
    "4 Fig Newton cookies"
  );
  if (!fig || fig.items[0].calories !== 200) {
    throw new Error("2.10.0 Fig Newton count conversion failed.");
  }
  var peanutButter = {
    servingAmount: 1,
    servingUnit: "serving",
    servingGrams: 32,
    servingMilliliters: null,
    countUnit: "",
    countPerServing: null
  };
  var tbsp = calculateAegisServingFactorV2100_(
    parseAegisNutritionPortionV2100_("1 tbsp peanut butter"),
    {
      servingAmount: 2,
      servingUnit: "tbsp",
      servingGrams: 32,
      servingMilliliters: null,
      countUnit: "",
      countPerServing: null
    }
  );
  var grams = calculateAegisServingFactorV2100_(
    parseAegisNutritionPortionV2100_("16 g peanut butter"),
    peanutButter
  );
  if (tbsp !== 0.5 || grams !== 0.5) {
    throw new Error("2.10.0 product-specific tbsp/gram conversion failed.");
  }
  var result = {
    status: "PASS",
    chobani_black_coffee_rejected: true,
    fig_newtons_four_cookies_servings: 1,
    tablespoon_factor: tbsp,
    gram_factor: grams,
    identity_contract: AEGIS_NUTRITION_IDENTITY_CONTRACT_V2100,
    trusted_food_contract: AEGIS_NUTRITION_TRUSTED_FOOD_CONTRACT_V2100,
    portion_contract: AEGIS_NUTRITION_PORTION_CONTRACT_V2100
  };
  Logger.log(JSON.stringify(result, null, 2));
  return result;
}
