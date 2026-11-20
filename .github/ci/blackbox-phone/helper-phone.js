'use strict';
const el = id => document.getElementById(id);
const api = '/api/music/youtube';
const audio = el('audio');
const form = el('search-form');
const query = el('query');
const results = el('results');
const status = text => { el('audio-status').textContent = text; };
const videoId = /^[A-Za-z0-9_-]{11}$/;
const audioLimit = 24 * 1024 * 1024;
let ready = false;
let busy = false;
let selected = null;
let movementTimer;

function controls() {
  query.disabled = el('search').disabled = !ready || busy;
  form.setAttribute('aria-busy', String(busy));
  for (const button of results.querySelectorAll('button')) button.disabled = !ready || busy;
}

async function request(path, timeout, options, read = response => response.json()) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(path, {...options, cache:'no-store', redirect:'error', signal:controller.signal});
    return [response.status, await read(response)];
  } finally { clearTimeout(timer); }
}

async function readSearch(response) {
  if (response.status !== 200) return response.json();
  const raw = await response.text();
  try { return JSON.parse(raw); } catch (error) {
    // p11 writes `"duration":186","thumbnail":...`, omitting the opening quote.
    // Read only that exact legacy shape, without changing the archived helper or rewriting titles.
    const start = '{"ok":true,"results":[';
    const end = ']}';
    if (raw.length > 64 * 1024 || !raw.startsWith(start) || !raw.endsWith(end)) throw error;
    const chars = String.raw`(?:[^"\\\u0000-\u001f]|\\["\\])*`;
    const row = new RegExp(String.raw`(\{"ytId":"(${chars})","title":"${chars}","channel":"${chars}","duration":)(0|[1-9][0-9]{0,18})(","thumbnail":"https://i\.ytimg\.com/vi/\2/hqdefault\.jpg"\})`, 'y');
    const rows = [];
    const limit = raw.length - end.length;
    let cursor = start.length;
    while (cursor < limit && rows.length < 8) {
      row.lastIndex = cursor;
      const match = row.exec(raw);
      if (!match) throw error;
      rows.push(`${match[1]}"${match[3]}${match[4]}`);
      cursor = row.lastIndex;
      if (cursor === limit) return JSON.parse(start + rows.join(',') + end);
      if (raw[cursor++] !== ',') throw error;
    }
    throw error;
  }
}

// Show only fixed reasons. Provider error text may contain media URLs or app-private paths.
function reason(answer) {
  const text = typeof answer?.message === 'string' ? answer.message : '';
  if (/429|too many requests/i.test(text)) return 'YouTube rate-limited the request.';
  if (/sign[ -]?in|not a bot|cookies/i.test(text)) return 'YouTube asked for sign-in or a bot check.';
  if (/403|forbidden|unavailable|private video/i.test(text)) return 'YouTube did not allow this upload.';
  if (/timed? out|no address|resolve|connection/i.test(text)) return 'The helper could not complete the connection to YouTube.';
  if (/ffmpeg|ffprobe|extract audio/i.test(text)) return 'The helper could not finish the audio file.';
  return 'The helper did not return a usable response.';
}

async function checkSetup() {
  el('retry').hidden = true;
  el('setup').textContent = 'Starting the bundled helper…';
  const until = performance.now() + 120000;
  while (performance.now() < until) {
    try {
      const [code, answer] = await request('/api/ytdlp/status', 5000);
      if (code === 200 && answer.cookies === true) {
        el('setup').textContent = 'Unexpected account session. This test will not run.';
        return;
      }
      if (code === 200 && answer.installed === true && answer.state === 'ready' && answer.cookies === false) {
        ready = true;
        el('setup').textContent = 'Helper ready · no account session';
        controls();
        return;
      }
      if (code === 200 && answer.state === 'failed') {
        el('setup').textContent = 'The bundled helper could not start.';
        el('retry').hidden = false;
        return;
      }
    } catch { /* Runtime startup and the local endpoint can briefly overlap. */ }
    await new Promise(resolve => setTimeout(resolve, 750));
  }
  el('setup').textContent = 'The helper did not become ready within two minutes.';
  el('retry').hidden = false;
}

const text = (value, limit) => typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, limit) : '';
const elapsed = start => `${((performance.now() - start) / 1000).toFixed(1)} s`;
const mmss = seconds => `${Math.floor(seconds / 60)}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;

function renderResults(rows) {
  const seen = new Set();
  for (const row of rows.slice(0, 8)) {
    const id = row?.ytId;
    const title = text(row?.title, 200);
    const channel = text(row?.channel, 100);
    const duration = Number(row?.duration);
    // Flat search can include upcoming uploads, playlists or results with no known length.
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
    const [code, answer] = await request(`${api}?q=${encodeURIComponent(value)}`, 40000, undefined, readSearch);
    if (code !== 200 || answer?.ok !== true || !Array.isArray(answer.results)) {
      message.textContent = `Search failed (${elapsed(start)}). ${reason(answer)}`;
      return;
    }
    const count = renderResults(answer.results);
    message.textContent = count ? `Search returned ${count} YouTube ${count === 1 ? 'result' : 'results'} (${elapsed(start)}).`
      : `Search returned no usable YouTube results (${elapsed(start)}). No audio was requested.`;
  } catch (error) {
    message.textContent = error.name === 'AbortError' ? 'Search timed out (40 s). No audio was requested.'
      : 'Could not read the helper’s search response.';
  } finally { busy = false; controls(); }
});

function resetAudio() {
  selected = null;
  clearTimeout(movementTimer);
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  audio.hidden = true;
  el('clock').textContent = '';
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
    const failed = () => complete(new Error('The phone could not open the prepared audio.'));
    const timer = setTimeout(() => complete(new Error('Reading the prepared audio timed out.')), 9000);
    audio.addEventListener('loadedmetadata', loaded);
    audio.addEventListener('error', failed);
    audio.src = url;
  });
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
  status('Preparing audio for this exact YouTube result…');
  try {
    const [code, answer] = await request(`${api}/fetch`, 90000, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({id:song.id, kind:'audio'}),
    });
    if (code !== 200 || answer.ok !== true) {
      status(`Audio preparation failed (${elapsed(start)}). ${reason(answer)}`);
      return;
    }
    if (answer.id !== song.id || !Number.isInteger(answer.bytes) || answer.bytes <= 0 || answer.bytes > audioLimit) {
      status('The helper did not confirm the exact selected audio file. Nothing played.');
      return;
    }
    status('Audio prepared. Checking the full length…');
    // Construct the private URL from the selected ID. Never follow a returned remote URL.
    await metadata(`${api}/file/${song.id}?kind=audio`);
    if (!Number.isFinite(audio.duration) || Math.abs(audio.duration - song.duration) > Math.min(6, Math.max(1, song.duration * .04))) {
      resetAudio();
      status('The audio length did not match the selected upload. Nothing played.');
      return;
    }
    selected = {...song, preparation:elapsed(start), advanced:false};
    audio.hidden = false;
    el('clock').textContent = `0:00 / ${mmss(audio.duration)}`;
    el('seek').hidden = el('seek').disabled = false;
    status(`Audio ready (${selected.preparation}). Starting playback…`);
    movementTimer = setTimeout(() => {
      if (selected?.id === song.id && !selected.advanced) status('Audio is ready, but the player has not advanced. Try the audio Play control.');
    }, 9000);
    audio.play().catch(() => {
      if (selected?.id !== song.id || selected.advanced) return;
      clearTimeout(movementTimer);
      status('Audio is ready. Tap Play in the audio controls to test playback.');
    });
  } catch (error) {
    resetAudio();
    status(error.name === 'AbortError' ? 'Audio preparation timed out (90 s). Nothing played.'
      : 'Could not open the selected audio. Nothing played.');
  } finally { busy = false; controls(); }
}

audio.addEventListener('timeupdate', () => {
  if (!selected || !Number.isFinite(audio.duration)) return;
  el('clock').textContent = `${mmss(audio.currentTime)} / ${mmss(audio.duration)}`;
  if (!selected.advanced && audio.currentTime > 0 && !audio.paused && !audio.seeking && !audio.muted && audio.volume > 0) {
    selected.advanced = true;
    clearTimeout(movementTimer);
    status(`Audio playing · Full length ${mmss(audio.duration)} · Prepared in ${selected.preparation}`);
  }
});
audio.addEventListener('error', () => {
  if (!selected) return;
  resetAudio();
  status('The phone could not play the prepared audio.');
});
el('seek').addEventListener('click', () => {
  if (!selected || el('seek').disabled) return;
  audio.currentTime = audio.duration * 2 / 3;
  audio.play().catch(() => status('Audio is ready. Tap Play to continue after seeking.'));
});
el('retry').addEventListener('click', checkSetup);
checkSetup();
