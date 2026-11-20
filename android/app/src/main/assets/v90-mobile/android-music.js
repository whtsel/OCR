/* Only the Android audio engine is different. Use the shipped v90 views, queue and controls. */
(() => {
  'use strict';
  if (!window.bbxAndroidOpen) return;
  const engine = '/api/mobile/music-helper';
  const planTimeout = 15_000, localOpenTimeout = 12_000;
  // Consent to one track in the previous build does not grant permission for two.
  const mobileLookaheadKey = 'bbxAndroidMusicMobileQueue';
  let mobileLookahead = false, helperAvailable = false, mobileSetting, queueNote, transition;
  try { mobileLookahead = localStorage.getItem(mobileLookaheadKey) === '1'; } catch { /* Keep opt-in off. */ }
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
  function recording(candidate) {
    const item = catalogueItem(candidate);
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

  // On WebViews that suspend Web Audio, leave the element on Android's normal audio output.
  // A visualizer must not capture the song into a silent, suspended graph.
  const attachSpectrum = attachMusicFrequencySource;
  attachMusicFrequencySource = () => musicSpectrumV51.ctx?.state === 'running' ? attachSpectrum() : null;

  // The original playlist stores short row IDs and pending keys, not full album objects.
  // Give its unsaved catalogue rows a local alias; resolve it only against packaged metadata.
  const catalogueAlias = ref => /^(?:pick:[0-9]+|album:[0-9]+:[0-9]+:[0-9]+)$/.test(ref || '')
    ? 'catalog_' + ref.replace(/:/g, '_') : '';
  const isAlias = id => /^catalog_(?:pick_[0-9]+|album_[0-9]+_[0-9]+_[0-9]+)$/.test(id || '');
  function catalogueItem(item) {
    if (item?._preparedRecording || item?.album) return item;
    const alias = [item?.ytId, item?.id, catalogueAlias(item?.key)].find(isAlias);
    if (!alias) return item;
    const [, type, id, disc, number] = alias.split('_');
    let row;
    if (type === 'pick') {
      for (const shelf of MUSIC_GENRES_V50) {
        const pick = shelf.results.find(entry => String(entry.id) === id);
        if (pick) { row = {...pick, genre:shelf.title}; break; }
      }
    } else {
      const album = musicAlbumCatalogV63?.byId.get(id);
      const ref = `${id}:${disc}:${number}`;
      if (album) row = musicAlbumTracksV63(album).find(entry => entry.catalogTrackId === ref);
      else {
        // A restored playlist can be opened before the original album index is built.
        const raw = globalThis.BBX_MUSIC_CATALOG_V64?.albums.find(entry => String(entry[0]) === id);
        if (raw) row = musicAlbumTracksV63({id, title:raw[1], artist:raw[2], artwork:raw[3], rawTracks:raw[6], genre:musicAlbumSourceGenreV63(raw[5], raw[2])})
          .find(entry => entry.catalogTrackId === ref);
      }
    }
    return row ? {...item, ...row, ytId:alias, genre:item.genre || row.genre} : item;
  }

  const originalIdentity = queueIdentity;
  queueIdentity = item => {
    const track = catalogueItem(item);
    return track?._preparedRecording?.ref || (track?.album && title(track) ? identity(track) : originalIdentity(track));
  };
  const originalValidId = validPlaybackId;
  // Private cache keys and catalogue aliases are row IDs, never extractor source IDs.
  validPlaybackId = id => keyPattern.test(id || '') || isAlias(id) || originalValidId(id);
  const originalYoutubeQueue = youtubeQueue;
  youtubeQueue = (items, mode, genre) => originalYoutubeQueue(items.map(catalogueItem), mode, genre).map(item => {
    const alias = catalogueAlias(queueIdentity(item));
    return alias && !keyPattern.test(item.ytId || '') ? {...item, ytId:alias} : item;
  });
  const sanitizeCollection = sanitizeMusicCollection;
  sanitizeMusicCollection = saved => {
    const aliases = new Map();
    const pending = (saved.pending || []).map(row => {
      const alias = catalogueAlias(row?.key);
      if (!alias) return row;
      if (row.id) aliases.set(row.id, alias);
      return {...row, id:alias};
    });
    const ids = (saved.ids || []).map(id => aliases.get(id) || id);
    for (const row of pending) if (isAlias(row?.id) && !ids.includes(row.id)) ids.push(row.id);
    return sanitizeCollection({...saved, ids, pending});
  };
  const markPlaying = refreshMusicPlayingMarkers;
  refreshMusicPlayingMarkers = () => {
    markPlaying();
    if (!musicNowMeta) return;
    const key = musicSearchRecordKey(musicNowMeta);
    const audio = byId('bgAudio'), playing = audio && !audio.paused && !audio.ended;
    for (const card of document.querySelectorAll('.music-card-v47[data-musicidentity], .music-genre-card-v50[data-musicidentity], .music-result-v47[data-musicidentity]')) {
      if (card.dataset.musicidentity !== key) continue;
      card.classList.toggle('music-card-playing-v53', !!playing);
      card.classList.toggle('music-card-paused-v53', !playing);
    }
  };
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

  // The requested selection and the audio already on the player can differ during a switch.
  let session = null, audible = null, sequence = 0, requestId = 0, lastSnapshot = 0;
  let queueRows = new Map(), link = 'OFFLINE', mobileMaxSeconds = 360, queueFingerprint = '';
  let primed = false;
  function primeHelper() {
    if (primed) return;
    primed = true;
    fetch(engine + '/warm', {method:'POST', body:'{}', cache:'no-store'})
      .then(response => { if (!response.ok) primed = false; }, () => { primed = false; });
  }
  let cached = new Set(), albumJob = null;
  async function read(path = '', data, signal) {
    const id = ++requestId;
    const response = await fetch(engine + path, {signal, cache:'no-store',
      ...(data === undefined ? {} : {method:'POST', body:JSON.stringify(data)})});
    if (!response.ok) throw new Error('Audio engine unavailable');
    const result = await response.json();
    if (id > lastSnapshot && result.sequence >= sequence) {
      lastSnapshot = id;
      helperAvailable = result.available === true;
      if (mobileSetting) mobileSetting.hidden = !helperAvailable;
      const rows = Object.entries(result.tracks || {});
      cached = new Set(rows.filter(([, row]) => row.state === 'ready').map(([key]) => key));
      queueRows = new Map(rows.map(([, row]) => [recordingKey(row.recording), row]));
      link = result.link;
      mobileMaxSeconds = result.mobileMaxSeconds || 360;
      refreshSaved();
      refreshQueueStatus();
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
    row.added = musicDownloads.find(old => old.id === key)?.added || Date.now();
    musicDownloads = [row, ...musicDownloads.filter(old => old.id !== key)].slice(0,500);
    const alias = catalogueAlias(track.ref);
    for (const list of musicCollections) {
      if (!alias || !list.ids.includes(alias)) continue;
      list.ids = [...new Set(list.ids.map(id => id === alias ? key : id))];
      list.pending = list.pending.filter(entry => entry.key !== track.ref && entry.id !== alias);
      refreshAutomaticCollectionName(list);
    }
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

  function plan(selected, upcoming = [], retry = false, signal, playing = false) {
    return read('/plan', {sequence:++sequence, selected, upcoming, retry, playing, mobileLookahead}, signal);
  }
  function stillSelected(live) {
    if (session !== live) return false;
    const item = musicQueue.list[musicQueue.idx];
    if (item === live.item) return true;
    // The original queue can rebuild its row objects without changing the recording.
    const track = item && recording(item);
    if (!track || recordingKey(track) !== live.recordingKey) return false;
    live.item = item;
    return true;
  }
  function planQueue(live, retry = false) {
    // Use exactly these slots. An unresolved or duplicate slot cannot pull in a later song.
    const upcoming = musicQueue.list.slice(musicQueue.idx + 1, musicQueue.idx + 3).map(recording);
    const windowKey = JSON.stringify([live.started, mobileLookahead, upcoming]);
    if (!retry && live.windowKey === windowKey) return;
    live.windowKey = windowKey;
    return plan(live.track, upcoming, retry, live.controller.signal, live.started).catch(error => {
      if (live.windowKey === windowKey) live.windowKey = null;
      throw error;
    });
  }
  preloadNextTwo = () => { if (session?.track && stillSelected(session)) planQueue(session)?.catch(() => {}); };

  function setTransition(message = '') {
    if (!transition) return;
    transition.textContent = message;
    transition.hidden = !message;
  }

  function cancelWork(live) {
    if (!live) return;
    clearTimeout(live.deadline);
    live.controller.abort();
    clearMusicPlaybackFeedback('cancel', live.feedbackId);
  }

  function clearAudio(live) {
    musicAutoAdvanceBlocked = true;
    live.audio.pause();
    live.audio.removeAttribute('src');
    live.audio.load();
    if (live.blob) URL.revokeObjectURL(live.blob);
    closeMusicFrequency();
  }

  function stop() {
    const current = session || audible;
    if (!current) return;
    cancelWork(session);
    if (audible !== session) cancelWork(audible);
    clearAudio(audible || current);
    session = audible = null;
    current.bar.hidden = true;
    setTransition();
    plan(null).catch(() => {});
  }

  function retainQueue(live) {
    live.queue = {...musicQueue, list:musicQueue.list.slice(), original:musicQueue.original.slice(),
      history:musicQueue.history.slice(), failed:new Set(musicQueue.failed)};
  }

  function fail(live, message) {
    if (session !== live) return;
    if (audible && audible !== live && live.intent !== 'auto-ended') {
      // A failed manual selection leaves the song and queue the user was listening to intact.
      cancelWork(live);
      session = audible;
      Object.assign(musicQueue, audible.queue);
      audible.windowKey = null;
      setTransition();
      updateQCount();
    } else {
      stop();
      if (live.intent === 'auto-ended') {
        // Keep Retry (Play), Next and Queue available when an upcoming recording fails.
        byId('bgTitle').textContent = title(live.item);
        byId('bgArtist').textContent = artist(live.item);
        byId('bgArt').removeAttribute('src');
        byId('bgTime').textContent = '';
        byId('bgSeek').value = 0;
        live.bar.hidden = false;
        setTransition(message + ' Tap Play to retry or Next to skip.');
      }
    }
    if (live.intent !== 'cached-restore') toast(message);
  }
  const persist = persistMusicPlaybackSession;
  persistMusicPlaybackSession = () => { if (session?.started && stillSelected(session)) persist(); };

  function deadline(live, ms, message) {
    clearTimeout(live.deadline);
    live.deadline = setTimeout(() => fail(live, message), ms);
  }

  async function play(live) {
    try {
      if (!live.track) { fail(live, 'This catalogue entry does not identify a full recording.'); return; }
      // An automatic handoff may continue a running next job, but never repeats a known provider failure.
      let snapshot = await planQueue(live, live.intent !== 'auto-ended'), key;
      if (!stillSelected(live)) return;
      const budget = snapshot.selectionWaitMs;
      if (Number.isFinite(budget) && budget >= planTimeout && budget <= 300_000) {
        deadline(live, Math.max(1, budget - (performance.now() - live.tapped)),
          'The full recording was not prepared in time. Tap Play to retry.');
      }
      while (stillSelected(live)) {
        key = snapshot.selected;
        const row = snapshot.tracks?.[key];
        if (!snapshot.available) { fail(live, 'Music playback is not available in this build.'); return; }
        if (!keyPattern.test(key) || !row || recordingKey(row.recording) !== live.recordingKey) {
          fail(live, 'Could not prepare this recording. Tap Play to retry.'); return;
        }
        if (row.state === 'failed') { fail(live, row.message || 'Could not prepare this exact recording.'); return; }
        if (row.state === 'ready') break;
        snapshot = await read(`/await?key=${key}`, undefined, live.controller.signal);
      }
      if (!stillSelected(live)) return;
      deadline(live, localOpenTimeout, 'The saved audio could not start. Tap Play to retry.');
      const response = await fetch(audioUrl(key), {cache:'no-store', signal:live.controller.signal});
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('audio/mp4')) throw new Error('Missing cached audio');
      const blob = await response.blob();
      if (!stillSelected(live)) return;
      if (!blob.size || blob.size > 24 * 1024 * 1024) throw new Error('Invalid cached audio');
      // Use the original audio node and its normal seek, pause and end handlers. The WebView's
      // local-only configuration lets this one tap start playback after asynchronous preparation.
      if (audible) {
        cancelWork(audible);
        clearAudio(audible);
        audible = null;
      }
      live.audio.src = live.blob = URL.createObjectURL(blob);
      await live.audio.play();
      if (!stillSelected(live)) return;
      audible = live;
      live.started = true;
      retainQueue(live);
      setTransition();
      musicAutoAdvanceBlocked = false;
      preloadNextTwo(); // Only after the current track actually starts, on its existing queue plan.
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
      if (stillSelected(live)) fail(live, 'Could not open the audio. Tap Play to retry.');
    } finally {
      clearTimeout(live.deadline);
      // A queue rebuild can replace the selected object while the bridge reply is in flight.
      // Never leave that abandoned request's ring attached to the original button.
      if (!live.started && session === live) fail(live, 'The selected recording changed.');
    }
  }

  function attachSpinner(control) {
    const id = beginMusicPlaybackFeedback(control);
    // The non-button queue rows also use the original ring, but not its desktop stage caption.
    if (musicPlaybackActionElement) delete musicPlaybackActionElement.dataset.loadingLabel;
    return id;
  }
  async function restoreCached(item) {
    const revision = sequence;
    try {
      const snapshot = await read();
      const track = recording(item);
      // Don't block a real Play tap while checking a previous session's private cache.
      if (sequence === revision && !session && musicQueue.list[musicQueue.idx] === item && track
          && Object.values(snapshot.tracks).some(row => row.state === 'ready' && recordingKey(row.recording) === recordingKey(track))) {
        return queuePlayCurrent({intent:'cached-restore'});
      }
    } catch { /* The normal Play control stays ready. */ }
  }
  queuePlayCurrent = (options = {}) => {
    const item = musicQueue.list[musicQueue.idx];
    if (!item) return;
    if (options.intent === 'restore') return restoreCached(item);
    if (session && !session.started && stillSelected(session)) return session.playing;
    const player = ensureBgAudio();
    if (!player) return;
    if (!options.intent || options.intent === 'user' || options.intent === 'resume') primeMusicFrequencyAnalyser();
    const control = options.control;
    const fromQueue = control?.matches('.bg-queue-row-v49');
    const track = recording(item);
    if (session && session !== audible) {
      cancelWork(session);
      if (session.blob) clearAudio(session); // A stale local open must not revive its decoder.
    }
    // Selecting the song already playing (e.g. from another collection) only updates its queue.
    if (audible && track && recordingKey(track) === audible.recordingKey && !player.audio.ended) {
      session = audible;
      audible.item = item;
      retainQueue(audible);
      audible.windowKey = null;
      setTransition();
      preloadNextTwo();
      persistMusicPlaybackSession();
      if (player.audio.paused) return resumeExistingMusicAudio(player.audio, control);
      return;
    }
    const live = session = {...player, item, track, recordingKey:track ? recordingKey(track) : '',
      fromQueue, intent:options.intent, started:false, tapped:performance.now(), controller:new AbortController()};
    live.feedbackId = attachSpinner(fromQueue ? document.querySelector('#bgQueueList .playing') : control);
    setTransition(audible || !live.bar.hidden ? `Preparing ${title(item)}…` : '');
    deadline(live, planTimeout, 'The Music helper did not respond. Tap Play to retry.');
    live.playing = play(live);
    return live.playing;
  };
  function rowFor(item) {
    const track = recording(item);
    return track && queueRows.get(recordingKey(track));
  }
  queueItemStatus = (item, idx) => {
    const track = recording(item), row = rowFor(item);
    if (track && audible && recordingKey(track) === audible.recordingKey && !audible.audio.ended)
      return audible.audio.paused ? 'Paused' : 'Playing now';
    if (row?.state === 'ready') return 'Ready offline';
    if (row?.state === 'failed') return 'Unavailable · tap to retry';
    if (row?.state === 'working') return 'preparing';
    if (idx === musicQueue.idx) return session && !session.started ? 'Waiting for audio' : 'Selected';
    if (idx < musicQueue.idx || idx > musicQueue.idx + 2) return 'Queued';
    if (!track) return 'Exact recording unavailable';
    if (link === 'OFFLINE') return 'Waiting for a connection';
    if (link === 'RESTRICTED') return 'Data Saver · waiting for Wi-Fi';
    if (link === 'METERED' && (!mobileLookahead || track.seconds > mobileMaxSeconds)) return 'Waiting for Wi-Fi';
    return 'Up next';
  };

  const originalRenderQueue = renderMusicQueue;
  function drawQueue() {
    originalRenderQueue();
    if (session?.fromQueue && !session.started && stillSelected(session) && !musicPlaybackActionElement?.isConnected)
      session.feedbackId = attachSpinner(document.querySelector('#bgQueueList .playing'));
    if (queueNote && helperAvailable) {
      const end = musicQueue.idx + 1, next = musicQueue.list.slice(end, end + 2);
      const ready = next.filter(item => rowFor(item)?.state === 'ready').length;
      const policy = link === 'METERED' && !mobileLookahead ? 'Mobile preparation is off.'
        : link === 'RESTRICTED' ? 'Android Data Saver is on.'
        : link === 'OFFLINE' ? 'Offline. Ready tracks can still play.'
        : 'Only the next two tracks prepare while you listen.';
      queueNote.textContent = `${next.length ? `${ready} of ${next.length} up next ready. ` : ''}${policy}`;
    }
  }
  function refreshQueueStatus() {
    if (byId('bgQueuePanel')?.hidden) return;
    const fingerprint = JSON.stringify([link, mobileLookahead, musicQueue.list.map(queueItemStatus)]);
    if (fingerprint !== queueFingerprint) { queueFingerprint = fingerprint; drawQueue(); }
  }
  renderMusicQueue = function () {
    drawQueue();
    if (!musicQueue.list.length) { stop(); return; }
    if (session && stillSelected(session)) {
      if (session.started) retainQueue(session);
      preloadNextTwo();
    }
  };

  const originalOpenQueue = openMusicQueuePanel;
  openMusicQueuePanel = () => {
    // Queue all / Queue album replaces the list without a Play request. Keep the actual
    // current song at the front so its end advances into the newly queued recordings.
    if (session && !stillSelected(session)) {
      if (audible && musicQueue.list.length) {
        if (session !== audible) cancelWork(session);
        session = audible;
        audible.windowKey = null;
        setTransition();
        queueSet([audible.item, ...musicQueue.list], 0, musicQueue.genre);
        persistMusicPlaybackSession();
      } else stop();
    }
    originalOpenQueue();
    read().catch(() => {});
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
    byId('navMusic')?.addEventListener('click', primeHelper, {passive:true});
    // Local unpack and checksum only. Start it during idle time, not on the first Play tap.
    if (window.requestIdleCallback) requestIdleCallback(primeHelper, {timeout:2500});
    else setTimeout(primeHelper, 500);
    byId('bgSeek').setAttribute('aria-label', 'Track position');
    byId('bgClose').addEventListener('click', () => { stop(); setTransition(); });
    const bar = byId('bgAudioBar'), panel = byId('bgQueuePanel');
    transition = document.createElement('div');
    transition.className = 'bbx-music-transition';
    transition.setAttribute('role', 'status');
    transition.hidden = true;
    byId('bgAudioControls').append(transition);
    // Queue stays reachable with the mini-player collapsed and the panel doesn't sit behind it.
    document.querySelector('.bg-audio-main-v69').append(byId('bgQueueToggle'));
    if (window.ResizeObserver) new ResizeObserver(() => {
      panel.style.setProperty('--bbx-bar-height', `${bar.offsetHeight}px`);
    }).observe(bar);
    let readingQueue = false;
    setInterval(() => {
      if (panel.hidden || readingQueue) return;
      readingQueue = true;
      read().catch(() => {}).finally(() => { readingQueue = false; });
    }, 1500);
    queueNote = panel.querySelector('.bg-queue-note-v49');
    if (queueNote) {
      queueNote.textContent = 'Play or remove a track without changing the order of the others.';
      mobileSetting = document.createElement('label');
      mobileSetting.className = 'bbx-mobile-lookahead';
      mobileSetting.hidden = !helperAvailable;
      const toggle = document.createElement('input');
      toggle.type = 'checkbox';
      toggle.checked = mobileLookahead;
      toggle.id = 'bbxMusicMobileLookahead';
      toggle.addEventListener('change', () => {
        mobileLookahead = toggle.checked;
        try { localStorage.setItem(mobileLookaheadKey, mobileLookahead ? '1' : '0'); } catch { /* Session only. */ }
        preloadNextTwo();
        refreshQueueStatus();
      });
      mobileSetting.append(toggle, document.createTextNode('Prepare the next two on mobile data (songs up to 6 min)'));
      queueNote.after(mobileSetting);
    }
  }, {once:true});
})();
