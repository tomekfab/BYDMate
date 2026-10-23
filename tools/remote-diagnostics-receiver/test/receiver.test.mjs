import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, writeFile, readFile, readdir, rm, stat } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { createReceiver } from '../src/server.mjs';
import { provisionInstall } from '../src/provision-install.mjs';
import { ENDPOINT, HOST, validateBatch, validateImport } from '../src/protocol.mjs';

const hash = value => createHash('sha256').update(value).digest('hex');
const token = 'a'.repeat(43);
const tokenHash = hash(token);
const id = '12345678-1234-4234-8234-123456789abc';
// Exported JSON strings are the compatibility vectors for the app's Step 2 tests.
export const acceptedBatchJson = JSON.stringify({
  schemaVersion: 1, batchId: id, createdAtMs: 1,
  app: { version: '1.0-2', versionCode: 1, buildType: 'debug', installIdHash: 'a'.repeat(64) },
  environment: { apiLevel: 29, manufacturer: 'byd', model: 'head_unit', capabilities: { microphone: true, overlay: false, notificationListener: true } },
  logcat: { entries: [{ type: 'voice.lifecycle', atMs: 0, state: 'listening', durationMs: 300000, reason: 'none' }], droppedUnrecognisedLines: 0, truncatedLogEvents: 0 },
  events: [
    { type: 'voice.failure', atMs: 1, stage: 'failed', reason: 'unknown', exceptionClass: 'Other' },
    { type: 'connection.request', atMs: 2, backend: 'local_asr', operation: 'recognize', durationMs: 120000, outcome: 'success', httpStatus: 200 },
    { type: 'connection.failure', atMs: 3, backend: 'openclaw', phase: 'connect', reason: 'transport', exceptionClass: 'IOException', retryable: true }
  ], counters: { captureDropped: 0, queueDropped: 0, storageFailures: 0 }
});
export const acceptedImportJson = JSON.stringify({ schemaVersion: 1, endpoint: ENDPOINT, bearerToken: token });
const changed = fn => { const v = JSON.parse(acceptedBatchJson); fn(v); return JSON.stringify(v); };
export const rejectedBatchJson = [
  changed(v => { v.message = 'secret'; }), changed(v => { v.schemaVersion = 2; }),
  changed(v => { delete v.counters; }), changed(v => { v.app.version = 'https://example.com'; }),
  changed(v => { v.app.versionCode = 0; }), changed(v => { v.app.extra = true; }),
  changed(v => { v.environment.manufacturer = 'BYD raw'; }), changed(v => { v.environment.apiLevel = 28; }),
  changed(v => { v.environment.capabilities.microphone = 1; }), changed(v => { v.environment.capabilities.extra = true; }),
  changed(v => { v.logcat.entries[0].message = 'token'; }), changed(v => { v.logcat.entries[0].state = 'arbitrary'; }),
  changed(v => { v.logcat.entries[0].durationMs = 300001; }), changed(v => { v.logcat.extra = 0; }),
  changed(v => { v.events[0].exceptionClass = 'CustomException: secret'; }), changed(v => { v.events[0].reason = null; }),
  changed(v => { v.events[0].durationMs = -1; }), changed(v => { v.events[1].durationMs = 120001; }),
  changed(v => { v.events[1].httpStatus = 600; }), changed(v => { v.events[1].operation = 'delete'; }),
  changed(v => { v.events[2].retryable = 'true'; }), changed(v => { v.events[2].phase = 'unknown'; }),
  changed(v => { v.events.push({ type: 'journal.raw', atMs: 1, text: 'secret' }); }),
  changed(v => { v.counters.captureDropped = 9007199254740992; }), changed(v => { v.counters.extra = 0; }),
  changed(v => { v.createdAtMs = 1.5; }), changed(v => { v.batchId = id.toUpperCase(); }),
  changed(v => { v.environment.model = { latitude: 1 }; }), changed(v => { v.events = null; }),
  changed(v => { delete v.events[2].reason; }), changed(v => { v.app.installIdHash = 'A'.repeat(64); }),
  changed(v => { v.app.version = ''; }), changed(v => { v.app.version = '1'.repeat(33); }),
  changed(v => { v.app.buildType = 'staging'; }), changed(v => { v.environment.apiLevel = 101; }),
  changed(v => { v.environment.extra = 1; }), changed(v => { v.logcat.entries = [null]; }),
  changed(v => { v.events[0].atMs = -1; }), changed(v => { v.events[0].stage = 'raw'; }),
  changed(v => { v.events[1].backend = 'https://secret'; }), changed(v => { v.events[1].outcome = 'other'; }),
  changed(v => { v.events[1].httpStatus = 99; }), changed(v => { v.events[2].exceptionClass = 'Error'; }),
  changed(v => { v.logcat.droppedUnrecognisedLines = -1; }), changed(v => { v.logcat.truncatedLogEvents = 1.1; }),
  changed(v => { v.counters.queueDropped = '0'; }), changed(v => { delete v.environment.capabilities.overlay; }),
  changed(v => { v.events[0].type = '__proto__'; }), changed(v => { v.batchId += '\n'; }),
  changed(v => { v.app.installIdHash += '\n'; }), changed(v => { v.app.version += '\n'; })
];
export const rejectedImportJson = [ '{}', JSON.stringify({ ...JSON.parse(acceptedImportJson), extra: 1 }),
  JSON.stringify({ ...JSON.parse(acceptedImportJson), endpoint: 'https://evil.test:9443' }),
  JSON.stringify({ ...JSON.parse(acceptedImportJson), bearerToken: 'bad' }),
  JSON.stringify({ ...JSON.parse(acceptedImportJson), schemaVersion: 2 }),
  JSON.stringify({ ...JSON.parse(acceptedImportJson), bearerToken: token + '\n' }),
  JSON.stringify({ ...JSON.parse(acceptedImportJson), bearerToken: null }),
  JSON.stringify({ ...JSON.parse(acceptedImportJson), endpoint: ENDPOINT + '/' }) ];

function requestTo(server, body = acceptedBatchJson, headers = {}) {
    return new Promise((resolve, reject) => {
      const req = http.request({ host: '127.0.0.1', port: server.address().port, method: 'POST', path: '/v1/diagnostic-batches', headers: {
        host: HOST, authorization: `Bearer ${token}`, 'content-type': 'application/json',
        'x-bydmate-batch': id, 'x-bydmate-digest': `sha256=${hash(body)}`, ...headers
      } }, res => { let text = ''; res.on('data', b => { text += b; }); res.on('end', () => resolve({ status: res.statusCode, body: JSON.parse(text) })); });
      req.on('error', reject); req.end(body);
    });
  }

async function fixture(t, options = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bydmate-test-'));
  const dataDir = path.join(root, 'data');
  const tokensFile = path.join(root, 'tokens.json');
  await writeFile(tokensFile, JSON.stringify([tokenHash]));
  const logs = [];
  const server = await createReceiver({ dataDir, tokensFile, host: HOST, logger: row => logs.push(row), ...options });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(root, { recursive: true, force: true }); });
  const send = (body, headers) => requestTo(server, body, headers);
  return { root, dataDir, tokensFile, logs, send, server };
}
async function records(dir) { return (await readdir(dir, { recursive: true })).filter(n => n.endsWith('.batch.json')); }

if (process.argv.includes('--fixtures')) {
  process.stdout.write(JSON.stringify({ acceptedBatchJson, acceptedImportJson, rejectedBatchJson, rejectedImportJson }, null, 2) + '\n');
} else {
test('wire fixtures: accepted and rejected batches and imports', () => {
  assert.equal(validateBatch(JSON.parse(acceptedBatchJson)), true);
  assert.equal(validateImport(JSON.parse(acceptedImportJson)), true);
  for (const json of rejectedBatchJson) assert.throws(() => validateBatch(JSON.parse(json)), undefined, json);
  for (const json of rejectedImportJson) assert.throws(() => validateImport(JSON.parse(json)), undefined, json);
});
test('valid acknowledgement, exact body storage and identical retry', async t => {
  const f = await fixture(t); const first = await f.send();
  assert.deepEqual(first, { status: 200, body: { batchId: id, digest: `sha256=${hash(acceptedBatchJson)}` } });
  assert.deepEqual(await f.send(), first);
  const files = await records(f.dataDir); assert.equal(files.length, 1);
  const record = JSON.parse(await readFile(path.join(f.dataDir, files[0]), 'utf8'));
  assert.equal(Buffer.from(record.body, 'base64').toString(), acceptedBatchJson);
  assert.equal((await stat(path.join(f.dataDir, files[0]))).mode & 0o777, 0o600);
});
test('repeated ID with changed bytes conflicts, including concurrent retries', async t => {
  const f = await fixture(t);
  const initial = await Promise.all([f.send(), f.send()]);
  assert.deepEqual(initial.map(r => r.status), [200, 200]); assert.deepEqual(initial[0], initial[1]);
  assert.equal((await records(f.dataDir)).length, 1);
  assert.equal((await f.send(changed(v => { v.createdAtMs = 2; }))).status, 409);
  const replies = await Promise.all([f.send(), f.send()]); assert.deepEqual(replies.map(r => r.status), [200, 200]);
});
test('all invalid schema/typed fixtures rejected before persistence', async t => {
  const f = await fixture(t);
  for (const body of rejectedBatchJson) assert.equal((await f.send(body)).status, 400, body);
  assert.equal((await f.send('{')).status, 400); assert.deepEqual(await records(f.dataDir), []);
});
test('digest, UUID header mismatch and 64 KiB limit rejected before persistence', async t => {
  const f = await fixture(t);
  for (const digest of ['sha256=' + '0'.repeat(64), hash(acceptedBatchJson), 'sha256=' + 'A'.repeat(64)])
    assert.equal((await f.send(acceptedBatchJson, { 'x-bydmate-digest': digest })).status, 400);
  assert.equal((await f.send(acceptedBatchJson, { 'x-bydmate-batch': '22345678-1234-4234-8234-123456789abc' })).status, 400);
  assert.equal((await f.send(' '.repeat(65537))).status, 413);
  assert.deepEqual(await records(f.dataDir), []);
});
test('exact 64 KiB batch accepted, one extra byte rejected', async t => {
  const f = await fixture(t); const body = acceptedBatchJson.padEnd(65536, ' ');
  assert.equal((await f.send(body)).status, 200); assert.equal((await f.send(body + ' ')).status, 413);
});
test('missing, invalid and revoked bearer rejected', async t => {
  const f = await fixture(t);
  for (const authorization of ['', 'Bearer bad', `Bearer ${'b'.repeat(43)}`]) assert.equal((await f.send(acceptedBatchJson, { authorization })).status, 401);
  await writeFile(f.tokensFile, '[]'); assert.equal((await f.send()).status, 401);
  assert.deepEqual(await records(f.dataDir), []);
});
test('wrong configured host is rejected at construction', async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bydmate-host-')); t.after(() => rm(root, { recursive: true, force: true }));
  await assert.rejects(createReceiver({ dataDir: root, host: 'localhost:9443', tokensFile: path.join(root, 'tokens.json') }), /host/);
});
test('Host must be exact; forwarded headers provide no trust', async t => {
  const f = await fixture(t);
  assert.equal((await f.send(acceptedBatchJson, { host: 'localhost:9443', 'x-forwarded-host': HOST, forwarded: `host=${HOST}` })).status, 421);
  assert.equal((await f.send(acceptedBatchJson, { 'x-forwarded-host': 'evil.test', forwarded: 'host=evil.test' })).status, 200);
});
test('interrupted temporary write leaves no acknowledgement and startup removes temporary file', async t => {
  const f = await fixture(t, { beforeRename: () => { throw new Error('simulated interruption'); } });
  assert.equal((await f.send()).status, 503); assert.deepEqual(await records(f.dataDir), []);
  assert.equal((await readdir(f.dataDir)).some(n => n.endsWith('.tmp')), true);
  await new Promise(resolve => f.server.close(resolve));
  const recovery = await createReceiver({ dataDir: f.dataDir, tokensFile: f.tokensFile, host: HOST, logger: () => {} });
  await new Promise(resolve => recovery.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => recovery.close(resolve)));
  assert.equal((await readdir(f.dataDir, { recursive: true })).some(n => n.endsWith('.tmp')), false);
  assert.equal((await requestTo(recovery)).status, 200);
  assert.equal((await records(f.dataDir)).length, 1);
});
test('logs contain only allowlisted metadata and never token or body data', async t => {
  const f = await fixture(t); await f.send(); await f.send(acceptedBatchJson, { authorization: 'Bearer bad' });
  assert.equal(f.logs.length, 2);
  for (const row of f.logs) assert.deepEqual(Object.keys(row).sort(), ['batchId', 'bytes', 'outcome', 'reason', 'tokenHashPrefix']);
  const text = JSON.stringify(f.logs); assert.equal(text.includes(token), false); assert.equal(text.includes('installIdHash'), false);
});
test('retention evicts by receiver time and actual disk bytes, including index overhead', async t => {
  let now = 1000;
  const f = await fixture(t, { now: () => now, maxBytes: 3000 });
  assert.equal((await f.send()).status, 200);
  now += 1;
  const second = changed(v => { v.batchId = '22345678-1234-4234-8234-123456789abc'; });
  assert.equal((await f.send(second, { 'x-bydmate-batch': JSON.parse(second).batchId })).status, 200);
  assert.equal((await records(f.dataDir)).length, 1);
  now += 14 * 24 * 60 * 60 * 1000;
  assert.equal((await f.send(acceptedBatchJson, { authorization: '' })).status, 401);
  assert.deepEqual(await records(f.dataDir), []);
});

// Exact syntax includes rejection of trailing whitespace.
test('UUIDs, hashes, versions and tokens reject trailing newlines', async t => {
  for (const mutate of [v => { v.batchId += '\n'; }, v => { v.app.installIdHash += '\n'; }, v => { v.app.version += '\n'; }])
    assert.throws(() => validateBatch(JSON.parse(changed(mutate))));
  assert.throws(() => validateImport({ ...JSON.parse(acceptedImportJson), bearerToken: token + '\n' }));
});

test('provisioning generates one private import and persists only its hash', async t => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'bydmate-provision-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const registry = path.join(root, 'tokens.json');
  const file = await provisionInstall(registry);
  assert.notEqual(file, registry);
  t.after(() => rm(path.dirname(file), { recursive: true, force: true }));
  const document = JSON.parse(await readFile(file, 'utf8'));
  assert.equal(validateImport(document), true);
  assert.equal(Buffer.from(document.bearerToken, 'base64url').length, 32);
  assert.deepEqual(JSON.parse(await readFile(registry, 'utf8')), [hash(document.bearerToken)]);
  assert.equal((await stat(registry)).mode & 0o777, 0o600);
  assert.equal((await stat(file)).mode & 0o777, 0o600);
  assert.equal((await stat(path.dirname(file))).mode & 0o777, 0o700);
  const second = await provisionInstall(registry);
  t.after(() => rm(path.dirname(second), { recursive: true, force: true }));
  const secondDocument = JSON.parse(await readFile(second, 'utf8'));
  assert.notEqual(secondDocument.bearerToken, document.bearerToken);
  assert.deepEqual(JSON.parse(await readFile(registry, 'utf8')), [hash(document.bearerToken), hash(secondDocument.bearerToken)]);
});
test('listener rejects wildcard or absent bind addresses', async t => {
  const f = await fixture(t);
  assert.throws(() => f.server.listen(0, '0.0.0.0'), /loopback_required/);
  assert.throws(() => f.server.listen(0), /loopback_required/);
});
test('duplicate keys are scoped to token hash, not just batch ID', async t => {
  const f = await fixture(t); assert.equal((await f.send()).status, 200);
  const other = 'b'.repeat(43);
  await writeFile(f.tokensFile, JSON.stringify([tokenHash, hash(other)]));
  assert.equal((await f.send(changed(v => { v.createdAtMs = 2; }), { authorization: `Bearer ${other}` })).status, 200);
  assert.equal((await records(f.dataDir)).length, 2);
});
}
