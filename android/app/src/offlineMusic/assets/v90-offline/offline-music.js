/* v90's native Music discovery views, backed by the save-first Android library. */
(() => {
  'use strict';
  const bridge = window.OfflineMusic;
  if (!bridge) return;
  const id = value => document.getElementById(value);
  const doc = document.documentElement;
  const views = new Set(['music', 'musicGenre', 'musicDetail']);
  const norm = value => musicTextNorm(value || '');
  const clean = (value, max = 240) => String(value || '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max);
  const count = n => `${n} ${n === 1 ? 'song' : 'songs'}`;
  const libraryPageSize = 40;
  let page = 'collection', selecting = false, visibleLimit = libraryPageSize;
  let data = {tracks: [], paused: false}, byRef = new Map(), byKey = new Map(), last = '', lastPlayer = '';
  const chosen = new Map();

  function node(tag, classes, label) {
    const n = document.createElement(tag);
    if (classes) n.className = classes;
    if (label) n.textContent = label;
    return n;
  }
  function button(text, action, classes = 'om-link', label = '') {
    const b = node('button', classes, text);
    b.type = 'button'; b.onclick = action;
    if (label) b.setAttribute('aria-label', label);
    return b;
  }

  function recording(item) {
    if (!item) return null;
    const title = clean(item.track || item.title), artist = clean(item.artist || item.channel), album = clean(item.album);
    if (!title || !artist) return null;
    const duration = Math.round(Number(item.duration));
    const seconds = duration > 0 && duration <= 1800 ? duration : 0;
    const albumId = clean(item.albumId, 100);
    const full = albumId ? musicAlbumCatalogV63?.byId.get(albumId) : null;
    const ref = item.catalogTrackId ? `album:${item.catalogTrackId}` : item.id && album ? `pick:${item.id}`
      : JSON.stringify([title, artist, album, seconds, item.explicit === true]);
    return {ref:clean(ref, 630), title, artist, album, albumId, seconds, albumArtist:clean(full?.artist || artist),
      art:clean(item.artwork || item.thumbnail, 400), disc:item.discNumber || 1, number:item.trackNumber || 0,
      total:full?.rawTracks.length || item._offlineAlbumSize || (norm(album) === norm(title) ? 1 : 0)};
  }

  const saved = () => data.tracks.filter(track => track.state === 'saved');
  const isSaved = track => track?.state === 'saved';
  const batchOf = (group, key) => {
    if (!group.has(key)) group.set(key, []);
    return group.get(key);
  };
  const play = (action, value = '') => bridge.playback(action, String(value));
  function playList(tracks) {
    const keys = tracks.filter(isSaved).map(track => track.key);
    if (keys.length) play('playlist', JSON.stringify(keys));
    else toast('Nothing saved in this selection yet.');
  }

  function updateLibrary(raw) {
    const response = JSON.parse(raw);
    if (response.error) { toast(response.error); return false; }
    if (!Array.isArray(response.tracks)) return false;
    data = response; last = raw;
    byRef = new Map(data.tracks.map(track => [track.ref, track]));
    byKey = new Map(data.tracks.map(track => [track.key, track]));
    for (const ref of chosen.keys()) if (byRef.has(ref)) chosen.delete(ref);
    render();
    return true;
  }
  function command(action, value = '') {
    try { return updateLibrary(bridge.queue(action, value)); }
    catch { toast('Could not update your download list.'); return false; }
  }
  function enqueue(tracks, label = '') {
    const additions = [...new Map(tracks.filter(track => track && !byRef.has(track.ref)).map(track => [track.ref, track])).values()];
    if (!additions.length) { toast('Already saved or listed in Downloads.'); return false; }
    if (data.available === false) { toast('The music helper is not available on this device.'); return false; }
    if (!command('enqueue', JSON.stringify({tracks:additions,label}))) return false;
    toast(`${count(additions.length)} added to Downloads`);
    return true;
  }
  function addTrack(track) {
    if (!track) return toast('This entry does not identify an exact recording.');
    if (!selecting) return enqueue([track]);
    if (chosen.has(track.ref)) chosen.delete(track.ref);
    else if (!byRef.has(track.ref)) {
      if (chosen.size >= 120) return toast('Select up to 120 songs at a time.');
      chosen.set(track.ref, track);
    }
    syncFind();
  }

  // Use the existing v90 renderers. Change where their actions go, not how they find music.
  showMusicSourcePopup = () => {};
  refreshMusicYoutubeState = async () => false;
  ensureMusicPlaybackReady = async () => false;
  warmMusicTrack = async () => '';
  warmMusicLiveSource = async () => null;
  proactivelyWarmMusicItems = () => {};
  proactivelyWarmMusicLanding = async () => [];
  ensureQueueLookahead = () => 0;
  queueMusicAutoDownload = () => {};
  restoreMusicPlaybackSession = () => {};
  persistMusicPlaybackSession = () => {};
  loadMusicDownloads = () => {};
  renderMusicDownloads = () => {};
  refreshMusicPlayingMarkers = () => {};
  primeMusicFrequencyAnalyser = () => {};
  getCachedMusicSearchResults = async text => window.bbxMusicSearchV76?.search(text, {limit:30})?.songs.map(row => row.track) || [];
  renderMusicProviderFallbackV63 = async () => {
    id('musicMsg').textContent = 'No matching recording in the v90 catalogue.';
    id('musicTorrGrid').replaceChildren(node('p', 'sub', 'Try a song, artist or release. No other recording will be downloaded.'));
    return [];
  };
  loadMusicFeatured = ((original) => (...args) => {
    original(...args);
    if (id('musicSearchPane').hidden) id('musicMsg').textContent = '';
  })(loadMusicFeatured);
  playMusicList = (items, position) => {
    const target = recording(items?.[Math.max(0, position || 0)]);
    const row = target && byRef.get(target.ref);
    if (isSaved(row)) play('play', row.key);
    else toast('Not saved yet. Use the download button, then play from Collection.');
  };
  appendOneMusicTrackToQueue = item => addTrack(recording(item));
  const addControl = '.music-card-queue-v47,.music-card-add-v61,[data-track-add-v63],[data-detailqueue],[data-instant-action="add"]';
  function decorate(root, track) {
    if (!root || !track || !root.querySelector(addControl)) return;
    root._omTrack = track;
    root.dataset.omTrack = '';
    updateCard(root);
  }
  function updateCard(root) {
    const track = root._omTrack;
    if (!track) return;
    const add = root.querySelector(addControl);
    const playback = root.querySelector('[data-music-play-control]');
    const row = byRef.get(track.ref), isChosen = chosen.has(track.ref);
    const label = selecting ? `Select ${track.title}` : `Queue ${track.title}`;
    if (playback) {
      playback.hidden = !isSaved(row);
      playback.setAttribute('aria-label', `Play ${track.title}`);
    }
    if (!add) return;
    root.dataset.omSelected = String(isChosen);
    root.dataset.omSaved = String(isSaved(row));
    add.dataset.omAdd = '';
    add.disabled = !!row;
    add.setAttribute('aria-pressed', String(isChosen));
    add.textContent = row?.state === 'saved' ? '✓' : row ? '·' : isChosen ? '✓' : selecting ? '+' : '↓';
    add.title = row ? row.state === 'saved' ? 'Saved on this device' : 'See Downloads' : label;
    add.setAttribute('aria-label', row ? `${track.title}: ${add.title}` : label);
  }
  wireMusicWarmup = (root, item) => decorate(root, recording(item));
  renderMusicTrackRowsV63 = ((original) => (target, tracks, label) => {
    original(target, tracks, label);
    [...(target?.children || [])].forEach((root, index) => decorate(root, recording(tracks[index])));
  })(renderMusicTrackRowsV63);
  const albumCache = new WeakMap();
  function albumRows(album) {
    if (!album?.rawTracks) return [];
    if (!albumCache.has(album)) albumCache.set(album, musicAlbumTracksV63(album)
      .map(track => recording({...track, _offlineAlbumSize:album.rawTracks.length})).filter(Boolean));
    return albumCache.get(album);
  }
  function albumStatus(album) {
    const tracks = albumRows(album), listed = tracks.map(track => byRef.get(track.ref));
    return {total:tracks.length, saved:listed.filter(isSaved).length, missing:listed.filter(track => !track).length};
  }
  function albumLabel({total, saved, missing}) {
    if (missing) return missing === total ? 'Queue album' : 'Queue missing';
    return saved === total ? 'Album saved' : 'Listed in Downloads';
  }
  musicAlbumDownloadLabelV63 = album => albumLabel(albumStatus(album));
  function albumButton(control, album, instant = false) {
    const summary = albumStatus(album), label = albumLabel(summary);
    const name = `${label}: ${album.title}`;
    control.disabled = summary.missing === 0;
    control.textContent = instant ? summary.missing ? '↓' : summary.saved === summary.total ? '✓' : '·' : label;
    control.title = control.disabled ? name : `${name} · ${count(summary.missing)}`;
    control.setAttribute('aria-label', name);
  }
  refreshMusicAlbumJobUiV63 = album => {
    for (const control of document.querySelectorAll('[data-album-download-v63]')) {
      if (control.dataset.albumDownloadV63 === album.id) albumButton(control, album);
    }
  };
  downloadMusicAlbumV63 = async album => enqueue(albumRows(album), album.title);
  renderMusicAlbumDetailV63 = ((original) => (album, catalog) => {
    original(album, catalog);
    const root = id('musicTorrGrid').querySelector('.music-album-detail-v63');
    if (root) {
      root._omAlbum = album;
      const control = root.querySelector('[data-album-play-v63]');
      control.textContent = '▶ Play saved';
      control.onclick = () => playList(albumRows(album).map(track => byRef.get(track.ref)));
    }
    syncFind();
  })(renderMusicAlbumDetailV63);
  openMusicGenre = ((original) => key => {
    original(key);
    const genre = musicGenreRecord(key), root = id('musicGenrePage');
    if (!genre || !root) return;
    root.querySelector('[data-genre-status]').textContent = `${count(genre.results.length)} to explore`;
    root.querySelector('[data-genre-queue]').textContent = '↓ Queue all';
    root.querySelector('[data-genre-queue]').onclick = () => enqueue(genre.results.map(recording), genre.title);
    syncFind();
  })(openMusicGenre);
  // v76's instant suggestions keep their own click dispatcher. Route the plus action here
  // once, with an ordered album as one batch instead of N independent requests.
  document.addEventListener('click', event => {
    const add = event.target.closest('[data-instant-action="add"]');
    const row = add?.closest('[data-instant-row]');
    if (!row?._hit) return;
    event.preventDefault(); event.stopImmediatePropagation();
    if (row.dataset.kind === 'album') downloadMusicAlbumV63(row._hit).catch(() => toast('Could not queue this album.'));
    if (row.dataset.kind === 'song') addTrack(recording(row._hit.track));
  }, true);

  const toolbar = node('div', 'om-select-tools');
  toolbar.id = 'om-select-tools';
  const chooseButton = button('Select songs', () => {
    selecting = !selecting;
    if (!selecting) chosen.clear();
    syncFind();
  }, 'secondary');
  const selectedCount = node('span', 'sub'); selectedCount.setAttribute('aria-live', 'polite');
  const queueChosen = button('Queue selection', () => {
    if (enqueue([...chosen.values()], 'Selected songs')) { selecting = false; chosen.clear(); show('downloads'); }
  }, 'primary');
  toolbar.append(chooseButton, selectedCount, queueChosen);
  id('musicGenreChips').before(toolbar);
  const heroCopy = document.querySelector('#viewMusic .music-hero-content > p');
  if (heroCopy) heroCopy.textContent = 'Search artists, songs and albums. Queue the recordings you want, then play them from Collection.';
  const genreTools = node('div', 'om-select-tools');
  const genreChoose = button('Select songs', () => chooseButton.click(), 'secondary');
  const genreQueue = button('Queue selection', () => queueChosen.click(), 'primary');
  genreTools.append(genreChoose, genreQueue);
  id('musicGenrePage').before(genreTools);
  function syncFind() {
    for (const b of [chooseButton, genreChoose]) b.textContent = selecting ? 'Cancel selection' : 'Select songs';
    selectedCount.textContent = selecting ? `${chosen.size} selected` : '';
    for (const b of [queueChosen, genreQueue]) {
      b.hidden = !selecting; b.disabled = !chosen.size; b.textContent = chosen.size ? `Queue ${count(chosen.size)}` : 'Queue selection';
    }
    document.querySelectorAll('[data-om-track]').forEach(updateCard);
    for (const root of document.querySelectorAll('#musicInstantV76 [data-instant-row]')) {
      if (root.dataset.kind === 'song') decorate(root, recording(root._hit?.track));
      else if (root.dataset.kind === 'album' && root._hit) albumButton(root.querySelector('[data-instant-action="add"]'), root._hit, true);
    }
    for (const control of id('musicTorrGrid').querySelectorAll('[data-album-download-v63]')) {
      const album = musicAlbumCatalogV63?.byId.get(control.dataset.albumDownloadV63);
      if (album) albumButton(control, album);
    }
    const detail = id('musicTorrGrid').querySelector('.music-album-detail-v63');
    if (detail?._omAlbum) {
      const summary = albumStatus(detail._omAlbum);
      detail.querySelector('[data-album-play-v63]').disabled = summary.saved === 0;
      detail.querySelector('[data-album-job-v63]').textContent = `${summary.saved} of ${summary.total} saved`;
    }
  }
  // Instant search rebuilds its rows as the user types, independent of the other v90 renderers.
  const instantMount = new MutationObserver(() => {
    const instant = id('musicInstantV76');
    if (instant) {
      instantMount.disconnect();
      const update = () => {
        if (state.view === 'music' && page === 'find') syncFind();
        else if (!instant.hidden) window.bbxMusicSearchV76?._close();
      };
      new MutationObserver(records => {
        if (records.some(record => record.target === instant)) update();
      }).observe(instant, {childList:true});
      update();
    }
  });
  instantMount.observe(document.body, {childList:true});

  const artwork = track => {
    if (norm(track?.title) === 'kwaku the traveller' && norm(track?.artist) === 'black sherif') return '/offline-music/kwaku.svg';
    if (norm(track?.title) === 'espresso' && norm(track?.artist) === 'sabrina carpenter') return '/offline-music/espresso.svg';
    return safeArtwork(track?.art || '') || '/offline-music/mix.svg';
  };
  function setImage(img, src) {
    if (img.dataset.omArt === src) return;
    img.dataset.omArt = src;
    img.onerror = () => { img.onerror = null; img.src = '/offline-music/mix.svg'; };
    img.src = src;
  }
  function card(track, subtitle, controls, feature = false) {
    const row = node('article', feature ? 'om-mix' : 'om-row');
    const img = node('img', 'om-cover'); img.alt = ''; img.loading = 'lazy';
    setImage(img, artwork(track));
    const copy = node('div', 'om-copy'); copy.append(node('h3', '', track.title), node('p', feature ? '' : 'om-status', subtitle));
    row.append(img, copy);
    if (controls.length) { const buttons = node('div', 'om-controls'); buttons.append(...controls); row.append(buttons); }
    return row;
  }
  function empty(heading, description, link = false) {
    const row = node('div', 'om-empty'); row.append(node('strong', '', heading), node('p', '', description));
    if (link) row.append(button('Find songs', () => show('find')));
    return row;
  }
  function renderCollection() {
    const songs = saved(), releases = new Map();
    id('om-collection-count').textContent = songs.length ? `${count(songs.length)} available offline` : 'Queue what you want. Play what’s saved.';
    id('om-new-playlist').disabled = !songs.length;
    id('om-saved-count').textContent = songs.length || '';
    const rotation = button('▶', () => playList(songs), 'om-round om-primary', 'Play saved rotation'); rotation.disabled = !songs.length;
    const rotationCard = card({title:'Saved rotation'}, songs.length ? `${count(songs.length)} saved` : 'No saved tracks yet', [rotation], true);
    id('om-playlists').replaceChildren(rotationCard, ...(data.playlists || []).map(list => {
      const rows = [...new Set(list.keys)].map(key => byKey.get(key)).filter(isSaved);
      const playButton = button('▶', () => playList(rows), 'om-round', `Play ${list.name}`); playButton.disabled = !rows.length;
      return card({title:list.name}, `${count(rows.length)} saved`, [playButton, button('✕', () => {
        confirmRemoval(`Remove “${list.name}”?`, 'Saved songs stay on this device.', () => command('playlist-remove', list.id));
      }, 'om-round', `Remove playlist ${list.name}`)], true);
    }));
    for (const track of songs) if (track.album) batchOf(releases, track.albumId || JSON.stringify([track.albumArtist, track.album])).push(track);
    id('om-albums').hidden = releases.size === 0;
    id('om-releases').replaceChildren(...[...releases.values()].slice(0, visibleLimit).map(group => {
      const sorted = group.sort((a,b) => a.disc - b.disc || a.number - b.number);
      const track = sorted[0], knownTotal = track.total >= sorted.length ? `${sorted.length} of ${track.total} saved` : `${sorted.length} saved`;
      const controls = [button('Play saved', () => playList(sorted), 'om-link', `Play saved from ${track.album}`)];
      const album = musicAlbumCatalogV63?.byId.get(track.albumId);
      if (album && albumStatus(album).missing) controls.push(button('Queue missing', () => downloadMusicAlbumV63(album), 'om-link', `Queue missing: ${track.album}`));
      const release = card({...track, title:track.album}, `${track.albumArtist} · ${knownTotal}`, controls, true);
      release.classList.add('om-release');
      return release;
    }));
    id('om-saved').replaceChildren(...(songs.length ? songs.slice(0, visibleLimit).map(track => card(track,
      track.artist, [button('▶', () => play('play', track.key), 'om-round', `Play ${track.title}`), button('✕', () => {
        confirmRemoval(`Remove “${track.title}”?`, 'This removes the downloaded file from this device.', () => {
          if (player.key === track.key) play('stop');
          command('delete', track.key);
        });
      }, 'om-round', `Remove saved ${track.title}`)])) : [empty('Nothing saved yet', 'Find a song, then queue it in Downloads.', true)]));
    if (songs.length > visibleLimit) id('om-saved').append(button('Show more', () => { visibleLimit += libraryPageSize; renderCollection(); }, 'om-link om-more'));
  }

  const jobStatus = {working:'Getting recording…', offline:'Waiting for connection', wifi:'Waiting for Wi-Fi',
    restricted:'Waiting for unmetered network · Data Saver is on', paused:'Paused', queued:'Queued', cooldown:'Waiting for the provider cooldown'};
  const failures = { exact:'No exact recording verified', signin:'Sign-in required', refused:'Provider refused this recording',
    network:'Connection interrupted', limited:'Provider rate limited this request', storage:'Could not save · check storage',
    incomplete:'A complete, playable file was not verified', missing:'Saved file missing', helper:'Download didn’t finish' };
  function renderDownloads() {
    const pending = new Map();
    const waiting = data.tracks.filter(track => !['saved','failed'].includes(track.state));
    for (const track of waiting) batchOf(pending, track.batch).push(track);
    id('om-pause').hidden = !waiting.length && !data.paused;
    id('om-pause').textContent = data.paused ? 'Resume queue' : waiting.some(row => row.state === 'working') ? 'Pause after current' : 'Pause queue';
    id('om-pending').replaceChildren(...(pending.size ? [...pending.values()].map(rows => {
      const block = node('div', 'om-batch');
      if (rows[0].label) {
        const head = node('header'); head.append(node('strong', '', rows[0].label), node('small', '', count(rows.length))); block.append(head);
      }
      for (const track of rows) block.append(card(track, jobStatus[track.state] || 'Queued', track.state === 'working' ? [] : [
        button('✕', () => command('remove', track.key), 'om-round', `Remove ${track.title} from queue`)]));
      if (rows.some(row => row.state === 'wifi')) {
        const footer = node('footer'); footer.append(button('Use mobile data for this group', () => command('mobile', rows[0].batch))); block.append(footer);
      }
      return block;
    }) : [empty('Queue is clear', 'Add songs from Find when you’re ready.') ]));
    const issues = data.tracks.filter(track => track.state === 'failed');
    id('om-failed').hidden = !issues.length;
    id('om-issues').replaceChildren(...issues.map(track => card(track, failures[track.reason] || failures.helper, [
      ...(track.retryable ? [button('Retry', () => command('retry', track.key), 'om-link', `Retry ${track.title}`)] : []),
      button('✕', () => command('remove', track.key), 'om-round', `Dismiss ${track.title} download`),
    ])));
    const songs = saved();
    id('om-finished').replaceChildren(...(songs.length ? songs.slice(0, visibleLimit).map(track => card(track, 'Saved on this device', [node('span', 'om-round', '✓')]))
      : [empty('No finished downloads', 'Verified files appear here and in Collection.')]));
    if (songs.length > visibleLimit) id('om-finished').append(button('Show more', () => { visibleLimit += libraryPageSize; renderDownloads(); }, 'om-link om-more'));
  }

  function render() {
    const pending = data.tracks.filter(track => track.state !== 'saved').length;
    id('om-badge').hidden = !pending;
    id('om-badge').textContent = pending > 99 ? '99+' : pending;
    if (page === 'collection' && !id('offline-collection').hidden) renderCollection();
    if (page === 'downloads' && !id('offline-downloads').hidden) renderDownloads();
    if (page === 'find') syncFind();
  }
  let player = {}, dragging = false, seekUntil = 0;
  const time = value => `${Math.floor(value / 60000)}:${String(Math.floor(value / 1000) % 60).padStart(2, '0')}`;
  const text = (name, value) => { const field = id(name); if (field.textContent !== value) field.textContent = value; };
  function renderPlayer() {
    const track = byKey.get(player.key);
    id('om-mini').hidden = !track;
    id('om-chrome').hidden = !track && id('om-tabs').hidden;
    doc.classList.toggle('bbx-offline-playing', !!track);
    if (!track) {
      if (id('om-player').open) id('om-player').close();
      return;
    }
    text('om-mini-title', track.title); text('om-now-title', track.title); text('om-now-artist', track.artist);
    text('om-mini-state', player.problem ? 'Couldn’t play' : player.playing ? 'Playing offline' : player.ready ? 'Saved on this device' : 'Opening saved file…');
    id('om-play-error').hidden = !player.problem; text('om-play-error', player.problem || '');
    for (const name of ['om-player-art','om-mini-art']) setImage(id(name), artwork(track));
    for (const name of ['om-mini-toggle','om-play-toggle']) {
      text(name, player.playing ? '❚❚' : '▶'); id(name).setAttribute('aria-label', player.playing ? 'Pause' : 'Play');
      id(name).disabled = !player.ready && !player.problem;
    }
    id('om-previous').disabled = !player.previous; id('om-next').disabled = !player.next;
    const seek = id('om-seek'); seek.disabled = !player.ready; seek.max = Math.max(1, player.duration - 1);
    if (!dragging && Date.now() > seekUntil) seek.value = player.position;
    text('om-clock', player.ready ? `${time(dragging ? +seek.value : player.position)} / ${time(player.duration)}` : 'Opening saved file…');
  }
  function poll() {
    if (document.hidden) return;
    try {
      if (views.has(state.view) || player.key) {
        const snapshot = bridge.library();
        if (snapshot !== last) updateLibrary(snapshot);
        const next = bridge.player();
        if (next !== lastPlayer || seekUntil && Date.now() > seekUntil) {
          lastPlayer = next; if (Date.now() > seekUntil) seekUntil = 0;
          player = JSON.parse(next); renderPlayer();
        }
      }
    } catch { /* The shell may have been closed or expired. */ }
  }

  function syncView() {
    const music = views.has(state.view);
    if (music && state.view !== 'music') page = 'find';
    const find = state.view === 'music' && page === 'find';
    id('offline-collection').hidden = !music || page !== 'collection';
    id('offline-downloads').hidden = !music || page !== 'downloads';
    id('viewMusic').hidden = !find;
    id('om-tabs').hidden = !music;
    if (!find) window.bbxMusicSearchV76?._close();
    if (!music && id('om-playlist-dialog').open) id('om-playlist-dialog').close();
    doc.classList.toggle('bbx-offline-active', music);
    doc.classList.toggle('bbx-offline-find', find);
    if (music) {
      if (!musicAlbumCatalogV63 && !musicAlbumCatalogPromiseV63) loadMusicAlbumCatalogV63().then(render).catch(() => {});
      const musicLink = id('navMusic'), nav = musicLink.parentElement;
      for (const link of nav.querySelectorAll('.navlink')) link.classList.toggle('active', link === musicLink);
      const right = musicLink.getBoundingClientRect().right - nav.getBoundingClientRect().right;
      if (right > 0) nav.scrollLeft += right + 6;
    }
    for (const tab of id('om-tabs').querySelectorAll('button')) {
      if (tab.dataset.omPage === page) tab.setAttribute('aria-current','page'); else tab.removeAttribute('aria-current');
    }
    render(); renderPlayer(); poll();
  }
  const originalView = showView;
  showView = function (next) {
    if (next === 'music' && !views.has(state.view)) page = 'collection';
    const result = originalView.call(this, next);
    syncView(); return result;
  };
  function show(next) {
    page = next; visibleLimit = libraryPageSize;
    if (state.view !== 'music') showView('music'); else syncView();
    if (next === 'find' && id('musicSearchPane').hidden) loadMusicFeatured();
    window.scrollTo(0, 0);
  }
  const originalBack = window.bbxAndroidBack;
  window.bbxAndroidBack = () => {
    for (const dialog of [id('om-confirm'), id('om-player'), id('om-playlist-dialog')]) if (dialog.open) { dialog.close(); return true; }
    if (!id('settingsWrap').hidden) return originalBack?.() || false;
    if (id('musicInstantV76') && !id('musicInstantV76').hidden) { window.bbxMusicSearchV76?._close(); return true; }
    if (state.view === 'musicGenre' || state.view === 'musicDetail') { show('find'); return true; }
    if (state.view === 'music') {
      if (page === 'find' && !id('musicSearchPane').hidden && id('musicTorrGrid').querySelector('.music-album-back-v63')) {
        id('musicTorrGrid').querySelector('.music-album-back-v63').click(); return true;
      }
      if (page !== 'collection') { show('collection'); return true; }
    }
    return originalBack?.() || false;
  };

  function confirmRemoval(message, description, remove) {
    const dialog = id('om-confirm');
    text('om-confirm-message', message); text('om-confirm-description', description);
    id('om-confirm-remove').onclick = () => { dialog.close(); remove(); };
    dialog.showModal();
  }
  id('om-confirm-cancel').onclick = () => id('om-confirm').close();
  doc.classList.add('bbx-offline-shell');
  id('navMusic').addEventListener('click', () => { page = 'collection'; }, true);
  for (const tab of id('om-tabs').querySelectorAll('button')) tab.onclick = () => show(tab.dataset.omPage);
  id('om-add-songs').onclick = () => show('find'); id('om-saved-library').onclick = () => show('collection');
  id('om-pause').onclick = () => command(data.paused ? 'resume' : 'pause');
  id('om-now').onclick = () => id('om-player').showModal(); id('om-player-close').onclick = () => id('om-player').close();
  id('om-mini-toggle').onclick = id('om-play-toggle').onclick = () => play('toggle');
  id('om-previous').onclick = () => play('previous'); id('om-next').onclick = () => play('next');
  id('om-stop').onclick = () => { play('stop'); id('om-player').close(); };
  id('om-seek').oninput = () => { dragging = true; renderPlayer(); };
  id('om-seek').onchange = event => { dragging = false; seekUntil = Date.now() + 600; play('seek', event.target.value); };
  id('om-seek').onpointercancel = () => { dragging = false; };
  id('om-new-playlist').onclick = () => {
    id('om-playlist-name').value = '';
    id('om-playlist-tracks').replaceChildren(...saved().map(track => {
      const check = node('input'); check.type = 'checkbox'; check.value = track.key;
      const label = node('label'); label.append(check, document.createTextNode(`${track.title} · ${track.artist}`)); return label;
    }));
    id('om-playlist-dialog').showModal();
  };
  id('om-playlist-close').onclick = () => id('om-playlist-dialog').close();
  id('om-playlist-save').onclick = () => {
    const name = id('om-playlist-name').value.trim();
    const keys = [...id('om-playlist-tracks').querySelectorAll(':checked')].map(box => box.value);
    if (command('playlist-add', JSON.stringify({name,keys}))) id('om-playlist-dialog').close();
  };
  const resume = window.bbxAndroidResume;
  window.bbxAndroidResume = () => { resume?.(); poll(); };
  syncView();
  setInterval(poll, 400);
})();
