const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const code = fs.readFileSync(path.resolve(__dirname, "..", "Code.gs"), "utf8");
assert.doesNotThrow(() => new vm.Script(code, { filename: "Code.gs" }));
assert.match(code, /const AEGIS_BACKEND_VERSION = "2\.12\.0";/);
assert.match(code, /calendar_sources_v1:\s*true/);
assert.match(code, /task_due_time_v1:\s*true/);
assert.match(code, /contents\.include_shared === true/);
assert.match(code, /CalendarApp\.getAllCalendars\(\)/);
assert.match(code, /calendar_id:/);
assert.match(code, /CalendarApp\.getCalendarById\(p\.calendar_id\)/);

const sandbox = {};
vm.createContext(sandbox);
const helpers = [
  code.match(/function splitAegisTaskDueTimeV211_\([\s\S]*?\n}/)[0],
  code.match(/function applyAegisTaskDueTimeV211_\([\s\S]*?\n}/)[0],
].join("\n");
vm.runInContext(helpers, sandbox);

const marked = sandbox.applyAegisTaskDueTimeV211_("Call dentist", "14:30");
assert.equal(marked, "Call dentist\n\n[AEGIS due time: 14:30]");
assert.deepEqual(
  JSON.parse(JSON.stringify(sandbox.splitAegisTaskDueTimeV211_(marked))),
  { notes: "Call dentist", due_time: "14:30" },
);
assert.equal(sandbox.applyAegisTaskDueTimeV211_(marked, ""), "Call dentist");
assert.throws(() => sandbox.applyAegisTaskDueTimeV211_("x", "25:99"));

console.log("PASS backend 2.12.0 retained Calendar sources and Task reminder-time validation");
