#!/usr/bin/env node

import { writeFile, mkdir } from "node:fs/promises";
import { performance } from "node:perf_hooks";

const baseUrl = process.env.BASE_URL || "http://localhost:8080";
const outputDir = process.env.OUTPUT_DIR || "docs/pressure-results";
const warmupRequests = Number(process.env.WARMUP_REQUESTS || 50);
const scenarioRequests = Number(process.env.SCENARIO_REQUESTS || 300);
const concurrency = Number(process.env.CONCURRENCY || 30);
const appointmentRequests = Number(process.env.APPOINTMENT_REQUESTS || 12);

const nowTag = new Date().toISOString().replace(/[:.]/g, "-");

function percentile(values, p) {
  if (values.length === 0) {
    return 0;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const index = Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1);
  return sorted[index];
}

async function request(method, path, { token, headers = {}, body } = {}) {
  const requestHeaders = {
    Accept: "application/json",
    ...headers,
  };
  if (body !== undefined) {
    requestHeaders["Content-Type"] = "application/json";
  }
  if (token) {
    requestHeaders.Authorization = `Bearer ${token}`;
  }

  const startedAt = performance.now();
  let status = 0;
  let payload = null;
  let error = null;
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    status = response.status;
    const text = await response.text();
    payload = text ? JSON.parse(text) : null;
  } catch (exception) {
    error = exception.message;
  }
  const durationMs = performance.now() - startedAt;
  return { status, durationMs, payload, error };
}

async function login(username, password = "123456") {
  const response = await request("POST", "/api/auth/login", {
    body: { username, password },
  });
  if (response.status !== 200 || response.payload?.code !== 0) {
    throw new Error(`Login failed for ${username}: ${JSON.stringify(response)}`);
  }
  return response.payload.data.token;
}

async function getFirstOnSaleItemId() {
  const response = await request("GET", "/api/items");
  if (response.status !== 200 || response.payload?.code !== 0 || response.payload.data.length === 0) {
    throw new Error(`No demo item found: ${JSON.stringify(response)}`);
  }
  return response.payload.data[0].id;
}

async function runScenario(name, totalRequests, maxConcurrency, factory) {
  const results = [];
  let nextIndex = 0;
  const startedAt = performance.now();

  async function worker() {
    while (nextIndex < totalRequests) {
      const index = nextIndex++;
      results.push(await factory(index));
    }
  }

  const workers = Array.from({ length: Math.min(maxConcurrency, totalRequests) }, () => worker());
  await Promise.all(workers);

  const elapsedMs = performance.now() - startedAt;
  const durations = results.map((result) => result.durationMs);
  const success = results.filter((result) => result.status >= 200 && result.status < 300 && !result.error).length;
  const byStatus = results.reduce((acc, result) => {
    const key = String(result.status || "ERR");
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});

  return {
    name,
    totalRequests,
    concurrency: maxConcurrency,
    elapsedMs: Number(elapsedMs.toFixed(2)),
    throughput: Number((totalRequests / (elapsedMs / 1000)).toFixed(2)),
    success,
    failures: totalRequests - success,
    status: byStatus,
    avgMs: Number((durations.reduce((sum, value) => sum + value, 0) / durations.length).toFixed(2)),
    minMs: Number(Math.min(...durations).toFixed(2)),
    p50Ms: Number(percentile(durations, 50).toFixed(2)),
    p90Ms: Number(percentile(durations, 90).toFixed(2)),
    p95Ms: Number(percentile(durations, 95).toFixed(2)),
    p99Ms: Number(percentile(durations, 99).toFixed(2)),
    maxMs: Number(Math.max(...durations).toFixed(2)),
  };
}

function markdownReport(report) {
  const rows = report.scenarios
    .map((scenario) => `| ${scenario.name} | ${scenario.totalRequests} | ${scenario.concurrency} | ${scenario.throughput} | ${scenario.avgMs} | ${scenario.p95Ms} | ${scenario.p99Ms} | ${scenario.failures} | ${JSON.stringify(scenario.status)} |`)
    .join("\n");

  return `# CampusTrade Pressure Test Result

## Test Environment

| Item | Value |
|---|---|
| Time | ${report.startedAt} |
| Base URL | ${report.baseUrl} |
| Active Profile | ${report.profile} |
| Warmup Requests | ${report.warmupRequests} |
| Scenario Requests | ${report.scenarioRequests} |
| Concurrency | ${report.concurrency} |
| Appointment Requests | ${report.appointmentRequests} |
| Item ID | ${report.itemId} |

## Result Summary

| Scenario | Requests | Concurrency | Throughput(req/s) | Avg(ms) | P95(ms) | P99(ms) | Failures | Status |
|---|---:|---:|---:|---:|---:|---:|---:|---|
${rows}

## Notes

- This is a local HTTP pressure test against a running Spring Boot service.
- Enable mysql, redis, and rabbitmq profiles when testing the full persistence/cache/MQ path.
- Appointment creation is intentionally limited by business rate-limit rules, so the appointment scenario uses fewer requests.
- Do not present this as production capacity. Use it as reproducible local evidence for MyBatis-Plus persistence, cache, idempotency, rate-limit, and async-notification paths.
`;
}

async function main() {
  await mkdir(outputDir, { recursive: true });

  const startedAt = new Date().toISOString();
  const buyerToken = await login("buyer");
  await login("seller");
  const itemId = await getFirstOnSaleItemId();

  await runScenario("warmup_item_detail", warmupRequests, concurrency, () => request("GET", `/api/items/${itemId}`));

  const scenarios = [];
  scenarios.push(await runScenario("item_detail_cached", scenarioRequests, concurrency, () => request("GET", `/api/items/${itemId}`)));
  scenarios.push(await runScenario("hot_items", scenarioRequests, concurrency, () => request("GET", "/api/items/hot?limit=10")));
  scenarios.push(await runScenario("item_list_search", scenarioRequests, concurrency, () => request("GET", "/api/items?keyword=iPad")));
  scenarios.push(await runScenario("appointment_create_with_idempotency", appointmentRequests, Math.min(appointmentRequests, 6), (index) => {
    const expectedTime = new Date(Date.now() + 86400000 + index * 60000).toISOString().slice(0, 19);
    return request("POST", `/api/items/${itemId}/appointments`, {
      token: buyerToken,
      headers: { "X-Idempotency-Key": `pressure-${nowTag}-${index}` },
      body: {
        expectedTime,
        note: `pressure test ${index}`,
      },
    });
  }));
  scenarios.push(await runScenario("appointment_rate_limit_probe", 15, 5, (index) => {
    const expectedTime = new Date(Date.now() + 172800000 + index * 60000).toISOString().slice(0, 19);
    return request("POST", `/api/items/${itemId}/appointments`, {
      token: buyerToken,
      headers: { "X-Idempotency-Key": `rate-limit-${nowTag}-${index}` },
      body: {
        expectedTime,
        note: `rate limit probe ${index}`,
      },
    });
  }));

  const report = {
    startedAt,
    baseUrl,
    profile: process.env.SPRING_PROFILES_ACTIVE || "external process",
    warmupRequests,
    scenarioRequests,
    concurrency,
    appointmentRequests,
    itemId,
    scenarios,
  };

  const jsonPath = `${outputDir}/pressure-${nowTag}.json`;
  const mdPath = `${outputDir}/pressure-${nowTag}.md`;
  await writeFile(jsonPath, JSON.stringify(report, null, 2), "utf8");
  await writeFile(mdPath, markdownReport(report), "utf8");

  console.log(JSON.stringify({ jsonPath, mdPath, scenarios }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
