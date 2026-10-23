export const ENDPOINT = 'https://bobs-mac-mini-openclaw.tail3549c7.ts.net:9443';
export const HOST = new URL(ENDPOINT).host;
export const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const HASH = /^[0-9a-f]{64}$/;
export const TOKEN = /^[A-Za-z0-9_-]{43}$/;
export const DIGEST = /^sha256=[0-9a-f]{64}$/;
const states = ['preparing', 'listening', 'processing', 'running_command', 'waiting_assistant', 'speaking', 'done', 'refused', 'failed', 'stopped'];
const reasons = ['none', 'cancelled', 'timeout', 'not_ready', 'permission', 'unavailable', 'transport', 'http', 'protocol', 'storage', 'rejected', 'unknown'];
const backends = ['local_asr', 'local_tts', 'openrouter', 'openclaw'];
const exceptions = ['none', 'IOException', 'SocketTimeoutException', 'SSLException', 'IllegalStateException', 'CancellationException', 'Other'];
function requireValid(ok) { if (!ok) throw new Error('invalid_schema'); }
function object(value, required, optional = []) {
  requireValid(value !== null && typeof value === 'object' && !Array.isArray(value));
  requireValid(required.every(k => Object.hasOwn(value, k)) && Object.keys(value).every(k => required.includes(k) || optional.includes(k)));
}
function integer(value, min = 0, max = Number.MAX_SAFE_INTEGER) { requireValid(Number.isSafeInteger(value) && value >= min && value <= max); }
function enumeration(value, values) { requireValid(values.includes(value)); }
function matches(value, pattern) { requireValid(typeof value === 'string' && pattern.test(value)); }
function boolean(value) { requireValid(typeof value === 'boolean'); }
function event(value) {
  requireValid(value !== null && typeof value === 'object');
  const templates = {
    'voice.lifecycle': [['state', 'durationMs'], ['reason']],
    'voice.failure': [['stage', 'reason', 'exceptionClass'], ['durationMs']],
    'connection.request': [['backend', 'operation', 'durationMs', 'outcome'], ['httpStatus']],
    'connection.failure': [['backend', 'phase', 'reason', 'exceptionClass', 'retryable'], ['httpStatus']]
  };
  requireValid(Object.hasOwn(templates, value.type));
  const [required, optional] = templates[value.type];
  object(value, ['type', 'atMs', ...required], optional);
  integer(value.atMs);
  for (const [key, values] of Object.entries({ state: states, stage: states, reason: reasons, exceptionClass: exceptions,
    backend: backends, operation: ['load', 'recognize', 'synthesize', 'chat', 'prewarm'],
    outcome: ['success', 'failure', 'cancelled'], phase: ['connect', 'request', 'response', 'decode'] })) {
    if (Object.hasOwn(value, key)) enumeration(value[key], values);
  }
  if (Object.hasOwn(value, 'durationMs')) integer(value.durationMs, 0, value.type.startsWith('voice.') ? 300000 : 120000);
  if (Object.hasOwn(value, 'httpStatus')) integer(value.httpStatus, 100, 599);
  if (Object.hasOwn(value, 'retryable')) boolean(value.retryable);
}
function events(value) { requireValid(Array.isArray(value)); value.forEach(event); }
export function validateBatch(value) {
  object(value, ['schemaVersion', 'batchId', 'createdAtMs', 'app', 'environment', 'logcat', 'events', 'counters']);
  requireValid(value.schemaVersion === 1); matches(value.batchId, UUID); integer(value.createdAtMs);
  object(value.app, ['version', 'versionCode', 'buildType', 'installIdHash']);
  matches(value.app.version, /^[0-9.\-]{1,32}$/); integer(value.app.versionCode, 1);
  enumeration(value.app.buildType, ['debug', 'release']); matches(value.app.installIdHash, HASH);
  object(value.environment, ['apiLevel', 'manufacturer', 'model', 'capabilities']); integer(value.environment.apiLevel, 29, 100);
  enumeration(value.environment.manufacturer, ['byd', 'google', 'other']); enumeration(value.environment.model, ['head_unit', 'emulator', 'other']);
  object(value.environment.capabilities, ['microphone', 'overlay', 'notificationListener']);
  Object.values(value.environment.capabilities).forEach(boolean);
  object(value.logcat, ['entries', 'droppedUnrecognisedLines', 'truncatedLogEvents']); events(value.logcat.entries);
  integer(value.logcat.droppedUnrecognisedLines); integer(value.logcat.truncatedLogEvents); events(value.events);
  object(value.counters, ['captureDropped', 'queueDropped', 'storageFailures']); Object.values(value.counters).forEach(v => integer(v));
  return true;
}
export function validateImport(value) {
  object(value, ['schemaVersion', 'endpoint', 'bearerToken']);
  requireValid(value.schemaVersion === 1 && value.endpoint === ENDPOINT); matches(value.bearerToken, TOKEN);
  return true;
}
