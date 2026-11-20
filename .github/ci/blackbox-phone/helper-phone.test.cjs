// Controlled UI contract; only the Android emulator live gate can prove provider access.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, 'helper-phone.js'), 'utf8');
const markup = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
const search = '/api/music/youtube';
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
    async play() { this.attempts++; this.paused = false; },
  });
  const requests = [];
  vm.runInNewContext(script, {performance, setTimeout, clearTimeout, AbortController,
    document:{getElementById:id => nodes[id], createElement:tag => new Element(tag)},
    async fetch(url, options) {
      requests.push({url, options});
      const [code, payload] = url === '/api/ytdlp/status'
        ? [200, {installed:true, state:'ready', cookies:false}]
        : await respond(url, options);
      const raw = typeof payload === 'string' ? payload : JSON.stringify(payload);
      return {status:code, text:async () => raw, json:async () => JSON.parse(raw)};
    },
  });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(nodes.query.disabled, false);
  assert.deepEqual(requests.map(row => row.url), ['/api/ytdlp/status'], 'no hardcoded preview request on launch');
  return {nodes, audio, requests, async search(value) { nodes.query.value = value; await nodes['search-form'].emit('submit'); }};
}

test('arbitrary query; one explicit result; exact same-origin audio; forwards seek', async () => {
  const app = await tester(async url => url === `${search}/fetch`
    ? [200, {ok:true, id:second.ytId, bytes:300000, url:'https://example.org/never-follow-me'}]
    : [200, {ok:true, results:[first, first, {...first, ytId:'../../fake'}, {...second, duration:'NA'}, second]}]);
  await app.search('  A&B – 試し  ');
  assert.deepEqual(app.requests.map(row => row.url), ['/api/ytdlp/status', `${search}?q=A%26B%20%E2%80%93%20%E8%A9%A6%E3%81%97`]);
  assert.match(app.nodes['search-status'].textContent, /^Search returned 2 YouTube results/);
  const controls = app.nodes.results.querySelectorAll('button');
  assert.equal(controls.length, 2);
  assert.equal(controls[0]['aria-label'], `Play ${first.title}`);
  await controls[1].emit('click');
  assert.deepEqual(JSON.parse(app.requests.at(-1).options.body), {id:second.ytId, kind:'audio'});
  assert.equal(app.audio.src, `${search}/file/${second.ytId}?kind=audio`);
  assert.match(app.nodes['player-source'].textContent, new RegExp(`YouTube · Video ID: ${second.ytId}`));
  assert.equal(app.audio.attempts, 1);
  app.audio.currentTime = 2;
  await app.audio.emit('timeupdate');
  assert.match(app.nodes['audio-status'].textContent, /^Audio playing · Full length 3:45/);
  await app.nodes.seek.emit('click');
  assert.equal(app.audio.currentTime, 150);
});

// Reproduce the strings emitted by the archived Android wrapper, not an idealized API fixture.
const p11Rows = (...rows) => `{"ok":true,"results":[${rows.map(({ytId, title, channel, duration}) =>
  `{"ytId":${JSON.stringify(ytId)},"title":${JSON.stringify(title)},"channel":${JSON.stringify(channel)},"duration":${duration}","thumbnail":"https://i.ytimg.com/vi/${ytId}/hqdefault.jpg"}`
).join(',')}]}`;

test('reads the archived search envelope including quoted and Unicode metadata', async () => {
  const title = 'Échos ","duration":999"\\ライブ';
  const raw = p11Rows({...first, title}, {...second, duration:'0'}, second);
  assert.throws(() => JSON.parse(raw), SyntaxError);
  const app = await tester(async url => url === `${search}/fetch`
    ? [200, {ok:true, id:second.ytId, bytes:300000}]
    : [200, raw]);
  await app.search('Échos');
  assert.match(app.nodes['search-status'].textContent, /^Search returned 2 YouTube results/);
  const controls = app.nodes.results.querySelectorAll('button');
  assert.deepEqual(controls.map(button => button['aria-label']), [`Play ${title}`, `Play ${second.title}`]);
  assert.equal(app.requests.length, 2, 'reading search does not prepare audio');
  await controls[1].emit('click');
  assert.deepEqual(JSON.parse(app.requests.at(-1).options.body), {id:second.ytId, kind:'audio'});
  assert.equal(app.audio.src, `${search}/file/${second.ytId}?kind=audio`);
  app.audio.currentTime = 2;
  await app.audio.emit('timeupdate');
  assert.match(app.nodes['audio-status'].textContent, /^Audio playing · Full length 3:45/);
});

test('rejects incomplete or unexpected legacy search envelopes', async () => {
  const raw = p11Rows(first);
  for (const payload of [raw.slice(0, -1), raw.replace('/hqdefault.jpg', '/other.jpg'), raw.replace('}]}', '},]}')]) {
    const app = await tester(async () => [200, payload]);
    await app.search('A real query');
    assert.match(app.nodes['search-status'].textContent, /^Could not read the helper/);
    assert.equal(app.nodes.results.querySelectorAll('button').length, 0);
    assert.equal(app.requests.length, 2);
    assert.equal(app.nodes.search.disabled, false);
  }
});

test('provider refusal is reported after search without playing a different result', async () => {
  const app = await tester(async url => url === `${search}/fetch`
    ? [502, {ok:false, message:'ERROR: Sign in to confirm you are not a bot: https://example.org/private'}]
    : [200, {ok:true, results:[first, second]}]);
  await app.search('One test query');
  await app.nodes.results.querySelectorAll('button')[0].emit('click');
  assert.match(app.nodes['audio-status'].textContent, /Audio preparation failed .* YouTube asked for sign-in or a bot check\./);
  assert.equal(app.audio.attempts, 0);
  assert.equal(app.audio.src, undefined);
  assert.equal(app.nodes.search.disabled, false);
  await app.search('Another query');
  assert.match(app.nodes['search-status'].textContent, /^Search returned 2/);
});

test('wrong selected ID and truncated duration cannot start audio', async () => {
  for (const id of [first.ytId, second.ytId]) {
    const app = await tester(async url => url === `${search}/fetch`
      ? [200, {ok:true, id, bytes:100000}]
      : [200, {ok:true, results:[second]}], 30);
    await app.search('A real query');
    await app.nodes.results.querySelectorAll('button')[0].emit('click');
    assert.match(app.nodes['audio-status'].textContent, id === first.ytId ? /did not confirm the exact/ : /length did not match/);
    assert.equal(app.audio.src, undefined);
    assert.equal(app.audio.attempts, 0);
    assert.equal(app.nodes.seek.hidden, true);
  }
});
