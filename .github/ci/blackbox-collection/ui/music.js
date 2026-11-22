'use strict';

const byId = id => document.getElementById(id);
const bridge = window.OfflineMusic;
const pages = ['collection', 'find', 'downloads'];
const glyphs = {
  collection: '<path d="M3 8h18v12H3zM5 4h14M8 12h8"/>',
  find: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/>',
  downloads: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v4h16v-4"/>',
  plus: '<path d="M12 4v16m-8-8h16"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  play: '<path fill="currentColor" stroke="none" d="M8 4.5a1 1 0 0 1 1.52-.86l12 7.5a1 1 0 0 1 0 1.72l-12 7.5A1 1 0 0 1 8 19.5z"/>',
  pause: '<path d="M9 5v14m6-14v14" stroke-width="3"/>',
  next: '<path d="m7 5 10 7-10 7V5Zm12 0v14"/>',
  previous: '<path d="M17 5 7 12l10 7V5ZM5 5v14"/>',
  check: '<path d="m5 12 5 5L19 8"/>',
};
const icon = name => `<svg aria-hidden="true" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${glyphs[name]}</svg>`;
let data = {tracks: [], jobs: [], paused: false};
let tab = 'collection';
let lastLibrary = '';
let lastPlayer = '';
let loaded = false;
const selected = new Set();

function element(tag, className = '', text = '') {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text) node.textContent = text;
  return node;
}

function button(text, className, onClick, ariaLabel, symbol) {
  const node = element('button', className, text);
  node.type = 'button';
  if (ariaLabel) node.setAttribute('aria-label', ariaLabel);
  if (symbol) node.insertAdjacentHTML('afterbegin', icon(symbol));
  node.addEventListener('click', onClick);
  return node;
}

function artwork(track, className) {
  const image = element('img', className);
  image.src = track.key === 'kwaku' ? 'kwaku.svg' : 'espresso.svg';
  image.alt = '';
  return image;
}

function empty(title, note, action) {
  const panel = element('div', 'empty');
  panel.append(element('strong', '', title), element('p', '', note));
  if (action) panel.append(button('Find songs', 'link', () => show('find')));
  return panel;
}

const trackFor = key => data.tracks.find(track => track.key === key);
const saved = () => data.tracks.filter(track => track.saved);
const available = () => data.tracks.filter(track => !track.saved && !data.jobs.some(job => job.key === track.key));
const songCount = n => `${n} ${n === 1 ? 'song' : 'songs'}`;
const fold = value => value.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f’']/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

function row(track, subtitle, controls = [], color = false) {
  const group = element('article', 'track');
  const info = element('div', 'track-info');
  info.append(element('h3', '', track.title), element('p', color ? 'track-state' : '', subtitle));
  group.append(artwork(track, 'cover'), info);
  if (controls.length) {
    const actions = element('div', 'track-controls');
    actions.append(...controls);
    group.append(actions);
  }
  return group;
}

function problem(message) {
  byId('problem').hidden = !message;
  byId('problem').textContent = message || '';
}

function readLibrary(raw) {
  const payload = JSON.parse(raw);
  if (payload.error) { problem(payload.error); return false; }
  problem('');
  data = payload;
  loaded = true;
  lastLibrary = raw;
  for (const key of selected) if (!available().some(track => track.key === key)) selected.delete(key);
  render();
  return true;
}

function queue(action, value = '') {
  try { return readLibrary(bridge.queue(action, value)); }
  catch (_) { problem('Could not update the download list. Try again.'); return false; }
}

function enqueue(keys) {
  if (keys.length && queue('enqueue', JSON.stringify(keys))) show('downloads');
}

function playback(action, value = '') { if (bridge) bridge.playback(action, String(value)); }

function show(next) {
  if (!pages.includes(next)) return;
  if (document.activeElement === byId('filter') && next !== 'find') byId('filter').blur();
  tab = next;
  for (const page of pages) {
    byId(page).hidden = page !== next;
    const nav = byId(`tab-${page}`);
    if (page === next) nav.setAttribute('aria-current', 'page');
    else nav.removeAttribute('aria-current');
  }
  byId('scroll').scrollTop = 0;
  if (loaded) render();
}

function renderCollection() {
  const tracks = saved();
  byId('collection-sub').textContent = tracks.length ? `${songCount(tracks.length)} available offline` : 'Queue what you want. Play what’s saved.';
  byId('mix-count').textContent = tracks.length ? `${songCount(tracks.length)} saved` : 'No saved tracks yet';
  byId('play-mix').disabled = !tracks.length;
  byId('track-count').textContent = tracks.length ? String(tracks.length).padStart(2, '0') : '';
  byId('saved-songs').replaceChildren(...(tracks.length ? tracks.map(track => row(track, track.artist, [
    button('', 'round bare', () => playback('play', track.key), `Play ${track.title}`, 'play'),
  ])) : [empty('Nothing saved yet', 'Find a song, then queue it in Downloads.', true)]));
  const releases = new Map();
  for (const track of tracks) {
    const key = `${track.artist} / ${track.release}`;
    if (!releases.has(key)) releases.set(key, []);
    releases.get(key).push(track);
  }
  byId('releases').hidden = releases.size === 0;
  byId('release-list').replaceChildren(...Array.from(releases.values(), list => {
    const track = list[0];
    const card = element('article', 'release-card');
    card.append(artwork(track, 'release-art'), element('h3', '', track.release),
      element('p', '', `${track.artist} · ${list.length} saved`),
      button('Play saved', 'link', () => playback('playlist', JSON.stringify(list.map(item => item.key))), `Play saved from ${track.release}`));
    return card;
  }));
}

function selection() {
  const keys = selectionKeys();
  byId('selection-count').textContent = selected.size ? `${selected.size} selected` : 'Select songs to queue together';
  byId('queue-selection').disabled = keys.length === 0;
  byId('queue-selection').textContent = selected.size ? `Queue ${songCount(keys.length)}` : keys.length === 2 ? 'Queue both' : 'Queue song';
}

function matching() {
  const tokens = fold(byId('filter').value).split(' ').filter(Boolean);
  return data.tracks.filter(track => {
    const text = fold(`${track.title} ${track.artist} ${track.release}`);
    return tokens.every(token => text.includes(token));
  });
}

function selectionKeys() {
  const filtered = matching();
  return available().filter(track => selected.size ? selected.has(track.key) : filtered.includes(track)).map(track => track.key);
}

function renderFind() {
  const tracks = matching();
  byId('matches').textContent = `${tracks.length} ${tracks.length === 1 ? 'RESULT' : 'RESULTS'}`;
  byId('results').replaceChildren(...(tracks.length ? tracks.map(track => {
    const pending = data.jobs.find(job => job.key === track.key);
    const controls = [];
    if (!track.saved && !pending) {
      const tick = element('label', 'check');
      const input = element('input');
      input.type = 'checkbox';
      input.checked = selected.has(track.key);
      input.setAttribute('aria-label', `Select ${track.title}`);
      input.addEventListener('change', () => {
        if (input.checked) selected.add(track.key); else selected.delete(track.key);
        selection();
      });
      tick.append(input);
      controls.push(tick, button('', 'round bare', () => enqueue([track.key]), `Queue ${track.title}`, 'downloads'));
    } else {
      controls.push(track.saved ? element('span', 'track-tag', 'Saved') :
        button(pending.state === 'failed' ? 'Review' : 'View', 'link', () => show('downloads'), `View download for ${track.title}`));
    }
    const subtitle = track.title === track.release ? track.artist : `${track.artist} · ${track.release}`;
    return row(track, subtitle, controls);
  }) : [empty('No matching songs', 'Try Kwaku, Black Sherif, Espresso or Sabrina Carpenter.') ]));
  selection();
}

const status = {
  working: 'Getting recording…', offline: 'Waiting for connection', wifi: 'Waiting for Wi-Fi', paused: 'Paused', queued: 'Queued',
};
const failure = {
  signin: ['Sign-in required', 'The provider requested sign-in. Nothing was saved.'],
  unavailable: ['Exact recording unavailable', 'The provider did not offer this recording. No substitute was saved.'],
  refused: ['Provider refused', 'The provider blocked this request. No substitute was saved.'],
  limited: ['Rate limited', 'Wait a bit before retrying.'],
  incomplete: ['Recording not verified', 'A complete, playable copy was not found. Nothing was saved.'],
  network: ['Connection interrupted', 'Check your connection, then retry.'],
  storage: ['Could not save file', 'Check available storage, then retry.'],
  helper: ['Download didn’t finish', 'No file was saved. You can retry this recording.'],
};

function renderDownloads() {
  const waiting = data.jobs.filter(job => job.state !== 'failed');
  const issues = data.jobs.filter(job => job.state === 'failed');
  byId('pause-queue').hidden = !waiting.length && !data.paused;
  byId('pause-queue').textContent = data.paused ? 'Resume queue' : waiting.some(j => j.state === 'working') ? 'Pause after current' : 'Pause queue';
  const batches = new Map();
  for (const job of waiting) {
    if (!batches.has(job.batch)) batches.set(job.batch, []);
    batches.get(job.batch).push(job);
  }
  byId('queue-list').replaceChildren(...(waiting.length ? Array.from(batches, ([id, jobs]) => {
    const batch = element('div', 'batch');
    if (jobs.length > 1) {
      const header = element('div', 'batch-head');
      header.append(element('strong', '', 'Selected songs'), element('p', '', songCount(jobs.length)));
      batch.append(header);
    }
    for (const job of jobs) {
      const track = trackFor(job.key);
      const spin = element('span', 'working');
      spin.setAttribute('aria-hidden', 'true');
      batch.append(row(track, status[job.state], [job.state === 'working' ? spin :
        button('', 'round bare', () => queue('remove', job.key), `Remove ${track.title} from queue`, 'close')], true));
    }
    if (jobs.some(j => j.state === 'wifi')) {
      const footer = element('div', 'batch-footer');
      footer.append(button('Use mobile data for this group', 'link', () => queue('mobile', id)));
      batch.append(footer);
    }
    return batch;
  }) : [empty('Queue is clear', issues.length ? 'Review the recordings below.' : 'Add songs from Find when you’re ready.') ]));
  byId('issues').hidden = !issues.length;
  byId('issue-list').replaceChildren(...issues.map(job => {
    const track = trackFor(job.key);
    const [title, body] = failure[job.reason] || failure.helper;
    const remove = button('', 'round bare', () => queue('remove', job.key), `Dismiss ${track.title} download`, 'close');
    const entry = row(track, title, job.retryable ? [button('Retry', 'link', () => queue('retry', job.key), `Retry ${track.title}`), remove] : [remove], true);
    entry.classList.add('trailing');
    entry.append(element('p', 'track-detail', body));
    return entry;
  }));
  const tracks = saved();
  byId('downloaded-list').replaceChildren(...(tracks.length ? tracks.map(track => row(track, 'Saved on this device', [
    element('span', 'saved-check'),
  ], true)) : [empty('No finished downloads', 'Verified files appear here and in Collection.') ]));
  byId('downloaded-list').querySelectorAll('.saved-check').forEach(mark => { mark.innerHTML = icon('check'); });
}

function render() {
  byId('testing').hidden = !data.fixtures;
  byId('pending-count').hidden = !data.jobs.length;
  byId('pending-count').textContent = data.jobs.length;
  if (tab === 'collection') renderCollection();
  else if (tab === 'find') renderFind();
  else renderDownloads();
}

const time = millis => `${Math.floor(millis / 60000)}:${String(Math.floor(millis / 1000) % 60).padStart(2, '0')}`;
let seeking = null;
let waitForSeek = 0;
function renderPlayer(player) {
  const track = trackFor(player.key);
  byId('mini').hidden = !track;
  if (!track) return;
  byId('mini-title').textContent = track.title;
  byId('mini-status').textContent = player.problem ? 'Couldn’t play' : player.playing ? 'Playing offline' : player.ready ? 'Saved on this device' : 'Opening saved file…';
  byId('now-title').textContent = track.title;
  byId('now-artist').textContent = track.artist;
  for (const id of ['mini-art', 'full-art']) if (byId(id).getAttribute('src') !== `${track.key}.svg`) byId(id).src = `${track.key}.svg`;
  for (const id of ['mini-toggle', 'toggle']) {
    const control = byId(id);
    const label = player.playing ? 'Pause' : 'Play';
    if (control.getAttribute('aria-label') !== label || !control.firstChild) {
      control.setAttribute('aria-label', label);
      control.innerHTML = icon(player.playing ? 'pause' : 'play');
    }
    control.disabled = !player.ready && !player.problem;
  }
  byId('now-problem').hidden = !player.problem;
  byId('now-problem').textContent = player.problem;
  byId('previous').disabled = !player.previous;
  byId('next').disabled = !player.next;
  byId('seek').disabled = !player.ready;
  byId('seek').max = Math.max(1, player.duration - 1);
  if (seeking === null && Date.now() > waitForSeek) byId('seek').value = player.position;
  byId('clock').textContent = player.ready ? `${time(seeking ?? player.position)} / ${time(player.duration)}` : 'Opening saved file…';
}

function update() {
  if (!bridge) return;
  try {
    const library = bridge.library();
    if (library !== lastLibrary) readLibrary(library);
    const player = bridge.player();
    if (player !== lastPlayer || (waitForSeek && Date.now() > waitForSeek)) {
      lastPlayer = player;
      if (Date.now() > waitForSeek) waitForSeek = 0;
      renderPlayer(JSON.parse(player));
    }
  } catch (_) { problem('The music controls could not open. Restart the app to try again.'); }
}

document.querySelectorAll('[data-tab]').forEach(control => control.addEventListener('click', () => show(control.dataset.tab)));
for (const name of pages) byId(`tab-${name}`).insertAdjacentHTML('afterbegin', icon(name));
byId('tab-downloads').setAttribute('aria-label', 'Downloads');
document.querySelector('.shortcut').innerHTML = icon('find');
document.querySelector('.search-icon').innerHTML = icon('find');
document.querySelector('.add-songs').insertAdjacentHTML('afterbegin', icon('plus'));
byId('play-mix').innerHTML = icon('play');
byId('close-player').innerHTML = icon('close');
for (const id of ['next', 'previous']) {
  byId(id).innerHTML = icon(id);
  byId(id).addEventListener('click', () => playback(id));
}
byId('filter').addEventListener('input', renderFind);
byId('queue-selection').addEventListener('click', () => enqueue(selectionKeys()));
byId('pause-queue').addEventListener('click', () => queue(data.paused ? 'resume' : 'pause'));
byId('play-mix').addEventListener('click', () => playback('playlist', JSON.stringify(saved().map(track => track.key))));
for (const id of ['toggle', 'mini-toggle']) byId(id).addEventListener('click', () => playback('toggle'));
byId('open-player').addEventListener('click', () => byId('player').showModal());
byId('close-player').addEventListener('click', () => byId('player').close());
byId('seek').addEventListener('input', event => {
  seeking = Number(event.target.value);
  renderPlayer(JSON.parse(lastPlayer || '{}'));
});
byId('seek').addEventListener('change', event => {
  seeking = null;
  waitForSeek = Date.now() + 600;
  playback('seek', event.target.value);
});
byId('seek').addEventListener('pointercancel', () => { seeking = null; });
window.pilotBack = () => {
  if (byId('player').open) { byId('player').close(); return true; }
  if (tab !== 'collection') { show('collection'); return true; }
  return false;
};
if (bridge) {
  update();
  setInterval(update, 360);
} else {
  byId('collection-sub').textContent = 'Open in the BlackBox Music Android app.';
}
