/* Only the Android audio engine is different. Use the shipped v90 views, queue and controls. */
(() => {
  'use strict';
  if (!window.bbxAndroidOpen) return;
  const engine = '/api/mobile/music-helper';
  const byId = id => document.getElementById(id);
  const keyPattern = /^[a-f0-9]{64}$/;
  const audioUrl = key => `/prepared-audio/${key}.m4a`;
  const title = item => item.track || item.title || '';
  const artist = item => item.artist || item.channel || '';
  const clean = value => String(value || '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  const norm = value => clean(value).normalize('NFKD').toLowerCase()
    .replace(/\p{M}/gu, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
  const identity = item => item.catalogTrackId ? `album:${item.catalogTrackId}` : item.id && item.album ? `pick:${item.id}`
    : JSON.stringify([artist(item), title(item), item.album || '', item.duration || 0, item.explicit === true]);
  const recordingKey = track => JSON.stringify([track.ref, track.title, track.artist, track.album, track.seconds, track.source]);

  // Internal shortcuts for known exact recordings. Every other resolvable v90 track uses the same engine.
  const pinned = [
    ['Sabrina Carpenter', 'Espresso', 175, '51zjlMhdSTE', ['Espresso', "Short n' Sweet"]],
    ['Billie Eilish', 'bad guy', 194, 'ZD6rXLXZOEI', ['WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?']],
    ['The Weeknd', 'Blinding Lights', 200, 'fHI8X4OXluQ', ['After Hours', 'After Hours (Deluxe)', 'The Highlights']],
    ['Burna Boy', 'On the Low', 185, 'UFsskuvj8GQ', ['On the Low', 'African Giant']],
    ['Black Sherif', 'Kwaku the Traveller', 185, 'Hb6I7GoTvzI', ['Kwaku the Traveller', 'The Villain I Never Was']],
  ].map(([a,t,seconds,id,albums]) => ({artist:norm(a), title:norm(t), seconds, id, albums:albums.map(norm)}));
  function recording(item) {
    if (!item) return null;
    if (item._preparedRecording) return item._preparedRecording;
    const track = {ref:clean(identity(item)).slice(0,640), title:clean(title(item)), artist:clean(artist(item)),
      album:clean(item.album), seconds:Math.round(Number(item.duration)), source:''};
    if (!track.title || !track.artist || !track.album || [track.title,track.artist,track.album].some(s => s.length > 240)
        || !Number.isInteger(track.seconds) || track.seconds <= 0 || track.seconds > 1800) return null;
    const a = norm(track.artist), t = norm(track.title), album = norm(track.album);
    track.source = pinned.find(p => p.artist === a && p.title === t && p.albums.includes(album)
      && Math.abs(p.seconds - track.seconds) <= 3)?.id || '';
    return track;
  }

  // The desktop network/install paths must never run on Android, including while browsing or queueing.
  showMusicSourcePopup = () => {};
  refreshMusicYoutubeState = async () => false;
  warmMusicTrack = async () => '';
  warmMusicLiveSource = async () => null;
  proactivelyWarmMusicItems = () => {};
  proactivelyWarmMusicLanding = async () => [];
  wireMusicWarmup = () => {};
  ensureQueueLookahead = () => 0;
  queueMusicAutoDownload = () => {};
  autoAddPlayedMusicToActiveCollection = () => 0;
  setMusicPlaybackStatus = () => {};

  const originalIdentity = queueIdentity;
  queueIdentity = item => item?._preparedRecording?.ref || (item?.album && title(item) ? identity(item) : originalIdentity(item));
  const originalValidId = validPlaybackId;
  // A private cache key is a row id only. It is never passed to the extractor as a source id.
  validPlaybackId = id => keyPattern.test(id || '') || originalValidId(id);
  const originalOfflineUrl = musicStableOfflineUrl;
  musicStableOfflineUrl = id => keyPattern.test(id || '') ? audioUrl(id) : originalOfflineUrl(id);
  const originalQueueItem = musicQueueItemFromDownload;
  musicQueueItemFromDownload = row => ({...originalQueueItem(row), _preparedRecording:row._preparedRecording});
  const originalSessionItem = musicSessionItem;
  musicSessionItem = item => item && {...originalSessionItem(item), id:item.id, album:item.album,
    catalogTrackId:item.catalogTrackId, explicit:item.explicit, _preparedRecording:item._preparedRecording};

  getCachedMusicSearchResults = async query => {
    const text = String(query || '').trim().slice(0,130);
    const originals = window.bbxMusicSearchV76?.search(text, {limit:30}).songs?.map(row => row.track) || [];
    if (originals.length || text.length < 2) return originals;
    const {results} = await window.bbxAndroidRequest(`/api/mobile/music?q=${encodeURIComponent(text)}`);
    return results;
  };

  let session = null, sequence = 0, requestId = 0, lastSnapshot = 0;
  let cached = new Set(), albumJob = null;
  async function read(path = '', data, signal) {
    const id = ++requestId;
    const response = await fetch(engine + path, {signal, cache:'no-store',
      ...(data === undefined ? {} : {method:'POST', body:JSON.stringify(data)})});
    if (!response.ok) throw new Error('Audio engine unavailable');
    const result = await response.json();
    if (id > lastSnapshot && result.sequence >= sequence) {
      lastSnapshot = id;
      cached = new Set(Object.keys(result.tracks || {}).filter(key => result.tracks[key].state === 'ready'));
      refreshSaved();
    }
    return result;
  }
  function refreshSaved() {
    let changed = false;
    for (const row of musicDownloads) {
      const offline = cached.has(row.id);
      if (row.offline !== offline) {
        row.offline = offline; row.offlineState = offline ? 'ready' : 'missing'; changed = true;
      }
    }
    if (changed) renderMusicDownloads();
  }
  loadMusicDownloads = () => {
    try {
      const stored = JSON.parse(localStorage.getItem(MUSIC_DOWNLOADS_KEY) || '[]');
      musicDownloads = (Array.isArray(stored) ? stored : []).filter(row => keyPattern.test(row?.id) && row._preparedRecording);
    } catch { musicDownloads = []; }
    loadMusicCollections();
    renderMusicDownloads();
    read().catch(() => {});
  };
  function remember(item, track, key, collection = activeCollection()) {
    const row = {...item, id:key, ytId:key, _preparedRecording:track,
      title:track.title, artist:track.artist, duration:track.seconds, video:false, audio:true,
      audioUrl:audioUrl(key), offline:true, offlineState:'ready', offlineStage:''};
    musicDownloads = [row, ...musicDownloads.filter(old => old.id !== key)].slice(0,500);
    resolveCollectionPending(item, key);
    if (collection?.autoAddPlayed && !collection.ids.includes(key)) collection.ids.unshift(key);
  }
  const removeSaved = removeMusicDownload;
  removeMusicDownload = key => {
    removeSaved(key);
    if (keyPattern.test(key)) read('/forget', {key}).catch(() => {});
  };

  // Keep the existing album download action. It is explicit and lower priority than a Play tap
  // or its two upcoming tracks. It does not set a playback plan or show a new helper surface.
  downloadMusicAlbumV63 = async (album, control) => {
    if (albumJob) return;
    const items = musicAlbumTracksV63(album), specs = items.map(recording);
    const tracks = specs.filter(Boolean);
    if (!tracks.length || tracks.length > 64) return;
    const job = albumJob = {}, until = Date.now() + 20 * 60 * 1000;
    if (control) { setMusicElementLoading(control, true); delete control.dataset.loadingLabel; }
    try {
      let snapshot = await read('/album', {tracks});
      while (snapshot.available) {
        const rows = new Map(Object.entries(snapshot.tracks).map(([key, row]) => [recordingKey(row.recording), {...row, key}]));
        const matches = specs.map(track => track && rows.get(recordingKey(track)));
        if (Date.now() >= until || matches.every(row => !row || row.state === 'ready' || row.state === 'failed')) {
          const ready = matches.filter(row => row?.state === 'ready');
          if (ready.length) {
            const collection = ensureAlbumCollectionV63(album, []);
            matches.forEach((row, idx) => { if (row?.state === 'ready') remember(items[idx], specs[idx], row.key, null); });
            const ids = ready.map(row => row.key);
            collection.ids = [...new Set([...ids, ...collection.ids])];
            saveMusicDownloads(); saveMusicCollections(false); renderMusicDownloads();
          }
          musicAlbumJobsV63.set(album.id, {active:false, total:items.length, saved:ready.length, failed:items.length - ready.length});
          refreshMusicAlbumJobUiV63(album);
          break;
        }
        await new Promise(resolve => setTimeout(resolve, 2200));
        snapshot = await read();
      }
    } catch { /* Leave the original action retryable without a helper popup. */ }
    finally {
      await read('/album', {tracks:[]}).catch(() => {});
      if (albumJob === job) albumJob = null;
      if (control) setMusicElementLoading(control, false);
    }
  };

  function plan(selected, upcoming = [], retry = false, signal) {
    return read('/plan', {sequence:++sequence, selected, upcoming, retry}, signal);
  }
  function stillSelected(live) { return session === live && musicQueue.list[musicQueue.idx] === live.item; }
  function planQueue(live, retry = false) {
    // Use exactly these slots. An unresolved or duplicate slot cannot pull in a later song.
    const upcoming = musicQueue.list.slice(musicQueue.idx + 1, musicQueue.idx + 3).map(recording);
    const windowKey = JSON.stringify(upcoming);
    if (!retry && live.windowKey === windowKey) return;
    live.windowKey = windowKey;
    return plan(live.track, upcoming, retry, live.controller.signal);
  }
  preloadNextTwo = () => { if (session?.track && stillSelected(session)) planQueue(session)?.catch(() => {}); };

  function release(live, hideBar) {
    session = null;
    clearTimeout(live.deadline);
    live.controller.abort();
    clearMusicPlaybackFeedback('cancel', live.feedbackId);
    musicAutoAdvanceBlocked = true;
    live.audio.pause();
    live.audio.removeAttribute('src');
    live.audio.load();
    if (live.blob) URL.revokeObjectURL(live.blob);
    if (hideBar) live.bar.hidden = true;
    closeMusicFrequency();
  }
  function stop(live) {
    if (session !== live) return;
    release(live, true);
    plan(null).catch(() => {});
  }
  const persist = persistMusicPlaybackSession;
  persistMusicPlaybackSession = () => { if (session?.started && stillSelected(session)) persist(); };

  async function play(live, options) {
    try {
      if (!live.track) { stop(live); return; }
      // Restoring the UI may reuse a saved file, but must not start a provider request on launch.
      if (options.intent === 'restore') {
        const snapshot = await read('', undefined, live.controller.signal);
        if (!stillSelected(live)) return;
        if (!Object.values(snapshot.tracks).some(row => row.state === 'ready' && recordingKey(row.recording) === recordingKey(live.track))) {
          clearMusicPlaybackFeedback('cancel', live.feedbackId);
          clearTimeout(live.deadline);
          session = null;
          return;
        }
      }
      let snapshot = await planQueue(live, true), key;
      while (stillSelected(live)) {
        key = snapshot.selected;
        const row = snapshot.tracks?.[key];
        if (!snapshot.available || !row || recordingKey(row.recording) !== recordingKey(live.track) || row.state === 'failed') {
          stop(live); return;
        }
        if (row.state === 'ready' && keyPattern.test(key)) break;
        await new Promise(resolve => setTimeout(resolve, 650));
        if (!stillSelected(live)) return;
        snapshot = await read('', undefined, live.controller.signal);
      }
      if (!stillSelected(live)) return;
      const response = await fetch(audioUrl(key), {cache:'no-store', signal:live.controller.signal});
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('audio/mp4')) throw new Error('Missing cached audio');
      const blob = await response.blob();
      if (!stillSelected(live)) return;
      if (!blob.size || blob.size > 24 * 1024 * 1024) throw new Error('Invalid cached audio');
      // Use the original audio node and its normal seek, pause and end handlers. The WebView's
      // local-only configuration lets this one tap start playback after asynchronous preparation.
      live.audio.src = live.blob = URL.createObjectURL(blob);
      await live.audio.play();
      if (!stillSelected(live)) return;
      live.started = true;
      musicAutoAdvanceBlocked = false;
      byId('bgTitle').textContent = title(live.item);
      byId('bgArtist').textContent = artist(live.item);
      const art = byId('bgArt'), picture = live.item.artwork || live.item.thumbnail;
      if (picture) art.src = safeArtwork(picture); else art.removeAttribute('src');
      art.style.display = 'block';
      showMusicFrequencyMeta(live.item);
      startMusicFrequency();
      live.bar.hidden = false;
      clearMusicPlaybackFeedback('cancel', live.feedbackId);
      remember(live.item, live.track, key);
      saveMusicDownloads(); saveMusicCollections(false);
      renderMusicDownloads(); updateQCount(); persistMusicPlaybackSession();
    } catch {
      // No preview, video, source dialog, or retry on another recording.
      if (stillSelected(live)) stop(live);
    } finally { clearTimeout(live.deadline); }
  }

  function attachSpinner(control) {
    const id = beginMusicPlaybackFeedback(control);
    // The non-button queue rows also use the original ring, but not its desktop stage caption.
    if (musicPlaybackActionElement) delete musicPlaybackActionElement.dataset.loadingLabel;
    return id;
  }
  queuePlayCurrent = (options = {}) => {
    const item = musicQueue.list[musicQueue.idx];
    if (!item) return;
    if (session?.item === item && !session.started) return session.playing;
    const player = ensureBgAudio();
    if (!player) return;
    const control = options.control;
    const fromQueue = control?.matches('.bg-queue-row-v49');
    if (session) release(session, false);
    const live = session = {...player, item, track:recording(item), fromQueue, started:false, controller:new AbortController()};
    live.feedbackId = attachSpinner(fromQueue ? document.querySelector('#bgQueueList .playing') : control);
    live.deadline = setTimeout(() => stop(live), 195000);
    live.playing = play(live, options);
    return live.playing;
  };
  queueItemStatus = (_item, idx) => idx === musicQueue.idx ? (session?.started && !session.audio.paused ? 'Playing now' : 'Current') : 'Up next';
  const originalRenderQueue = renderMusicQueue;
  renderMusicQueue = function () {
    originalRenderQueue();
    if (!session) return;
    if (!musicQueue.list.length) stop(session);
    else if (stillSelected(session)) {
      if (session.fromQueue && !session.started && !musicPlaybackActionElement?.isConnected) {
        session.feedbackId = attachSpinner(document.querySelector('#bgQueueList .playing'));
      }
      preloadNextTwo();
    }
  };
  const originalNext = queueNext;
  queueNext = options => {
    if (options?.intent === 'auto-ended' && (!session?.started || !session.audio.ended || !stillSelected(session))) return;
    if (musicQueue.idx + 1 < musicQueue.list.length) return originalNext(options);
  };

  const back = window.bbxAndroidBack;
  window.bbxAndroidBack = () => {
    if (!byId('settingsWrap')?.hidden) return back?.() || false;
    if (byId('bgQueuePanel')?.hidden === false) { byId('bgQueueClose')?.click(); return true; }
    if (state.view === 'musicGenre') { byId('musicGenreBack')?.click(); return true; }
    if (state.view === 'musicDetail') { byId('musicDetailBack')?.click(); return true; }
    const albumBack = state.view === 'music' && document.querySelector('.music-album-back-v63');
    if (albumBack) { albumBack.click(); return true; }
    return back?.() || false;
  };
  document.addEventListener('DOMContentLoaded', () => {
    byId('bgSeek').setAttribute('aria-label', 'Track position');
    byId('bgClose').addEventListener('click', () => { if (session) stop(session); });
    const note = document.querySelector('.bg-queue-note-v49');
    if (note) note.textContent = 'Play or remove a track without changing the order of the others.';
  }, {once:true});
})();
