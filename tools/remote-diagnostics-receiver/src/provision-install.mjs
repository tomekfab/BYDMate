import { randomBytes } from 'node:crypto';
import { mkdtemp, mkdir, chmod, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { ENDPOINT, validateImport } from './protocol.mjs';
import { atomicWrite, readTokenHashes, sha256 } from './server.mjs';

export async function provisionInstall(tokensFile) {
  if (!tokensFile) throw new Error('tokens_file_required');
  await mkdir(path.dirname(tokensFile), { recursive: true, mode: 0o700 });
  const lock = `${tokensFile}.lock`;
  await mkdir(lock, { mode: 0o700 }); // Refuse concurrent registry updates.
  let temporary;
  try {
    let hashes;
    try { hashes = await readTokenHashes(tokensFile); } catch (error) { if (error.code !== 'ENOENT') throw error; hashes = []; }
    const bearerToken = randomBytes(32).toString('base64url');
    const document = { schemaVersion: 1, endpoint: ENDPOINT, bearerToken }; validateImport(document);
    temporary = await mkdtemp(path.join(os.tmpdir(), 'bydmate-install-')); await chmod(temporary, 0o700);
    const importFile = path.join(temporary, 'import.json');
    await writeFile(importFile, JSON.stringify(document), { flag: 'wx', mode: 0o600 });
    await atomicWrite(tokensFile, JSON.stringify([...new Set([...hashes, sha256(bearerToken)])]));
    return importFile;
  } catch (error) {
    if (temporary) await rm(temporary, { recursive: true, force: true });
    throw error;
  } finally { await rm(lock, { recursive: true, force: true }); }
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try { console.log(await provisionInstall(process.argv[2])); }
  catch { process.stderr.write('provision_failed\n'); process.exitCode = 1; }
}
