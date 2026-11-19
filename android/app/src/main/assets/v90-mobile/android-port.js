/* Mobile adapters for the original BlackBox v90 UI. Original assets live in assets/v90. */
(() => {
  'use strict';
  if (!window.bbxAndroidOpen) return;
  document.documentElement.classList.add('bbx-android');
  const node = id => document.getElementById(id);
  const button = (label, fn, className = 'secondary') => {
    const b = el('button', className, label); b.type = 'button'; b.onclick = fn; return b;
  };
  async function read(path, data) {
    const r = await fetch(path, data === undefined ? {} : {method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(data)});
    const payload = await r.json();
    if (!r.ok) throw new Error(payload.message || payload.error || `Android engine HTTP ${r.status}`);
    return payload;
  }
  const player = () => node('playerWrap');
  const originalNoKey = renderNoKey;
  function fillKeylessHome() {
    const quick = node('heroTorrent');
    if (quick) quick.onclick = () => bbxAndroidOpen('torrent');
    const hero = node('hero');
    hero.classList.add('android-keyless');
    const description = hero.querySelector('.hero-desc');
    if (!description || !quick) return;
    description.textContent = 'Use your TMDB key for the full v90 movie and series catalogue. You can also look up torrent sources by title or play the open films below without a key.';
    read('/api/mobile/open-films').then(({results}) => {
      if (state.tmdb || !hero.classList.contains('android-keyless')) return;
      const rows = node('rows');
      rows.replaceChildren(makeRow('Open films · full-length sources', results, null));
    }).catch(() => {});
  }
  renderNoKey = function () { originalNoKey(); fillKeylessHome(); };
  if (!state.tmdb && node('heroSettings')) fillKeylessHome();
  const originalLoadHome = loadHome;
  loadHome = function (...args) {
    node('hero')?.classList.remove('android-keyless');
    return originalLoadHome.apply(this, args);
  };

  // Keep the original search cards, title details and source list; add the index search option
  // when there is no TMDB credential. An index result is never autoplayed.
  const originalSourceCard = sourceCardV70;
  sourceCardV70 = function (source) {
    const card = originalSourceCard(source);
    const play = card.querySelector('.source-play-chip-v74');
    play?.classList.add('android-source-play');
    const title = el('span', 'android-release-title', source.title || 'Selected release');
    card.prepend(title);
    return card;
  };
  const originalSearch = doSearch;
  doSearch = function () {
    originalSearch();
    const query = node('searchInput').value.trim();
    let station = node('androidSourceSearch');
    if (!station) {
      station = el('div', 'android-source-search'); station.id = 'androidSourceSearch';
      node('browseGrid').before(station);
    }
    station.replaceChildren();
    if (!query || state.tmdb || state.view !== 'browse') { station.hidden = true; return; }
    station.hidden = false;
    const search = button('Search torrent sources', async () => {
      search.disabled = true;
      const status = el('p', 'sub', 'Searching Knaben and Torrents-CSV…');
      station.replaceChildren(search, status);
      const q = new URLSearchParams({title: query.slice(0, 130), type: 'movie'});
      const responses = await Promise.allSettled(['find','deep'].map(mode => read(`/api/sources/${mode}?${q}`)));
      if (!status.isConnected || state.browse?.query !== query) return;
      search.disabled = false;
      const sources = new Map(); const notes = [];
      for (const reply of responses) {
        if (reply.status !== 'fulfilled') continue;
        notes.push(reply.value.note);
        for (const s of reply.value.sources || []) if (!sources.has(s.magnet)) sources.set(s.magnet, s);
      }
      status.textContent = sources.size ? `${sources.size} source candidates. Choose a release to verify its exact files.`
        : notes.filter(Boolean).join(' ') || 'The source indexes did not answer. Try opening an authorized magnet instead.';
      for (const source of sources.values()) {
        const card = sourceCardV70(source);
        station.append(card);
      }
    });
    station.append(search, el('p', 'sub', 'Searches public indexes with this title only. Select media that you have permission to download.'));
  };
  node('searchBtn').onclick = doSearch;
  document.addEventListener('bbx-view', () => {
    const panel = node('androidSourceSearch');
    if (panel && (state.view !== 'browse' || state.browse?.mode !== 'search')) panel.hidden = true;
  });

  // Bind every chooser to the specific manifest job from the chosen source, not a recent torrent.
  openTorrent = async function (source, title, options = {}) {
    const link = String(source || '').trim();
    const name = title || titleOf(state.movie || {}) || 'Selected release';
    if (!/^magnet:\?/i.test(link) && !/^https:\/\//i.test(link)) {
      toast('Paste a magnet or HTTPS link, or pick a local .torrent file.');
      return;
    }
    if (state.resolveId) return toast('Cancel the current source check first.');
    try {
      if (/^https:\/\//i.test(link) && !/\.torrent$/i.test(new URL(link).pathname)) {
        const {id} = await read('/api/mobile/direct', {url: link, title: title || undefined});
        node('settingsWrap').hidden = true; bbxAndroidOpen('play', id);
        return;
      }
    } catch (error) { toast(error.message); return; }
    let job, cancelled = false;
    state.resolveId = 'starting';
    state.findingCancel = async () => {
      cancelled = true;
      if (job) await read(`/api/content-job/${job}/cancel`, {}).catch(() => {});
    };
    showFinding(`Checking complete manifest for ${name.slice(0, 72)}…`);
    try {
      ({job} = await read('/api/content-resolve', {uri: link}));
      if (cancelled) { await state.findingCancel(); return; }
      state.resolveId = job;
      const until = Date.now() + 130000;
      while (!cancelled && Date.now() < until) {
        const reply = await read(`/api/content-job/${job}`);
        if (cancelled || reply.state === 'cancelled') return;
        if (reply.state === 'error') throw new Error(reply.error);
        if (reply.state === 'done') {
          const manifest = reply.result;
          if (manifest?.job !== job || manifest.id !== manifest.info_hash || !manifest.files?.length)
            throw new Error('The exact source did not return a verified file list.');
          node('settingsWrap').hidden = true;
          hideFinding();
          showPlayer(manifest, title || manifest.name, options);
          return;
        }
        findingText(reply.msg || 'Waiting for the selected source…');
        await new Promise(resolve => setTimeout(resolve, 600));
      }
      if (!cancelled) {
        await state.findingCancel();
        toast('No peers answered for this manifest in time. Try another authorized release.');
      }
    } catch (error) { if (!cancelled) toast(`Source check stopped: ${error.message}`); }
    finally { state.resolveId = null; state.findingCancel = null; hideFinding(); }
  };

  // Let the original v90 chooser render file rows. The Windows byte reader is replaced with
  // the actual Android torrent job and MediaDataSource for exactly the selected info hash/index.
  selectAndPlayFile = async function (torrent, index, title, control, _unsafe, downloadOnly) {
    const choice = torrent.files?.find(file => file.idx === index);
    if (!choice) return toast('This exact file is not in the verified descriptor.');
    if (control?.disabled) return;
    const message = node('playerMsg');
    if (control) control.disabled = true;
    message.classList.add('on'); message.textContent = `Preparing ${choice.name}…`;
    try {
      const result = await read(`/api/torrent/${encodeURIComponent(torrent.id)}/select/${index}`, {title, job: torrent.job});
      if (!result.manifest_verified || !result.id) throw new Error('The Android engine did not accept that file.');
      if (state.torrent === torrent && !player().hidden) {
        player().hidden = true; state.playerFrom = null;
        if (downloadOnly) toast('This exact file is queued in Library.');
        else bbxAndroidOpen('play', result.id);
      }
      if (state.view === 'library') refreshLibrary();
    } catch (error) { message.textContent = error.message; toast(error.message); }
    finally { if (control) control.disabled = false; }
  };
  const chooser = showPlayer;
  showPlayer = function (torrent, title, options = {}) {
    chooser(torrent, title, options);
    const pane = player();
    pane.classList.add('android-file-chooser');
    const message = node('playerMsg');
    message.classList.add('on');
    message.textContent = 'Manifest verified. Choose the exact file; Android plays verified pieces as they arrive.';
    node('playerMode').textContent = 'Android player · exact torrent file';
  };

  // The same offline files, jobs and history are shared with the native library/player.
  refreshLibrary = async function () {
    const target = node('libraryList');
    if (state.view !== 'library') return;
    try {
      const {items} = await read('/api/mobile/library');
      if (state.view !== 'library') return;
      const toolbar = el('div', 'android-tools-row');
      toolbar.append(button('Device library & imports', () => bbxAndroidOpen('library')), button('Open a torrent', () => bbxAndroidOpen('torrent')));
      target.replaceChildren(toolbar);
      if (!items.length) target.append(el('p', 'sub', 'Nothing here yet. Open a title or import a source.'));
      for (const entry of items) {
        const card = el('article', 'download-card-v70 android-library-row');
        const copy = el('div', 'download-card-copy-v70');
        const status = entry.offline ? 'Offline copy' : entry.queued ? (entry.total > 0
          ? `${Math.min(99, Math.floor(100 * entry.received / entry.total))}% verified` : 'Queued') : entry.error || entry.label;
        copy.append(el('h3', '', entry.title), el('p', 'sub', `${entry.kind} · ${status}`));
        const actions = el('div', 'android-tools-row');
        actions.append(button(entry.position > 0 ? 'Resume' : 'Play', () => bbxAndroidOpen('play', entry.id), 'primary'));
        if (entry.queued) actions.append(button('Cancel download', async () => {
          if (!confirm('Cancel this unfinished download?')) return;
          try { await read(`/api/mobile/transfer/${entry.id}/cancel`, {}); refreshLibrary(); }
          catch (error) { toast(error.message); }
        }));
        card.append(copy, actions); target.append(card);
      }
    } catch (error) { target.replaceChildren(el('p', 'msg err', error.message)); }
  };

  window.bbxAndroidBack = () => {
    if (window.bbxExpiryV90?.isExpired()) return false;
    if (!node('settingsWrap').hidden) { node('settingsWrap').hidden = true; return true; }
    if (!player().hidden) { node('closePlayer').click(); return true; }
    if (state.findingCancel) { state.findingCancel(); return true; }
    if (state.view === 'detail') { node('detailBack').click(); return true; }
    if (state.view !== 'home') { showView('home'); return true; }
    return false;
  };

  // There is no Windows downloader to install in this APK.
  showMusicSourcePopup = () => {};
  ensureMusicPlaybackReady = async () => {
    toast('Choose a phone audio file or direct HTTPS audio link in Device library.');
    return false;
  };

  function installMobileControls() {
    const version = node('bbxVer'); version.title = 'PC v90 interface · Android engine';
    node('communityGlobalCount')?.classList.add('android-desktop-only');
    const menu = node('settingsWrap');
    const field = node('uri');
    field.placeholder = 'Magnet or HTTPS media / .torrent URL';
    field.parentElement?.previousElementSibling?.replaceChildren(document.createTextNode('Open a magnet or HTTPS media link, or select a .torrent document from your phone. Only use media you are authorized to download.'));
    const tools = el('div', 'android-tools-row');
    tools.append(button('Choose .torrent file', () => bbxAndroidOpen('torrent')), button('Import M3U or phone media', () => bbxAndroidOpen('library')), button('Community rooms', () => bbxAndroidOpen('rooms')));
    field.parentElement.after(tools);
    const privacy = el('p', 'sub android-privacy', 'TMDB credentials stay on this phone and go only to TMDB. Source lookup sends the selected title to the listed indexes. Torrent peers can see your IP. Windows-only integrations are not enabled here.');
    tools.after(privacy);
    const helpers = node('ytFetchNow')?.parentElement;
    if (helpers) { helpers.previousElementSibling?.classList.add('android-desktop-only'); helpers.previousElementSibling?.previousElementSibling?.classList.add('android-desktop-only'); helpers.classList.add('android-desktop-only'); }
    node('ytCookies')?.closest('label')?.classList.add('android-desktop-only');
    node('ytState')?.classList.add('android-desktop-only');
    node('privacyPanel')?.classList.add('android-desktop-only');
    // The original expiry message remains visible regardless of unsupported desktop settings.
    node('adsPanel')?.classList.add('android-ads-disabled');
    node('navShare')?.addEventListener('click', e => { e.stopImmediatePropagation(); bbxAndroidOpen('share'); }, true);
    const music = document.querySelector('#viewMusic .music-hero-content');
    if (music) {
      const audio = el('div', 'android-tools-row android-music-actions');
      audio.append(button('Open your audio files', () => bbxAndroidOpen('library')), el('p', 'sub', 'v90 music catalogue and playlists are available. The Windows online audio helper is not included.'));
      music.append(audio);
    }
    menu.dataset.platform = 'android';
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', installMobileControls, {once: true});
  else installMobileControls();
})();
