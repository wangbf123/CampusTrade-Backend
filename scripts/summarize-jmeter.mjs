#!/usr/bin/env node

import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";

const input = process.argv[2];
const outputDir = process.argv[3] || "docs/pressure-results";

if (!input) {
  console.error("Usage: node scripts/summarize-jmeter.mjs <result.jtl> [outputDir]");
  process.exit(1);
}

function percentile(values, p) {
  if (values.length === 0) {
    return 0;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[index];
}

function csvSplit(line) {
  const values = [];
  let value = "";
  let quoted = false;
  for (let index = 0; index < line.length; index++) {
    const char = line[index];
    if (char === '"') {
      if (quoted && line[index + 1] === '"') {
        value += '"';
        index++;
      } else {
        quoted = !quoted;
      }
    } else if (char === "," && !quoted) {
      values.push(value);
      value = "";
    } else {
      value += char;
    }
  }
  values.push(value);
  return values;
}

function summarize(rows) {
  const groups = new Map();
  for (const row of rows) {
    if (row.label.startsWith("setup_") || row.label.startsWith("warmup_")) {
      continue;
    }
    if (!groups.has(row.label)) {
      groups.set(row.label, []);
    }
    groups.get(row.label).push(row);
  }

  return [...groups.entries()].map(([label, samples]) => {
    const elapsed = samples.map((sample) => sample.elapsed);
    const firstTs = Math.min(...samples.map((sample) => sample.timeStamp));
    const lastEnd = Math.max(...samples.map((sample) => sample.timeStamp + sample.elapsed));
    const elapsedSeconds = Math.max((lastEnd - firstTs) / 1000, 0.001);
    const status = samples.reduce((acc, sample) => {
      acc[sample.responseCode] = (acc[sample.responseCode] || 0) + 1;
      return acc;
    }, {});
    const success = samples.filter((sample) => sample.success).length;

    return {
      label,
      samples: samples.length,
      throughput: Number((samples.length / elapsedSeconds).toFixed(2)),
      avgMs: Number((elapsed.reduce((sum, value) => sum + value, 0) / elapsed.length).toFixed(2)),
      minMs: Math.min(...elapsed),
      p50Ms: percentile(elapsed, 50),
      p90Ms: percentile(elapsed, 90),
      p95Ms: percentile(elapsed, 95),
      p99Ms: percentile(elapsed, 99),
      maxMs: Math.max(...elapsed),
      success,
      failures: samples.length - success,
      status,
    };
  });
}

function markdown(report) {
  const rows = report.scenarios
    .map((scenario) => `| ${scenario.label} | ${scenario.samples} | ${scenario.throughput} | ${scenario.avgMs} | ${scenario.p95Ms} | ${scenario.p99Ms} | ${scenario.failures} | ${JSON.stringify(scenario.status)} |`)
    .join("\n");

  return `# CampusTrade JMeter Pressure Test Result

## Test Environment

| Item | Value |
|---|---|
| Time | ${report.startedAt} |
| JTL | ${report.input} |
| Profile | ${report.profile} |
| Base URL | ${report.baseUrl} |
| Tool | Apache JMeter 5.6.3 |

## Result Summary

| Scenario | Requests | Throughput(req/s) | Avg(ms) | P95(ms) | P99(ms) | Failures | Status |
|---|---:|---:|---:|---:|---:|---:|---|
${rows}

## Notes

- setup_* and warmup_* samples are excluded from the summary table.
- HTTP 429 in appointment_rate_limit_probe is expected when the appointment rate limit window has been filled.
- This is local-machine pressure evidence, not a production capacity promise.
`;
}

const content = await readFile(input, "utf8");
const lines = content.trim().split(/\r?\n/);
const headers = csvSplit(lines.shift());
const rows = lines
  .filter(Boolean)
  .map((line) => {
    const values = csvSplit(line);
    const row = Object.fromEntries(headers.map((header, index) => [header, values[index] ?? ""]));
    return {
      timeStamp: Number(row.timeStamp),
      elapsed: Number(row.elapsed),
      label: row.label,
      responseCode: row.responseCode,
      success: row.success === "true",
    };
  });

const report = {
  startedAt: new Date().toISOString(),
  input,
  profile: process.env.SPRING_PROFILES_ACTIVE || "default",
  baseUrl: process.env.BASE_URL || "http://localhost:8080",
  scenarios: summarize(rows),
};

await mkdir(outputDir, { recursive: true });
const base = path.basename(input, path.extname(input));
const jsonPath = path.join(outputDir, `${base}.summary.json`);
const mdPath = path.join(outputDir, `${base}.summary.md`);

await writeFile(jsonPath, JSON.stringify(report, null, 2), "utf8");
await writeFile(mdPath, markdown(report), "utf8");

console.log(JSON.stringify({ jsonPath, mdPath, scenarios: report.scenarios }, null, 2));
