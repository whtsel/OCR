// Controlled UI contract; only the Android emulator live gate can prove provider access.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, 'helper-phone.js'), 'utf8');
const markup = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
const search = 'api/search';
const stream = 's'.repeat(24);
const first = {ytId:'0aBcD1eF_hI', title:'A <new> result', channel:'Uploader A', duration:'186'};
const second = {ytId:'eF_gHJK_321', title:'Another result', channel:'Uploader B', duration:'225'};

class Element {
  constructor(tag) { this.tag = tag; this.children = []; this.handlers = new Map(); }
  addEventListener(type, handler) { (this.handlers.get(type) || this.handlers.set(type, new Set()).get(type)).add(handler); }
  removeEventListener(type, handler) { this.handlers.get(type)?.delete(handler); }
  async emit(type) { await Promise.all([...(this.handlers.get(type) || [])].map(handler => handler({preventDefault() {}}))); }
  append(...children) { this.children.push(...children); }
  replaceChildren() { this.children = []; }
  querySelectorAll(tag) { return this.children.flatMap(child => [...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)]); }
  setAttribute(key, value) { this[key] = value; }
  removeAttribute(key) { delete this[key]; }
  blur() {}
  scrollIntoView() {}
}

async function tester(respond, duration = Number(second.duration)) {
  // IDs must actually exist in the shipped HTML.
  const nodes = Object.fromEntries(ids.map(id => [id, new Element()]));
  const audio = nodes.audio;
  let url;
  Object.defineProperty(audio, 'src', {
    configurable:true,
    get:() => url,
    set(value) { url = value; queueMicrotask(() => audio.emit('loadedmetadata')); },
  });
  Object.assign(audio, {duration, currentTime:0, volume:1, paused:true, attempts:0,
    removeAttribute(key) { if (key === 'src') url = undefined; else delete this[key]; },
    load() { url = undefined; this.currentTime = 0; },
    pause() { this.paused = true; },
    canPlayType() { return 'probably'; },
    async play() { this.attempts++; this.paused = false; },
  });
  const requests = [];
  vm.runInNewContext(script, {performance, setTimeout(fn, ms) { const timer = setTimeout(fn, ms); timer.unref(); return timer; }, clearTimeout, AbortController,
    document:{getElementById:id => nodes[id], createElement:tag => new Element(tag)},
    async fetch(url, options) {
      requests.push({url, options});
      const [code, payload] = url === 'api/status'
        ? [200, {ready:true, cookies:false}]
        : url.startsWith('api/buffer/') ? [200, {bytes:1024, size:2048, complete:false}] : await respond(url, options);
      const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
      return {status:code, text:async () => raw, json:async () => JSON.parse(raw)};
    },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(nodes.query.disabled, false);
  assert.deepEqual(requests.map(row => row.url), ['api/status'], 'no hardcoded preview request on launch');
  return {nodes, audio, requests, async search(value) { nodes.query.value = value; await nodes['search-form'].emit('submit'); }};
}

test('one explicit result; opaque same-origin stream; advancing audio before completion', async () => {
  const app = await tester(async url => url === 'api/open'
    ? [200, {ok:true, id:second.ytId, stream, cached:false, url:'https://example.org/never-follow-me'}]
    : [200, {ok:true, results:[first, first, {...first, ytId:'../../fake'}, {...second, duration:'NA'}, second]}]);
  await app.search('  A&B – 試し  ');
  assert.deepEqual(app.requests.map(row => row.url), ['api/status', `${search}?q=A%26B%20%E2%80%93%20%E8%A9%A6%E3%81%97`]);
  assert.match(app.nodes['search-status'].textContent, /^Search returned 2 YouTube results/);
  const buttons = app.nodes.results.querySelectorAll('button');
  assert.equal(buttons[0]['aria-label'], `Play ${first.title}`);
  await buttons[1].emit('click');
  assert.deepEqual(JSON.parse(app.requests.at(-1).options.body), {id:second.ytId, seconds:225, webm:true});
  assert.equal(app.audio.src, `api/audio/${stream}`);
  assert.equal(app.audio.attempts, 1);
  app.audio.currentTime = 2;
  await app.audio.emit('timeupdate');
  await new Promise(resolve => setImmediate(resolve));
  assert.match(app.nodes['audio-status'].textContent, /^Audio playing · First audio .* Full length 3:45/);
  assert.equal(app.nodes['startup-proof'].textContent, 'Started before the full audio was buffered.');
  await app.nodes.seek.emit('click');
  assert.equal(app.audio.currentTime, 150);
  assert.equal(app.nodes['seek-status'].textContent, '');
  app.audio.currentTime = 151.1;
  await app.audio.emit('timeupdate');
  assert.equal(app.nodes['seek-status'].textContent, 'Seek verified · 2:30 → 2:31');
});

test('provider refusal reports lookup failure without selecting a different result', async () => {
  const app = await tester(async url => url === 'api/open'
    ? [502, {ok:false, message:'YouTube asked for sign-in or a bot check.'}]
    : [200, {ok:true, results:[first, second]}]);
  await app.search('One test query');
  await app.nodes.results.querySelectorAll('button')[0].emit('click');
  assert.match(app.nodes['audio-status'].textContent, /Audio lookup failed .* YouTube asked for sign-in or a bot check\./);
  assert.equal(app.audio.attempts, 0);
  assert.equal(app.audio.src, undefined);
  assert.equal(app.requests.filter(row => row.url === 'api/open').length, 1);
  assert.equal(app.nodes.search.disabled, false);
});

test('wrong ID, nonlocal stream token and truncated duration cannot start audio', async () => {
  for (const [id, token, reason] of [[first.ytId, stream, /did not confirm/], [second.ytId, '../wrong', /did not confirm/],
    [second.ytId, stream, /length did not match/]]) {
    const app = await tester(async url => url === 'api/open'
      ? [200, {ok:true, id, stream:token}]
      : [200, {ok:true, results:[second]}], 30);
    await app.search('A real query');
    await app.nodes.results.querySelectorAll('button')[0].emit('click');
    assert.match(app.nodes['audio-status'].textContent, reason);
    assert.equal(app.audio.src, undefined);
    assert.equal(app.audio.attempts, 0);
    assert.equal(app.nodes.seek.hidden, true);
  }
});
