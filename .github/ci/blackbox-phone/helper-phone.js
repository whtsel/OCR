'use strict';
const el = id => document.getElementById(id);
const songs = [
  { key: 'espresso', title: 'Espresso', id: '51zjlMhdSTE', duration: 175 },
  { key: 'kwaku', title: 'Kwaku the Traveller', id: 'Hb6I7GoTvzI', duration: 185 },
];
const audio = el('audio');
let ready = false;
let loading = false;
let selected = null;
let audioUrl = '';
const updateButtons = () => songs.forEach(song => { el(song.key).disabled = !ready || loading; });
const report = (song, text) => { el(`${song.key}-status`).textContent = text; };

async function request(url, options, timeout) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    const response = await fetch(url, {...options, cache: 'no-store', signal: controller.signal});
    return [response.status, await response.json()];
  } finally {
    clearTimeout(timer);
  }
}

// Display a redacted reason, not raw provider pages, URLs, or app-private paths.
function reason(answer) {
  const text = String(answer?.message ?? '');
  if (/sign in|not a bot|cookies/i.test(text)) return 'The provider asked for sign-in or a bot check.';
  if (/429|too many requests/i.test(text)) return 'The provider rate-limited the request.';
  if (/403|forbidden/i.test(text)) return 'The provider refused the request.';
  if (/timed? out|no address|resolve|connection/i.test(text)) return 'The phone could not complete the provider connection.';
  if (/ffmpeg|ffprobe|extract audio/i.test(text)) return 'Audio preparation failed.';
  return 'The helper did not return this recording.';
}

async function checkSetup() {
  el('retry').hidden = true;
  el('setup').textContent = 'Starting the bundled helper…';
  const until = Date.now() + 120000;
  do {
    try {
      const [status, answer] = await request('/api/ytdlp/status', {}, 5000);
      if (status === 200 && answer.cookies === true) {
        el('setup').textContent = 'Unexpected account session. This no-sign-in test will not run.';
        return;
      }
      if (status === 200 && answer.installed === true && answer.state === 'ready' && answer.cookies === false) {
        ready = true;
        el('setup').textContent = 'Helper ready · no account session';
        updateButtons();
        return;
      }
      if (status === 200 && answer.state === 'failed') {
        el('setup').textContent = 'Helper setup failed on this phone.';
        el('retry').hidden = false;
        return;
      }
    } catch (_) { /* A cold start can briefly leave the local endpoint unavailable. */ }
    await new Promise(resolve => setTimeout(resolve, 1000));
  } while (Date.now() < until);
  el('setup').textContent = 'Helper did not become ready within two minutes.';
  el('retry').hidden = false;
}

async function load(song) {
  if (!ready || loading) return;
  loading = true;
  updateButtons();
  selected = null;
  audioUrl = '';
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  el('player').hidden = true;
  el('seek').disabled = true;
  report(song, 'Requesting the exact recording…');
  try {
    const [status, answer] = await request('/api/music/youtube/fetch', {
      method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({id: song.id, kind: 'audio'}),
    }, 150000);
    if (status !== 200 || answer.ok !== true) {
      report(song, `Could not load (HTTP ${status}). ${reason(answer)}`);
      return;
    }
    if (answer.id !== song.id) {
      report(song, 'The helper returned a different ID. No audio was opened.');
      return;
    }
    selected = song;
    // Never follow a returned remote URL. Only play p11's app-private file for this ID.
    audioUrl = `/api/music/youtube/file/${song.id}?kind=audio`;
    audio.src = audioUrl;
    el('player-title').textContent = song.title;
    el('clock').textContent = 'Reading duration…';
    el('player').hidden = false;
    report(song, 'Audio received. Press Play when ready.');
  } catch (error) {
    report(song, error.name === 'AbortError' ? 'Request timed out. No playback yet.' : 'Could not read the helper response.');
  } finally {
    loading = false;
    updateButtons();
  }
}

const mmss = time => `${Math.floor(time / 60)}:${String(Math.floor(time % 60)).padStart(2, '0')}`;
const activeAudio = () => selected && audio.getAttribute('src') === audioUrl &&
  audio.currentSrc === new URL(audioUrl, location.href).href;
function updateTime() {
  if (!activeAudio() || !Number.isFinite(audio.duration)) return;
  el('clock').textContent = `${mmss(audio.currentTime)} / ${mmss(audio.duration)}`;
}
audio.addEventListener('loadedmetadata', () => {
  if (!activeAudio()) return;
  if (!Number.isFinite(audio.duration) || Math.abs(audio.duration - selected.duration) > 6) {
    report(selected, 'Unexpected audio duration. This does not pass the full-track test.');
    selected = null;
    audio.removeAttribute('src');
    audio.load();
    el('player').hidden = true;
    return;
  }
  el('seek').disabled = false;
  updateTime();
});
audio.addEventListener('timeupdate', updateTime);
audio.addEventListener('error', () => {
  if (!selected || audio.getAttribute('src') !== audioUrl) return;
  el('seek').disabled = true;
  report(selected, 'The phone could not play the prepared audio.');
});
el('seek').addEventListener('click', () => {
  if (!activeAudio() || el('seek').disabled) return;
  audio.currentTime = Math.floor(selected.duration * 2 / 3);
  audio.play().catch(() => {});
});
for (const song of songs) el(song.key).addEventListener('click', () => load(song));
el('retry').addEventListener('click', checkSetup);
checkSetup();
