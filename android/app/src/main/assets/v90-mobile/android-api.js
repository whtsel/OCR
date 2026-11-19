/* Android transport for v90's existing fetch calls. Loaded before app.js. */
(() => {
  'use strict';
  const bridge = window.BlackBoxAndroid;
  if (!bridge) return;
  const pending = new Map();
  const fetchAsset = window.fetch.bind(window);
  let sequence = 0;
  const expired = () => Date.now() >= 1806537600000 || window.bbxExpiryV90?.isExpired();
  function result(status, json) {
    return new Response(json, { status, headers: {'Content-Type': 'application/json; charset=utf-8'} });
  }
  window.bbxAndroidReply = (id, status, json) => pending.get(id)?.complete(result(status, json));
  window.fetch = (input, options = {}) => {
    const url = new URL(input instanceof Request ? input.url : String(input), location.href);
    if (url.origin !== location.origin) return Promise.reject(new TypeError('The Android interface cannot open remote pages.'));
    if ((url.pathname !== '/health' && !url.pathname.startsWith('/api/')) ||
        url.pathname === '/api/cover' || url.pathname.startsWith('/api/img/')) return fetchAsset(input, options);
    if (expired()) return Promise.resolve(result(410, '{"error":"This v90 build has ended."}'));
    if (pending.size >= 80) return Promise.resolve(result(503, '{"error":"Android request queue is busy."}'));
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const signal = options.signal;
      const abort = () => { clear(); reject(new DOMException('Cancelled', 'AbortError')); };
      const timer = setTimeout(() => complete(result(504, '{"error":"Android request timed out."}')), 34000);
      function clear() { clearTimeout(timer); signal?.removeEventListener('abort', abort); pending.delete(id); }
      function complete(r) { clear(); resolve(r); }
      if (signal?.aborted) return abort();
      signal?.addEventListener('abort', abort, {once: true});
      pending.set(id, {complete});
      try { bridge.request(id, String(options.method || 'GET').toUpperCase(), url.pathname + url.search, options.body == null ? '' : String(options.body)); }
      catch (err) { clear(); reject(err); }
    });
  };
  window.bbxAndroidOpen = (kind, id = '') => { if (!expired()) bridge.open(kind, String(id)); };
})();
