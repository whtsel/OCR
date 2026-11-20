/* Android playback for the unchanged v90 genre, artist, album and track views. */
(() => {
  'use strict';
  if (!window.bbxAndroidOpen) return;
  const read = window.bbxAndroidRequest;
  const byId = id => document.getElementById(id);
  const button = (text, fn, classes = 'secondary') => {
    const b = el('button', classes, text); b.type = 'button'; b.onclick = fn; return b;
  };
  const norm = value => String(value || '').normalize('NFKD').toLowerCase()
    .replace(/\p{M}/gu, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const title = item => item.track || item.title || '';
  const artist = item => item.artist || item.channel || '';
  const identity = item => item.catalogTrackId ? `album:${item.catalogTrackId}` : item.id && item.album ? `pick:${item.id}`
    : JSON.stringify([artist(item), title(item), item.album || '', item.duration || 0, item.explicit === true]);
  const display = item => [title(item), artist(item)].filter(Boolean).join(' — ');
  const videoId = id => /^[A-Za-z0-9_-]{11}$/.test(id || '');

  // Original recordings published by the artists/labels, not text-search guesses or covers.
  // Match the recording, duration and release aliases already present in the supplied v90 data.
  // SoundCloud references verified against the artists' accounts:
  // https://soundcloud.com/sabrinacarpenter/sabrina-carpenter-espresso
  // https://soundcloud.com/blacksherif/kweku-the-traveler
  const officialSources = [
    ['Sabrina Carpenter', 'Espresso', 175, '51zjlMhdSTE', ['Espresso', "Short n' Sweet"], 'sc:1799087761'],
    ['Billie Eilish', 'bad guy', 194, 'ZD6rXLXZOEI', ['WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?']],
    ['The Weeknd', 'Blinding Lights', 200, 'fHI8X4OXluQ', ['After Hours', 'After Hours (Deluxe)', 'The Highlights']],
    ['Burna Boy', 'On the Low', 185, 'UFsskuvj8GQ', ['On the Low', 'African Giant']],
    ['Black Sherif', 'Kwaku the Traveller', 185, 'Hb6I7GoTvzI', ['Kwaku the Traveller', 'The Villain I Never Was'], 'sc:1229321974'],
  ].map(([a, t, seconds, youtube, albums, soundCloud]) => ({artist:norm(a), track:norm(t), seconds, id:soundCloud || youtube, youtube, soundCloud, albums:albums.map(norm)}));
  function official(item) {
    const a = norm(artist(item)), t = norm(title(item)), album = norm(item.album);
    return officialSources.find(s => s.artist === a && s.track === t && s.albums.includes(album)
      && Math.abs(s.seconds - Number(item.duration)) <= 3);
  }
  function youtubeLink(raw) {
    try {
      const u = new URL(raw);
      if (u.protocol !== 'https:' || u.port || u.username || u.password) return '';
      let id;
      if (u.hostname === 'youtu.be') id = u.pathname.slice(1);
      else if (['youtube.com','www.youtube.com','m.youtube.com','music.youtube.com'].includes(u.hostname)) {
        id = u.pathname === '/watch' ? u.searchParams.get('v') : u.pathname.match(/^\/embed\/([A-Za-z0-9_-]{11})$/)?.[1];
      }
      return videoId(id) ? id : '';
    } catch { return ''; }
  }
  const storageKey = 'bbx.android.music.links.v1';
  let links = {};
  try { links = JSON.parse(localStorage.getItem(storageKey) || '{}') || {}; } catch {}
  function savedSource(item) {
    const entry = links[identity(item)];
    if (entry?.type === 'play' && /^[a-f0-9]{64}$/.test(entry.id || '')) return entry;
    if (entry?.type === 'music-video' && videoId(entry.id)) return entry;
    return null;
  }
  function remember(item, type, id) {
    links[identity(item)] = {type, id};
    const keys = Object.keys(links);
    for (const key of keys.slice(0, Math.max(0, keys.length - 300))) delete links[key];
    try { localStorage.setItem(storageKey, JSON.stringify(links)); } catch {}
  }
  let openDialog, fileRequest, sequence = 0;
  window.bbxAndroidMusicFile = (id, mediaId, error) => {
    if (fileRequest?.id !== id) return;
    const finish = fileRequest.finish;
    fileRequest = null;
    finish(mediaId, error);
  };
  function showTrackSource(item) {
    openDialog?.close();
    const dialog = el('dialog', 'android-music-source');
    dialog.id = 'androidMusicSource';
    const source = official(item), linked = savedSource(item);
    const heading = el('h2', '', title(item));
    heading.id = 'androidTrackTitle';
    dialog.setAttribute('aria-labelledby', heading.id);
    const status = el('p', 'sub android-action-status'); status.role = 'status';
    const info = el('p', 'sub', source ? 'This track has an official online player. You can also attach your own audio file link.'
      : 'No verified online source is available for this recording. Link this exact recording to play it; no different track will be used.');
    const input = document.createElement('input');
    input.type = 'url'; input.maxLength = 4096; input.placeholder = 'https://…/your-audio.mp3';
    input.setAttribute('aria-label', 'Source link for this recording');
    const controls = el('div', 'android-tools-row');
    async function useLink(event, download) {
      const control = event.currentTarget, url = input.value.trim();
      if (fileRequest) return;
      if (!url) { status.textContent = 'Enter the HTTPS link to this exact recording.'; input.focus(); return; }
      const id = youtubeLink(url);
      if (id) {
        if (download) { status.textContent = 'YouTube playback is online only. Use a downloadable audio file you have permission to save.'; return; }
        remember(item, 'music-video', id); dialog.close(); bbxAndroidOpen('music-video', id); return;
      }
      control.disabled = true;
      status.classList.remove('err'); status.textContent = download ? 'Queuing this file…' : 'Opening in BlackBox…';
      try {
        const result = await read('/api/mobile/direct', {url, kind:'MUSIC', title:display(item), download});
        if (!/^[a-f0-9]{64}$/.test(result.id || '')) throw new Error('The selected audio file was not opened.');
        if (!dialog.open) return;
        remember(item, 'play', result.id);
        if (download) status.replaceChildren(document.createTextNode(result.offline ? 'Already saved offline. ' : 'Queued in Library. '),
          button('View Library', () => { dialog.close(); showView('library'); }, 'ghost'));
        else { dialog.close(); bbxAndroidOpen('play', result.id); }
      } catch (error) { status.classList.add('err'); status.textContent = error.message; }
      finally { control.disabled = false; }
    }
    function pickFile(event) {
      if (fileRequest) return;
      const control = event.currentTarget;
      control.disabled = true;
      const id = ++sequence;
      fileRequest = {id, finish: (mediaId, error) => {
        control.disabled = false;
        if (!dialog.open) return;
        if (error) { status.textContent = error; status.classList.add('err'); return; }
        if (mediaId) { remember(item, 'play', mediaId); dialog.close(); bbxAndroidOpen('play', mediaId); }
      }};
      try { window.BlackBoxAndroid.pickMusic(id, display(item).slice(0,140)); }
      catch { fileRequest.finish('', 'Android’s file picker is unavailable.'); fileRequest = null; }
    }
    if (linked) {
      dialog.append(heading, el('p', 'sub', [artist(item), item.album].filter(Boolean).join(' · ')));
      const saved = el('div', 'android-tools-row');
      saved.append(button('Play saved source', () => { dialog.close(); bbxAndroidOpen(linked.type, linked.id); }));
      if (linked.type === 'play') saved.append(button('Save linked file offline', async event => {
        const control = event.currentTarget;
        control.disabled = true;
        try {
          const result = await read(`/api/mobile/transfer/${linked.id}/download`, {});
          status.classList.remove('err');
          status.textContent = result.offline ? 'Already saved offline in Library.' : 'Queued in Library.';
        } catch (error) { status.classList.add('err'); status.textContent = error.message; }
        finally { control.disabled = false; }
      }));
      saved.append(button('Unlink source', () => {
        delete links[identity(item)];
        try { localStorage.setItem(storageKey, JSON.stringify(links)); } catch {}
        showTrackSource(item);
      }, 'ghost'));
      dialog.append(saved);
    } else dialog.append(heading, el('p', 'sub', [artist(item), item.album].filter(Boolean).join(' · ')), info);
    controls.append(button('Choose audio on this phone', pickFile), button('Play linked source', event => useLink(event, false), 'primary'),
      button('Download audio file', event => useLink(event, true)), button('Close', () => dialog.close(), 'ghost'));
    if (source) {
      const providers = el('div', 'android-tools-row');
      if (source.soundCloud) providers.append(button('Play on SoundCloud here', () => {
        dialog.close(); bbxAndroidOpen('music-video', source.soundCloud);
      }));
      providers.append(button('Play on YouTube here', () => {
        dialog.close(); bbxAndroidOpen('music-video', source.youtube);
      }));
      dialog.append(providers);
    }
    dialog.append(el('p', 'sub', 'Use an authorized direct audio file, or an official YouTube link for online-only playback.'), input, controls, status);
    dialog.addEventListener('close', () => { if (openDialog === dialog) openDialog = null; dialog.remove(); }, {once:true});
    openDialog = dialog; document.body.append(dialog); dialog.showModal();
  }

  // Search the current v90 data. The existing metadata fallback does not advertise audio.
  getCachedMusicSearchResults = async query => {
    const text = String(query || '').trim().slice(0,130);
    const originals = window.bbxMusicSearchV76?.search(text, {limit:30}).songs?.map(row => row.track) || [];
    if (originals.length || text.length < 2) return originals;
    const {results} = await read(`/api/mobile/music?q=${encodeURIComponent(text)}`);
    return results;
  };
  // Keep the actual v90 queue selection but don't let desktop recovery choose a different recording.
  const desktopQueueIdentity = queueIdentity;
  queueIdentity = item => item?.catalogTrackId || (item?.album && title(item) ? identity(item) : desktopQueueIdentity(item));
  ensureQueueLookahead = () => 0;
  // The helper is optional and disabled until the listener opts in. Queueing/browsing alone never contacts the provider.
  const HELPER = '/api/mobile/music-helper';
  let helper = {available:false, enabled:false, tracks:{}};
  let selection = 0, planSequence = 0, playingPrepared = null, currentBlob = '', windowKey = '';
  let helperTimer, helperPanel, helperNote, helperToggle, helperClear, preparedRows;
  const matchHelper = item => item && !savedSource(item) ? (official(item)?.youtube || '') : '';
  const presentState = result => {
    helper = result;
    updateHelperPanel();
    renderMusicQueue();
    return result;
  };
  const refreshHelper = async () => presentState(await read(HELPER));
  function plan(selected, upcoming = [], retry = false) {
    return read(`${HELPER}/plan`, {sequence:++planSequence, selected, upcoming, retry});
  }
  function releasePreparedAudio() {
    playingPrepared = null;
    document.documentElement.classList.remove('bbx-prepared-player');
    windowKey = '';
    clearTimeout(helperTimer);
    if (!currentBlob) return;
    const audio = byId('bgAudio');
    if (audio?.src === currentBlob) {
      musicBgLoadSeq++;
      musicBgPendingFinish?.({ok:false, stale:true});
      audio.pause(); audio.removeAttribute('src'); audio.load();
      byId('bgAudioBar').hidden = true;
    }
    URL.revokeObjectURL(currentBlob);
    currentBlob = '';
  }
  function stillSelected(number, item) { return number === selection && musicQueue.list[musicQueue.idx] === item; }
  function followPreparation(number) {
    clearTimeout(helperTimer);
    helperTimer = setTimeout(async () => {
      if (selection !== number || !playingPrepared) return;
      try {
        const result = await read(HELPER);
        if (selection !== number) return;
        presentState(result);
        if (Object.values(helper.tracks).some(t => t.state === 'preparing' || result.unmetered && t.state === 'queued')) followPreparation(number);
      } catch { /* A status failure must not interrupt audio that is already playing. */ }
    }, 1100);
  }
  function prepareUpcoming() {
    const live = playingPrepared;
    if (!helper.enabled || !live || live.number !== selection || musicQueue.list[musicQueue.idx] !== live.item) return;
    // Do not fill holes further down the queue, resolve guesses, or change the original queue order.
    const next = musicQueue.list.slice(musicQueue.idx + 1, musicQueue.idx + 3).map(matchHelper);
    const key = JSON.stringify([live.id, ...next]);
    if (key === windowKey) return;
    windowKey = key;
    plan(live.id, next).then(result => {
      if (selection !== live.number || windowKey !== key) return;
      presentState(result);
      if (next.some(Boolean)) followPreparation(live.number);
    }).catch(() => { if (windowKey === key) windowKey = ''; });
  }
  async function playPrepared(item, id, number, options) {
    const started = Date.now();
    try {
      let result = await plan(id, [], true);
      while (stillSelected(number, item)) {
        presentState(result);
        const track = result.tracks?.[id];
        if (!result.enabled || !track) return;
        if (track.state === 'ready') break;
        if (track.state === 'failed') throw new Error(track.message);
        const seconds = Math.floor((Date.now() - started) / 1000);
        if (seconds > 190) throw new Error('This recording is taking too long to prepare.');
        setMusicPlaybackStatus(`Preparing ${title(item)} · ${seconds}s`, '#e9c979');
        await new Promise(resolve => setTimeout(resolve, 760));
        if (!stillSelected(number, item)) return;
        result = await read(HELPER);
      }
      if (!stillSelected(number, item)) return;
      // Read the complete app-private copy once. A blob gives the existing player local seeking
      // without exposing app files, a loopback server, or media URLs from the provider.
      const file = await fetch(`/prepared-audio/${id}.m4a`, {cache:'no-store'});
      if (!file.ok || !file.headers.get('Content-Type')?.startsWith('audio/mp4')) throw new Error('The prepared file is no longer on this phone.');
      const blob = await file.blob();
      if (!stillSelected(number, item)) return;
      if (!blob.size || blob.size > 24 * 1024 * 1024) throw new Error('The prepared file has an invalid size.');
      const player = ensureBgAudio();
      if (!player) throw new Error('The Music player is unavailable.');
      currentBlob = URL.createObjectURL(blob);
      document.documentElement.classList.add('bbx-prepared-player');
      const played = () => {
        if (!stillSelected(number, item) || player.audio.src !== currentBlob || !currentBlob) return;
        playingPrepared = {item, id, number};
        setMusicPlaybackStatus(`${display(item)} · playing from this phone`, '#65ddbc');
        prepareUpcoming();
      };
      player.audio.addEventListener('playing', played, {once:true});
      const outcome = await playBgAudio(currentBlob, {title:title(item), artist:artist(item), artwork:item.artwork || item.thumbnail});
      if (!stillSelected(number, item)) { player.audio.removeEventListener('playing', played); return; }
      if (!outcome.ok) {
        player.audio.removeEventListener('playing', played);
        throw new Error('The prepared recording could not start.');
      }
      if (outcome.needsTap) setMusicPlaybackStatus(`${title(item)} is prepared · tap Play in the player`, '#e9c979');
    } catch (error) {
      if (!stillSelected(number, item)) return;
      releasePreparedAudio();
      plan('').catch(() => {});
      setMusicPlaybackStatus(`${error.message || 'Could not prepare the exact recording.'} Use Source for selected track for other options.`, '#ff7785');
    } finally {
      if (stillSelected(number, item)) finishMusicPlaybackFeedback(true, options.feedbackId || 0);
    }
  }
  queueItemStatus = (item, i) => {
    const match = matchHelper(item), state = helper.enabled && helper.tracks?.[match]?.state;
    const keep = state === 'ready' ? 'Ready on this phone' : state === 'preparing' ? 'Preparing exact audio'
      : state === 'queued' ? (helper.unmetered ? 'Preparation queued' : 'Waiting for unmetered network')
      : state === 'failed' ? 'Preparation failed' : match ? (helper.enabled ? 'Verified source' : 'Official · online only') : 'Source needed';
    return `${i === musicQueue.idx ? 'Selected · ' : ''}${savedSource(item) ? 'Linked source' : keep}`;
  };
  proactivelyWarmMusicLanding = async () => [];
  preloadNextTwo = prepareUpcoming;
  queuePlayCurrent = async (options = {}) => {
    const item = musicQueue.list[musicQueue.idx];
    if (!item || !title(item)) return;
    const number = ++selection;
    releasePreparedAudio();
    const source = savedSource(item), match = official(item);
    if (!source && match && helper.enabled && helper.tracks?.[match.youtube]) return playPrepared(item, match.youtube, number, options);
    if (helper.enabled) plan('').catch(() => {});
    finishMusicPlaybackFeedback(true, options.feedbackId || 0);
    if (source) { setMusicPlaybackStatus(display(item)); return bbxAndroidOpen(source.type, source.id); }
    if (match) { setMusicPlaybackStatus(display(item)); return bbxAndroidOpen('music-video', match.id); }
    showTrackSource(item);
  };
  const renderQueue = renderMusicQueue;
  renderMusicQueue = function (...args) {
    const result = renderQueue.apply(this, args);
    prepareUpcoming(); // Also handles removal and reordering of already queued entries.
    return result;
  };
  function updateHelperPanel() {
    if (!helperPanel) return;
    helperPanel.hidden = !helper.available;
    helperToggle.textContent = helper.enabled ? 'Turn off music preparation' : 'Enable music preparation';
    helperNote.textContent = helper.enabled ? `Helper ${helper.version} · next two matched tracks prepare on unmetered connections.`
      : 'Use the on-device helper for recordings you may download. Once prepared, tracks can replay in Music without a connection.';
    helperClear.hidden = !helper.enabled;
    preparedRows.replaceChildren();
    for (const [id, track] of Object.entries(helper.tracks || {})) {
      const match = officialSources.find(source => source.youtube === id);
      if (!match || track.state !== 'ready') continue;
      preparedRows.append(button(`Play prepared: ${track.title}`, () => {
        const item = {track:track.title, artist:track.artist, album:match.albums[0], duration:track.seconds};
        playMusicList([item], 0, 'audio');
      }, 'ghost'));
    }
  }
  function confirmHelper(clear) {
    const dialog = el('dialog', 'android-music-source');
    dialog.id = 'androidMusicHelperConsent';
    const heading = el('h2', '', clear ? 'Clear prepared tracks?' : 'Prepare music on this phone?');
    heading.id = 'androidMusicHelperTitle';
    dialog.setAttribute('aria-labelledby', heading.id);
    dialog.append(heading, el('p', 'sub', clear ? 'This removes the private copies and turns off preparation.'
      : 'Only use this for recordings you may download. The first play prepares the full file. BlackBox keeps the audio in the app and prepares up to the next two exact matches on unmetered connections.'),
      button(clear ? 'Clear and turn off' : 'Enable on this phone', () => { dialog.close(); changeHelper(!clear, clear); }, 'primary'),
      button('Cancel', () => dialog.close(), 'ghost'));
    openDialog?.close();
    openDialog = dialog;
    dialog.addEventListener('close', () => { if (openDialog === dialog) openDialog = null; dialog.remove(); }, {once:true});
    document.body.append(dialog); dialog.showModal();
  }
  async function changeHelper(enable, clear = false) {
    if (!enable || clear) { selection++; releasePreparedAudio(); }
    helperToggle.disabled = true;
    try { presentState(await read(`${HELPER}/options`, {enabled:enable, clear})); }
    catch (error) { toast(error.message); }
    finally { helperToggle.disabled = false; }
  }
  warmMusicTrack = async () => '';
  warmMusicLiveSource = async () => null;
  proactivelyWarmMusicItems = () => {};
  wireMusicWarmup = () => {};
  restoreMusicPlaybackSession = () => {};
  loadMusicDownloads = async () => {};

  let audioRead = 0;
  async function refreshAudioRows() {
    const host = byId('androidAudioLibrary');
    if (!host) return;
    const sequence = ++audioRead;
    try {
      const {items} = await read('/api/mobile/library');
      if (state.view !== 'music' || sequence !== audioRead) return;
      const entries = items.filter(e => e.kindId === 'MUSIC');
      const rows = byId('androidAudioLinks');
      rows.replaceChildren();
      for (const e of entries.slice(0,12)) {
        rows.append(button(`${e.offline ? 'Offline · ' : e.queued ? 'Queued · ' : ''}${e.title}`, () => bbxAndroidOpen('play', e.id)));
      }
    } catch { /* The native import action is still available. */ }
  }
  function chooseSelectedSource() {
    const item = musicQueue.list[musicQueue.idx];
    if (item) showTrackSource(item);
    else toast('Choose a track first.');
  }
  const genre = openMusicGenre;
  openMusicGenre = function (name) {
    const result = genre.call(this, name);
    const status = document.querySelector('#musicGenrePage [data-genre-status]');
    if (status) {
      status.textContent = '30 catalogue picks · select a recording to play or link its source';
      status.after(button('Source for selected track', chooseSelectedSource, 'ghost'));
    }
    return result;
  };
  const show = showView;
  showView = function (next) { const result = show.call(this, next); if (next === 'music') refreshAudioRows(); return result; };
  const resume = window.bbxAndroidResume;
  window.bbxAndroidResume = () => {
    resume?.();
    if (state.view === 'music') refreshAudioRows();
    if (helperPanel) refreshHelper().catch(() => {});
  };
  const back = window.bbxAndroidBack;
  window.bbxAndroidBack = () => {
    if (openDialog?.open) { openDialog.close(); return true; }
    if (!byId('settingsWrap')?.hidden) return back?.() || false;
    if (byId('bgQueuePanel')?.hidden === false) { byId('bgQueueClose')?.click(); return true; }
    if (state.view === 'musicGenre') { byId('musicGenreBack')?.click(); return true; }
    if (state.view === 'musicDetail') { byId('musicDetailBack')?.click(); return true; }
    const albumBack = state.view === 'music' && document.querySelector('.music-album-back-v63');
    if (albumBack) { albumBack.click(); return true; }
    return back?.() || false;
  };

  function controls() {
    const shelf = byId('musicLibrarySidebar'), hero = document.querySelector('.music-hero-content');
    if (!shelf || !hero) return;
    const note = document.querySelector('.bg-queue-note-v49');
    if (note) note.textContent = 'Choose a queued track to play its recording. Tracks without a source stay in the list.';
    const lead = hero.querySelector(':scope > p');
    if (lead) lead.textContent = 'Browse the original v90 genres, artists and albums. Play available official sources here, or attach your own exact audio recording.';
    const meta = hero.querySelector('.music-hero-meta-v47');
    if (meta?.lastElementChild) meta.lastElementChild.textContent = 'Offline copies from downloadable files only';
    const own = el('section', 'android-audio-library'); own.id = 'androidAudioLibrary';
    const rows = el('div', 'android-audio-library'); rows.id = 'androidAudioLinks';
    own.append(el('p','sub android-music-note','Your files and offline copies'),
      button('Import audio / playlists', () => bbxAndroidOpen('library')),
      button('Playback queue', () => openMusicQueuePanel()),
      button('Source for selected track', chooseSelectedSource), rows);
    helperPanel = el('section', 'android-audio-library'); helperPanel.hidden = true;
    helperNote = el('p', 'sub android-music-note');
    helperToggle = button('Enable music preparation', () => helper.enabled ? changeHelper(false) : confirmHelper(false));
    helperClear = button('Clear prepared tracks', () => confirmHelper(true), 'ghost');
    preparedRows = el('div', 'android-audio-library');
    helperPanel.append(el('p', 'sub android-music-note', 'Music preparation'), helperNote, helperToggle, helperClear, preparedRows);
    shelf.append(own, helperPanel);
    byId('bgClose')?.addEventListener('click', () => {
      selection++; releasePreparedAudio();
      if (helper.enabled) plan('').catch(() => {});
    });
    refreshHelper().catch(() => {});
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', controls, {once:true});
  else controls();
})();
