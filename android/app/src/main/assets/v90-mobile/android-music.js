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

  // Known exact IDs are a fast path; they do not limit the catalogue-wide helper.
  // The same aliases also feed the original official-player fallback.
  // SoundCloud references verified against the artists' accounts:
  // https://soundcloud.com/sabrinacarpenter/sabrina-carpenter-espresso
  // https://soundcloud.com/blacksherif/kweku-the-traveler
  const officialSources = [
    ['Sabrina Carpenter', 'Espresso', 175, '51zjlMhdSTE', ['Espresso', "Short n' Sweet"], 'sc:1799087761'],
    ['Billie Eilish', 'bad guy', 194, 'ZD6rXLXZOEI', ['WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?']],
    ['The Weeknd', 'Blinding Lights', 200, 'fHI8X4OXluQ', ['After Hours', 'After Hours (Deluxe)', 'The Highlights']],
    ['Burna Boy', 'On the Low', 185, 'UFsskuvj8GQ', ['On the Low', 'African Giant']],
    ['Black Sherif', 'Kwaku the Traveller', 185, 'Hb6I7GoTvzI', ['Kwaku the Traveller', 'The Villain I Never Was'], 'sc:1229321974'],
  ].map(([a, t, seconds, youtube, albums, soundCloud]) => ({artist:norm(a), track:norm(t), seconds, id:soundCloud || youtube, youtube, soundCloud, albums}));
  function official(item) {
    const a = norm(artist(item)), t = norm(title(item)), album = norm(item.album);
    return officialSources.find(s => s.artist === a && s.track === t && s.albums.some(name => norm(name) === album)
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
    if (['music-video', 'prepared-youtube'].includes(entry?.type) && videoId(entry.id)) return entry;
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
  function showTrackSource(item, reason = '') {
    openDialog?.close();
    const dialog = el('dialog', 'android-music-source');
    dialog.id = 'androidMusicSource';
    const source = official(item), linked = savedSource(item);
    const heading = el('h2', '', title(item));
    heading.id = 'androidTrackTitle';
    dialog.setAttribute('aria-labelledby', heading.id);
    const status = el('p', 'sub android-action-status'); status.role = 'status';
    const info = el('p', 'sub', source ? 'This track has an official online player. You can also attach your own audio file link.'
      : 'Link this exact recording if the Music engine cannot verify a match. BlackBox will not play a different version.');
    if (reason) { status.textContent = reason; status.classList.add('err'); }
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
        remember(item, 'music-video', id); dialog.close(); openLinked('music-video', id); return;
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
        else { dialog.close(); openLinked('play', result.id); }
      } catch (error) { status.classList.add('err'); status.textContent = error.message; }
      finally { control.disabled = false; }
    }
    function prepareLink() {
      const id = youtubeLink(input.value.trim());
      if (!id) { status.textContent = 'Enter the official YouTube link to the exact full recording.'; input.focus(); return; }
      remember(item, 'prepared-youtube', id);
      dialog.close();
      startSelected(item);
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
        if (mediaId) { remember(item, 'play', mediaId); dialog.close(); openLinked('play', mediaId); }
      }};
      try { window.BlackBoxAndroid.pickMusic(id, display(item).slice(0,140)); }
      catch { fileRequest.finish('', 'Android’s file picker is unavailable.'); fileRequest = null; }
    }
    if (linked) {
      dialog.append(heading, el('p', 'sub', [artist(item), item.album].filter(Boolean).join(' · ')));
      const saved = el('div', 'android-tools-row');
      saved.append(button('Play saved source', () => {
        dialog.close();
        if (linked.type === 'prepared-youtube' && helper.enabled) startSelected(item);
        else openLinked(linked.type === 'play' ? 'play' : 'music-video', linked.id);
      }));
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
      button('Download audio file', event => useLink(event, true)));
    if (helper.enabled) controls.append(button('Prepare exact YouTube link', prepareLink));
    controls.append(button('Close', () => dialog.close(), 'ghost'));
    if (source) {
      const providers = el('div', 'android-tools-row');
      if (source.soundCloud) providers.append(button('Play on SoundCloud here', () => {
        dialog.close(); openLinked('music-video', source.soundCloud);
      }));
      providers.append(button('Play on YouTube here', () => {
        dialog.close(); openLinked('music-video', source.youtube);
      }));
      dialog.append(providers);
    }
    dialog.append(el('p', 'sub', helper.enabled
      ? 'Use your own file, or paste the official YouTube link for this exact track. Prepare it only if you are allowed to keep a private copy.'
      : 'Use an authorized direct audio file, or an official YouTube link for online-only playback.'), input, controls, status);
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
  let session = null, planSequence = 0, readSequence = 0, shownSequence = 0, helperTimer;
  let helperPanel, helperNote, helperToggle, helperClear, preparedRows;
  let helperRows = new Map();
  const trackKey = track => track ? JSON.stringify([track.ref, track.title, track.artist, track.album, track.seconds, track.source]) : '';
  const clean = value => String(value || '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  function recording(item) {
    if (!item) return null;
    if (item._preparedRecording) return item._preparedRecording;
    const link = savedSource(item);
    if (link && link.type !== 'prepared-youtube') return null;
    const source = link?.id || official(item)?.youtube || '';
    const seconds = Math.round(Number(item.duration));
    const track = {ref:clean(identity(item)).slice(0,640), title:clean(title(item)), artist:clean(artist(item)),
      album:clean(item.album), seconds: Number.isFinite(seconds) && seconds > 0 && seconds <= 1800 ? seconds : 0, source};
    if (!track.title || !track.artist || [track.title,track.artist,track.album].some(s => s.length > 240)
        || seconds > 1800 || !source && (!track.seconds || !track.album)) return null;
    return track;
  }
  function startSelected(item) {
    if (musicQueue.list[musicQueue.idx] === item) return queuePlayCurrent({intent:'user'});
    return playMusicList([item], 0, 'audio');
  }
  function openLinked(type, id) {
    releasePreparedAudio();
    if (helper.enabled) plan(null).catch(() => {});
    return bbxAndroidOpen(type, id);
  }
  async function readHelper(path = '', body) {
    const request = ++readSequence;
    const result = await read(HELPER + path, body);
    if (request > shownSequence) {
      shownSequence = request;
      const updated = JSON.stringify(result) !== JSON.stringify(helper);
      helper = result;
      if (updated) {
        helperRows = new Map(Object.values(result.tracks || {}).map(row => [trackKey(row.recording), row]));
        updateHelperPanel(); renderQueue();
      }
    }
    return result;
  }
  function plan(selected, upcoming = [], retry = false) {
    return readHelper('/plan', {sequence:++planSequence, selected, upcoming, retry});
  }
  function releasePreparedAudio() {
    const old = session;
    session = null;
    clearTimeout(helperTimer);
    if (!old) return;
    document.documentElement.classList.remove('bbx-prepared-player');
    musicAutoAdvanceBlocked = true;
    musicBgLoadSeq++;
    musicBgPendingFinish?.({ok:false, stale:true});
    old.read?.abort();
    old.audio.removeEventListener('playing', old.played);
    old.audio.pause(); old.audio.removeAttribute('src'); old.audio.load();
    old.bar.hidden = true;
    if (old.blob) URL.revokeObjectURL(old.blob);
  }
  function stillSelected(live) { return session === live && musicQueue.list[musicQueue.idx] === live.item; }
  function followPreparation(live) {
    clearTimeout(helperTimer);
    if (!stillSelected(live) || !live.started || !Object.values(helper.tracks).some(t => ['matching','preparing','queued'].includes(t.state))) return;
    helperTimer = setTimeout(async () => {
      if (!stillSelected(live)) return;
      try { await readHelper(); if (stillSelected(live)) followPreparation(live); }
      catch { /* A status failure must not interrupt audio that is already playing. */ }
    }, helper.unmetered ? 1100 : 4000);
  }
  function prepareUpcoming(retry = false) {
    const live = session;
    if (!helper.enabled || !live || !stillSelected(live)) return;
    // Only these two slots; missing metadata or a linked file does not pull in any later item.
    const next = musicQueue.list.slice(musicQueue.idx + 1, musicQueue.idx + 3).map(recording);
    const key = JSON.stringify(next);
    if (!retry && key === live.windowKey) return;
    live.windowKey = key;
    return plan(live.recording, next, retry).then(result => {
      if (stillSelected(live) && live.windowKey === key) followPreparation(live);
      return result;
    });
  }
  async function playPrepared(item, spec, options) {
    const player = ensureBgAudio();
    if (!player) return;
    const live = session = {...player, item, recording:spec, played:null};
    const started = Date.now();
    document.documentElement.classList.add('bbx-prepared-player');
    byId('bgTitle').textContent = title(item);
    byId('bgArtist').textContent = artist(item);
    byId('bgTime').textContent = 'Preparing…';
    byId('bgPlayBtn').disabled = true;
    byId('bgSeek').disabled = true;
    player.bar.hidden = false;
    try {
      let result = await prepareUpcoming(true);
      let id = result?.selected;
      while (stillSelected(live)) {
        id = result?.selected;
        const row = result?.tracks?.[id];
        if (!result?.enabled) throw new Error('Music preparation is turned off.');
        if (!row || trackKey(row.recording) !== trackKey(spec)) throw new Error('The recording selection changed. Try again.');
        if (row.state === 'ready') break;
        if (row.state === 'failed') throw new Error(row.message);
        const seconds = Math.floor((Date.now() - started) / 1000);
        if (seconds > 190) throw new Error('This recording is taking too long to prepare.');
        const stage = row.state === 'matching' ? 'Finding exact source' : 'Preparing';
        byId('bgTime').textContent = `${stage} · ${seconds}s`;
        setMusicPlaybackStatus(`${stage}: ${title(item)} · ${seconds}s`, '#e9c979');
        await new Promise(resolve => setTimeout(resolve, 900));
        if (!stillSelected(live)) return;
        result = await readHelper();
      }
      if (!stillSelected(live)) return;
      // The existing player can seek a local blob; no external media URL or loopback server is exposed.
      const controller = live.read = new AbortController();
      const file = await fetch(`/prepared-audio/${id}.m4a`, {cache:'no-store', signal:controller.signal});
      if (!file.ok || !file.headers.get('Content-Type')?.startsWith('audio/mp4')) throw new Error('The prepared file is no longer on this phone.');
      const blob = await file.blob();
      if (!stillSelected(live)) return;
      live.read = null;
      if (!blob.size || blob.size > 24 * 1024 * 1024) throw new Error('The prepared file has an invalid size.');
      live.blob = URL.createObjectURL(blob);
      live.played = () => {
        if (!stillSelected(live) || player.audio.src !== live.blob) return;
        live.started = true;
        setMusicPlaybackStatus(`${display(item)} · playing from this phone`, '#65ddbc');
        followPreparation(live);
      };
      player.audio.addEventListener('playing', live.played, {once:true});
      byId('bgPlayBtn').disabled = false;
      byId('bgSeek').disabled = false;
      const outcome = await playBgAudio(live.blob, {...item, title:title(item), artist:artist(item)});
      if (!stillSelected(live)) return;
      if (!outcome.ok) throw new Error('The prepared recording could not start.');
      if (outcome.needsTap) setMusicPlaybackStatus(`${title(item)} is prepared · tap Play in the player`, '#e9c979');
    } catch (error) {
      if (!stillSelected(live)) return;
      releasePreparedAudio();
      plan(null).catch(() => {});
      const message = error.message || 'Could not prepare the exact recording.';
      setMusicPlaybackStatus(`${message} Choose Source for selected track for other options.`, '#ff7785');
      if (options.intent !== 'auto-ended' && !openDialog?.open) showTrackSource(item, message);
      else toast(message);
    } finally {
      if (stillSelected(live) || !session) finishMusicPlaybackFeedback(true, options.feedbackId || 0);
    }
  }
  queueItemStatus = (item, i) => {
    const spec = helper.enabled && recording(item), state = spec && helperRows.get(trackKey(spec))?.state;
    const keep = state === 'ready' ? 'Ready on this phone' : state === 'matching' ? 'Finding exact source'
      : state === 'preparing' ? 'Preparing exact audio'
      : state === 'queued' ? (helper.unmetered ? 'Preparation queued' : 'Waiting for unmetered network')
      : state === 'failed' ? 'Preparation failed' : spec ? 'Find exact audio on play'
      : savedSource(item) ? 'Linked source' : official(item) ? 'Official · online only' : 'Source needed';
    return `${i === musicQueue.idx ? 'Selected · ' : ''}${keep}`;
  };
  proactivelyWarmMusicLanding = async () => [];
  preloadNextTwo = () => prepareUpcoming()?.catch(() => {});
  // The mobile UI does not display the spectrum. Keep audio on the media element's normal output.
  primeMusicFrequencyAnalyser = () => false;
  const nextTrack = queueNext;
  queueNext = function (options) {
    // The desktop ended handler waits briefly. Do not let it skip a newer manual selection.
    if (options?.intent === 'auto-ended' && (!session?.started || !session.audio.ended || !stillSelected(session))) return;
    return nextTrack.call(this, options);
  };
  queuePlayCurrent = async (options = {}) => {
    const item = musicQueue.list[musicQueue.idx];
    if (!item || !title(item)) return;
    releasePreparedAudio();
    const source = savedSource(item), match = official(item);
    const spec = helper.enabled && recording(item);
    if (spec) return playPrepared(item, spec, options);
    if (helper.enabled) plan(null).catch(() => {});
    finishMusicPlaybackFeedback(true, options.feedbackId || 0);
    if (source) { setMusicPlaybackStatus(display(item)); return bbxAndroidOpen(source.type === 'play' ? 'play' : 'music-video', source.id); }
    if (match) { setMusicPlaybackStatus(display(item)); return bbxAndroidOpen('music-video', match.id); }
    showTrackSource(item);
  };
  const renderQueue = renderMusicQueue;
  renderMusicQueue = function (...args) {
    const result = renderQueue.apply(this, args);
    if (session && !musicQueue.list.length) { releasePreparedAudio(); plan(null).catch(() => {}); }
    else preloadNextTwo(); // Also handles removal and reordering of already queued entries.
    return result;
  };
  function updateHelperPanel() {
    if (!helperPanel) return;
    helperPanel.hidden = !helper.available;
    helperToggle.textContent = helper.enabled ? 'Turn off music preparation' : 'Enable music preparation';
    helperNote.textContent = helper.enabled ? `Engine ${helper.version} · finds full recordings for the v90 catalogue. Prepares the next two queue slots on unmetered connections.`
      : 'Use the on-device helper for recordings you may download. Once prepared, tracks can replay in Music without a connection.';
    helperClear.hidden = !helper.enabled && !helper.stored;
    preparedRows.replaceChildren();
    const ready = Object.values(helper.tracks || {}).filter(row => row.state === 'ready');
    if (!ready.length) return;
    const shelf = el('details', 'android-prepared-tracks');
    shelf.open = preparedRows.dataset.expanded !== 'false';
    shelf.ontoggle = () => { preparedRows.dataset.expanded = String(shelf.open); };
    shelf.append(el('summary', '', `Prepared music · ${ready.length}`));
    const list = el('div', 'android-audio-library');
    for (const row of ready) {
      const track = row.recording;
      const play = button(`Play prepared: ${track.title} — ${track.artist}`, () => {
        playMusicList([{track:track.title, artist:track.artist, album:track.album, duration:track.seconds,
          _preparedRecording:track}], 0, 'audio');
      }, 'ghost');
      play.title = track.album;
      list.append(play);
    }
    shelf.append(list);
    preparedRows.append(shelf);
  }
  function confirmHelper(clear) {
    const dialog = el('dialog', 'android-music-source');
    dialog.id = 'androidMusicHelperConsent';
    const heading = el('h2', '', clear ? 'Clear prepared tracks?' : 'Prepare music on this phone?');
    heading.id = 'androidMusicHelperTitle';
    dialog.setAttribute('aria-labelledby', heading.id);
    dialog.append(heading, el('p', 'sub', clear ? 'This removes the private copies and turns off preparation.'
      : 'Only use this for recordings you may download. The first play prepares the full file. BlackBox looks up the exact recording, keeps the audio in the app and prepares up to the next two queued tracks on unmetered connections.'),
      button(clear ? 'Clear and turn off' : 'Enable on this phone', () => { dialog.close(); changeHelper(!clear, clear); }, 'primary'),
      button('Cancel', () => dialog.close(), 'ghost'));
    openDialog?.close();
    openDialog = dialog;
    dialog.addEventListener('close', () => { if (openDialog === dialog) openDialog = null; dialog.remove(); }, {once:true});
    document.body.append(dialog); dialog.showModal();
  }
  async function changeHelper(enable, clear = false) {
    if (!enable || clear) releasePreparedAudio();
    helperToggle.disabled = true;
    try { await readHelper('/options', {enabled:enable, clear}); }
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
    if (helperPanel) readHelper().then(() => { if (session) followPreparation(session); }).catch(() => {});
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
    if (meta?.lastElementChild) meta.lastElementChild.textContent = 'Offline copies stay on this phone';
    const own = el('section', 'android-audio-library'); own.id = 'androidAudioLibrary';
    const rows = el('div', 'android-audio-library'); rows.id = 'androidAudioLinks';
    own.append(el('p','sub android-music-note','Your files and offline copies'),
      button('Import audio / playlists', () => bbxAndroidOpen('library')),
      button('Playback queue', () => openMusicQueuePanel()),
      button('Source for selected track', chooseSelectedSource), rows);
    helperPanel = el('section', 'android-audio-library'); helperPanel.id = 'androidMusicHelper'; helperPanel.hidden = true;
    helperNote = el('p', 'sub android-music-note');
    helperToggle = button('Enable music preparation', () => helper.enabled ? changeHelper(false) : confirmHelper(false));
    helperToggle.id = 'androidMusicHelperToggle';
    helperClear = button('Clear prepared tracks', () => confirmHelper(true), 'ghost');
    preparedRows = el('div', 'android-audio-library'); preparedRows.id = 'androidPreparedMusic';
    helperPanel.append(el('p', 'sub android-music-note', 'Music preparation'), helperNote, helperToggle, helperClear, preparedRows);
    shelf.append(helperPanel, own);
    const seek = byId('bgSeek'), time = byId('bgTime');
    if (seek && time) seek.after(time);
    seek?.setAttribute('aria-label', 'Track position');
    byId('bgClose')?.addEventListener('click', () => {
      releasePreparedAudio();
      if (helper.enabled) plan(null).catch(() => {});
    });
    readHelper().catch(() => {});
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', controls, {once:true});
  else controls();
})();
