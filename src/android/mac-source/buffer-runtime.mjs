import { Buffer as BrowserBuffer } from 'buffer/index.js';

// The browser buffer package supplies BigInt methods but not Node's base64url encoding.
if (!BrowserBuffer.isEncoding('base64url')) {
  const originalToString = BrowserBuffer.prototype.toString;
  BrowserBuffer.prototype.toString = function toString(encoding, ...args) {
    if (typeof encoding === 'string' && encoding.toLowerCase() === 'base64url') {
      return originalToString.call(this, 'base64', ...args).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }
    return originalToString.call(this, encoding, ...args);
  };
}
export const Buffer = BrowserBuffer;

// Keep the unmodified desktop module usable on WebViews predating AbortSignal.timeout.
export function androidAppTimeout(milliseconds) {
  if (typeof globalThis.AbortSignal?.timeout === 'function') return globalThis.AbortSignal.timeout(milliseconds);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), milliseconds);
  controller.signal.addEventListener('abort', () => clearTimeout(timer), { once: true });
  return controller.signal;
}
