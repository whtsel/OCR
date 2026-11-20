'use strict';
const el = id => document.getElementById(id);
const audio = el('audio');
const form = el('search-form');
const query = el('query');
const results = el('results');
const status = text => { el('audio-status').textContent = text; };
const videoId = /^[A-Za-z0-9_-]{11}$/;
const streamId = /^[A-Za-z0-9_-]{24}$/;
const callLimit = 34_000; // Provider process + the byte-range probe are separately bounded.
let ready = false;
let busy = false;
let selected = null;
let movementTimer;
let cacheTimer;

function controls() {
  query.disabled = el('search').disabled = !ready || busy;
  form.setAttribute('aria-busy', String(busy));
  for (const button of results.querySelectorAll('button')) button.disabled = !ready || busy;
}

async function request(path, timeout, options) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(path, {...options, cache:'no-store', redirect:'error', signal:controller.signal});
    return [response.status, await response.json()];
  } finally { clearTimeout(timer); }
}

// The adapter sends a small, fixed set of messages. Keep all provider text and URLs out of this UI.
const text = (value, limit) => typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, limit) : '';
const reason = (answer, fallback) => text(answer?.message, 140) || fallback;
const elapsed = start => `${((performance.now() - start) / 1000).toFixed(1)} s`;
const mmss = seconds => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;

async function checkSetup() {
  el('retry').hidden = true;
  try {
    const [code, answer] = await request('api/status', 5000);
    if (code === 200 && answer.ready === true && answer.cookies === false) {
      ready = true;
      el('setup').textContent = 'Helper ready · no account session';
      controls();
      return;
    }
  } catch { /* The user can retry the local readiness check. */ }
  el('setup').textContent = 'The bundled helper could not start.';
  el('retry').hidden = false;
}

function renderResults(rows) {
  const seen = new Set();
  for (const row of rows.slice(0, 8)) {
    const id = row?.ytId;
    const title = text(row?.title, 200);
    const channel = text(row?.channel, 100);
    const duration = Math.round(Number(row?.duration));
    if (typeof id !== 'string' || !videoId.test(id) || seen.has(id) || !title || title === 'NA'
        || !Number.isInteger(duration) || duration < 1 || duration > 1800) continue;
    seen.add(id);
    const track = document.createElement('div');
    track.className = 'track';
    const copy = document.createElement('div');
    const name = document.createElement('strong');
    name.textContent = title;
    const meta = document.createElement('small');
    meta.textContent = `${channel && channel !== 'NA' ? channel : 'Channel not returned'} · ${mmss(duration)}`;
    copy.append(name, meta);
    const play = document.createElement('button');
    play.type = 'button';
    play.id = `play-result-${seen.size}`;
    play.textContent = 'Play';
    play.setAttribute('aria-label', `Play ${title}`);
    play.addEventListener('click', () => load({id, title, duration}));
    track.append(copy, play);
    results.append(track);
  }
  el('result-panel').hidden = !seen.size;
  return seen.size;
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  const value = text(query.value, 120);
  if (!ready || busy || !value) return;
  busy = true;
  query.blur();
  controls();
  results.replaceChildren();
  el('result-panel').hidden = true;
  const message = el('search-status');
  const start = performance.now();
  message.textContent = 'Searching YouTube…';
  try {
    const [code, answer] = await request(`api/search?q=${encodeURIComponent(value)}`, callLimit);
    if (code !== 200 || answer?.ok !== true || !Array.isArray(answer.results)) {
      message.textContent = `Search failed (${elapsed(start)}). ${reason(answer, 'No results received.')}`;
      return;
    }
    const count = renderResults(answer.results);
    message.textContent = count ? `Search returned ${count} YouTube ${count === 1 ? 'result' : 'results'} (${elapsed(start)}).`
      : `Search returned no usable YouTube results (${elapsed(start)}). No audio was requested.`;
  } catch (error) {
    message.textContent = error.name === 'AbortError' ? 'Search timed out. No audio was requested.'
      : 'Could not read the helper’s search response.';
  } finally { busy = false; controls(); }
});

function resetAudio() {
  selected = null;
  clearTimeout(movementTimer);
  clearTimeout(cacheTimer);
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  audio.hidden = true;
  for (const id of ['clock', 'buffer-status', 'startup-proof', 'seek-status']) el(id).textContent = '';
  el('seek').hidden = el('seek').disabled = true;
}

function metadata(url) {
  return new Promise((resolve, reject) => {
    const complete = error => {
      clearTimeout(timer);
      audio.removeEventListener('loadedmetadata', loaded);
      audio.removeEventListener('error', failed);
      if (error) reject(error); else resolve();
    };
    const loaded = () => complete();
    const failed = () => complete(new Error('The phone could not open the audio stream.'));
    const timer = setTimeout(() => complete(new Error('Buffering the start of the audio timed out.')), 13_000);
    audio.addEventListener('loadedmetadata', loaded);
    audio.addEventListener('error', failed);
    audio.src = url;
  });
}

async function readBuffer(stream) {
  try { return (await request(`api/buffer/${stream}`, 1700))[1]; }
  catch { return null; }
}

function pollBuffer(track) {
  if (track !== selected) return;
  cacheTimer = setTimeout(async () => {
    const buffer = await readBuffer(track.stream);
    if (track !== selected) return;
    if (buffer?.complete === true) {
      el('buffer-status').textContent = 'Whole audio buffered for replay in this session.';
      track.complete = true;
    } else {
      el('buffer-status').textContent = buffer?.size > 0
        ? `Partial buffer · ${Math.floor(100 * buffer.bytes / buffer.size)}%` : 'Buffering audio…';
      pollBuffer(track);
    }
  }, 550);
}

async function load(song) {
  if (!ready || busy) return;
  busy = true;
  controls();
  resetAudio();
  const start = performance.now();
  el('player-title').textContent = song.title;
  el('player-source').textContent = `YouTube · Video ID: ${song.id} · Listed length: ${mmss(song.duration)}`;
  const player = el('player');
  player.hidden = false;
  player.scrollIntoView({block:'nearest'});
  status('Finding audio for this exact YouTube result…');
  let stream;
  try {
    const webm = !!audio.canPlayType('audio/webm; codecs="opus"');
    const [code, answer] = await request('api/open', callLimit, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({id:song.id, seconds:song.duration, webm}),
    });
    if (code !== 200 || answer?.ok !== true) {
      status(`Audio lookup failed (${elapsed(start)}). ${reason(answer, 'Nothing played.')}`);
      return;
    }
    if (answer.id !== song.id || !streamId.test(answer.stream || '')) {
      status('The helper did not confirm the exact selected stream. Nothing played.');
      return;
    }
    stream = answer.stream;
    status(answer.cached ? 'Opening the local buffer…' : `Audio found (${elapsed(start)}). Buffering the start…`);
    await metadata(`api/audio/${stream}`);
    if (!Number.isFinite(audio.duration) || Math.abs(audio.duration - song.duration) > Math.min(6, Math.max(1, song.duration * .04))) {
      resetAudio();
      status('The audio length did not match the selected upload. Nothing played.');
      return;
    }
    const track = selected = {...song, stream, start, complete:answer.cached === true, first:'', advance:0};
    audio.hidden = false;
    el('clock').textContent = `0:00 / ${mmss(audio.duration)}`;
    status('Audio open. Waiting for the first playable buffer…');
    pollBuffer(track);
    movementTimer = setTimeout(() => {
      if (selected === track && !track.first) status('Player opened, but the audio clock has not advanced.');
    }, 13_000);
    audio.play().catch(() => {
      if (selected === track && !track.first && !audio.error) status('Audio is ready. Tap Play in the audio controls.');
    });
  } catch (error) {
    const buffer = stream && await readBuffer(stream);
    resetAudio();
    status(reason(buffer, error.name === 'AbortError' ? 'Audio lookup timed out. Nothing played.'
      : stream ? error.message : 'Could not open the selected audio. Nothing played.'));
  } finally { busy = false; controls(); }
}

audio.addEventListener('timeupdate', () => {
  const track = selected;
  if (!track || !Number.isFinite(audio.duration)) return;
  el('clock').textContent = `${mmss(audio.currentTime)} / ${mmss(audio.duration)}`;
  if (!track.first && audio.currentTime > 0 && !audio.paused && !audio.seeking && !audio.muted && audio.volume > 0) {
    track.first = elapsed(track.start);
    clearTimeout(movementTimer);
    el('seek').hidden = el('seek').disabled = false;
    // Sample cache state immediately, not at the next periodic UI refresh.
    readBuffer(track.stream).then(buffer => {
      if (track === selected) el('startup-proof').textContent = track.complete || buffer?.complete === true
        ? 'Started from a complete buffer.' : buffer?.complete === false ? 'Started before the full audio was buffered.' : '';
    });
  }
  if (track.seek && !audio.paused && !audio.seeking && audio.currentTime >= Math.floor(track.seek) + 1) {
    el('seek-status').textContent = `Seek verified · ${mmss(track.seek)} → ${mmss(audio.currentTime)}`;
    track.seek = 0;
  }
  if (track.first && !audio.paused && !audio.seeking && audio.currentTime > track.advance) {
    track.advance = audio.currentTime;
    status(`Audio playing · First audio in ${track.first} · Full length ${mmss(audio.duration)}`);
  }
});
audio.addEventListener('seeking', () => { if (selected) selected.advance = 0; });
audio.addEventListener('waiting', () => {
  if (selected?.first && !audio.paused) status('Buffering this recording…');
});
audio.addEventListener('error', async () => {
  const track = selected;
  if (!track) return;
  const buffer = await readBuffer(track.stream);
  if (track !== selected) return;
  resetAudio();
  status(reason(buffer, 'The phone could not continue this audio stream.'));
});
el('seek').addEventListener('click', () => {
  if (!selected || el('seek').disabled) return;
  el('seek-status').textContent = '';
  selected.seek = audio.duration * 2 / 3;
  audio.currentTime = selected.seek;
  selected.advance = 0;
  audio.play().catch(() => status('Tap Play to continue after seeking.'));
});
el('retry').addEventListener('click', checkSetup);
controls();
checkSetup();
