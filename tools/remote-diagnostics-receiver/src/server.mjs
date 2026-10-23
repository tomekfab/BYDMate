import http from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { mkdir, chmod, open, readFile, readdir, rename, rm, stat } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { HOST, HASH, TOKEN, UUID, DIGEST, validateBatch } from './protocol.mjs';

export const MAX_BODY_BYTES = 64 * 1024;
export const MAX_DATA_BYTES = 100 * 1024 * 1024;
export const MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000;
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

// Body and lookup metadata share one durable rename, so neither can exist alone.
export async function atomicWrite(file, bytes, beforeRename = () => {}) {
  const temp = `${file}.${randomUUID()}.tmp`;
  const handle = await open(temp, 'wx', 0o600);
  try { await handle.writeFile(bytes); await handle.sync(); } finally { await handle.close(); }
  await beforeRename();
  await rename(temp, file);
  const directory = await open(path.dirname(file), 'r');
  try { await directory.sync(); } finally { await directory.close(); }
}

export async function readTokenHashes(file) {
  const hashes = JSON.parse(await readFile(file, 'utf8'));
  if (!Array.isArray(hashes) || !hashes.every(h => typeof h === 'string' && HASH.test(h))) throw new Error('invalid_token_registry');
  return hashes;
}

export async function createReceiver({ dataDir, tokensFile, host = HOST, logger = row => console.log(JSON.stringify(row)),
  now = Date.now, maxBytes = MAX_DATA_BYTES, beforeRename } = {}) {
  if (host !== HOST) throw new Error('invalid_host_policy');
  if (!dataDir || !tokensFile || !Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > MAX_DATA_BYTES) throw new Error('invalid_configuration');
  await readTokenHashes(tokensFile); // Fail closed before opening the listener.
  await mkdir(dataDir, { recursive: true, mode: 0o700 }); await chmod(dataDir, 0o700);
  let chain = Promise.resolve();
  const serial = job => { const result = chain.then(job); chain = result.catch(() => {}); return result; };
  const recordPattern = /^[0-9a-f]{64}\.[0-9a-f-]{36}\.batch\.json$/;
  let timer;
  let closed = false;
  async function inventory() {
    const rows = [];
    for (const name of await readdir(dataDir)) {
      if (name.endsWith('.tmp')) { await rm(path.join(dataDir, name), { force: true }); continue; }
      if (!recordPattern.test(name)) throw new Error('unexpected_storage_file');
      const file = path.join(dataDir, name);
      const record = JSON.parse(await readFile(file, 'utf8'));
      if (!Number.isSafeInteger(record.receivedAtMs)) throw new Error('invalid_storage_record');
      rows.push({ file, receivedAtMs: record.receivedAtMs, bytes: (await stat(file)).size });
    }
    return rows.sort((a, b) => a.receivedAtMs - b.receivedAtMs || a.file.localeCompare(b.file));
  }
  async function prune(reserve = 0) {
    clearTimeout(timer);
    const rows = await inventory(); let total = rows.reduce((sum, row) => sum + row.bytes, 0);
    while (rows.length && (rows[0].receivedAtMs + MAX_AGE_MS <= now() || total + reserve > maxBytes)) {
      const row = rows.shift(); await rm(row.file); total -= row.bytes;
    }
    if (!closed) {
      const delay = rows.length ? Math.max(1, Math.min(60000, rows[0].receivedAtMs + MAX_AGE_MS - now())) : 60000;
      timer = setTimeout(() => { serial(() => prune()).catch(() => { /* Requests fail closed if storage remains unhealthy. */ }); }, delay);
      timer.unref();
    }
  }
  await prune();
  const server = http.createServer({ requestTimeout: 15000, headersTimeout: 10000, maxHeaderSize: 8192 }, (req, res) => {
    let bytes = 0, tokenHashPrefix = '', batchId = '';
    function reply(status, reason, body = {}) {
      if (res.writableEnded || res.destroyed) return;
      try { logger({ tokenHashPrefix, batchId, bytes, outcome: status === 200 ? 'accepted' : 'rejected', reason }); } catch { /* Never expose logger exceptions. */ }
      res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(body));
    }
    const uniqueHeader = name => req.headersDistinct[name]?.length === 1 ? req.headersDistinct[name][0] : undefined;
    async function handle() {
      // Do not read Forwarded or X-Forwarded-*; Serve is the configured ingress.
      await serial(() => prune());
      if (uniqueHeader('host') !== host) return reply(421, 'host');
      if (req.method !== 'POST' || req.url !== '/v1/diagnostic-batches') return reply(404, 'route');
      const authorization = uniqueHeader('authorization');
      const bearer = typeof authorization === 'string' && authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
      if (!TOKEN.test(bearer)) return reply(401, 'auth');
      const tokenHash = sha256(bearer); tokenHashPrefix = tokenHash.slice(0, 12);
      if (!(await readTokenHashes(tokensFile)).includes(tokenHash)) return reply(401, 'auth');
      if (uniqueHeader('content-type') !== 'application/json' || req.headers['content-encoding']) return reply(400, 'content_type');
      const claimedId = uniqueHeader('x-bydmate-batch'); const claimedDigest = uniqueHeader('x-bydmate-digest');
      if (!UUID.test(claimedId ?? '') || !DIGEST.test(claimedDigest ?? '')) return reply(400, 'headers');
      batchId = claimedId;
      const chunks = [];
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > MAX_BODY_BYTES) { reply(413, 'size'); return; }
        chunks.push(chunk);
      }
      const body = Buffer.concat(chunks);
      const digest = `sha256=${sha256(body)}`;
      if (digest !== claimedDigest) return reply(400, 'digest');
      let batch;
      try { batch = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)); validateBatch(batch); }
      catch { return reply(400, 'schema'); }
      if (batch.batchId !== claimedId) return reply(400, 'batch_id');
      await serial(async () => {
        await prune();
        // Refresh authorization after waiting for a queued write (revocation is immediate).
        if (!(await readTokenHashes(tokensFile)).includes(tokenHash)) return reply(401, 'auth');
        const file = path.join(dataDir, `${tokenHash}.${batchId}.batch.json`);
        let existing;
        try { existing = JSON.parse(await readFile(file, 'utf8')); } catch (error) { if (error.code !== 'ENOENT') throw error; }
        if (existing) return existing.digest === digest ? reply(200, 'duplicate', { batchId, digest }) : reply(409, 'conflict');
        const record = JSON.stringify({ tokenHash, batchId, digest, receivedAtMs: now(), body: body.toString('base64') });
        if (Buffer.byteLength(record) > maxBytes) return reply(503, 'capacity');
        await prune(Buffer.byteLength(record));
        await atomicWrite(file, record, beforeRename);
        await prune();
        reply(200, 'stored', { batchId, digest });
      });
    }
    handle().catch(() => reply(503, 'storage'));
  });
  // All callers, including library consumers, must bind explicitly to loopback.
  const listen = server.listen.bind(server);
  server.listen = (...args) => {
    const bindHost = typeof args[0] === 'object' ? args[0]?.host : args[1];
    if (!['127.0.0.1', '::1'].includes(bindHost)) throw new Error('loopback_required');
    return listen(...args);
  };
  server.on('close', () => { closed = true; clearTimeout(timer); });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const [dataDir, tokensFile, portText = '8787'] = process.argv.slice(2);
    const port = Number(portText);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid_port');
    const server = await createReceiver({ dataDir, tokensFile });
    server.on('error', () => { process.stderr.write('receiver_failed\n'); process.exitCode = 1; server.close(); });
    server.listen(port, '127.0.0.1');
  } catch { process.stderr.write('receiver_failed\n'); process.exitCode = 1; }
}
