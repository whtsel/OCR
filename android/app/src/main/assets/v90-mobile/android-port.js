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
  window.bbxAndroidRequest = read;
  const player = () => node('playerWrap');
  const originalNoKey = renderNoKey;
  renderNoKey = function () {
    originalNoKey();
    node('hero').classList.add('android-keyless');
    node('heroTorrent').onclick = () => bbxAndroidOpen('torrent');
  };
  const originalLoadHome = loadHome;
  loadHome = function (...args) {
    node('hero')?.classList.remove('android-keyless');
    return originalLoadHome.apply(this, args);
  };
  // Ignore synthetic catalogue entries persisted by older previews, without changing user files.
  const tmdbTitle = item => /^[1-9][0-9]*$/.test(String(item?.id || '')) && !item._anime;
  state.watchlist = state.watchlist.filter(entry => tmdbTitle(entry.movie));
  for (const [key, item] of COMING_SOON_CACHE) if (!tmdbTitle(item)) COMING_SOON_CACHE.delete(key);

  // Sources are separate from the TMDB catalogue and open only the chosen release/file.
  const originalSourceCard = sourceCardV70;
  sourceCardV70 = function (source) {
    const card = originalSourceCard(source);
    const play = card.querySelector('.source-play-chip-v74');
    play?.classList.add('android-source-play');
    const title = el('span', 'android-release-title', source.title || 'Selected release');
    card.prepend(title);
    const kind = state.view === 'detail' ? (isAnime(state.movie) ? 'ANIME' : isTv(state.movie) ? 'SERIES' : 'MOVIE') : undefined;
    card.onclick = () => openTorrent(source.magnet, source.title, {mode:'play', kind});
    const download = card.querySelector('.source-dl-chip-v74');
    if (download) download.onclick = event => {
      event.stopPropagation(); openTorrent(source.magnet, source.title, {mode:'download', saveCopy:true, kind});
    };
    return card;
  };
  const originalBrowse = loadBrowsePage;
  loadBrowsePage = function (...args) {
    if (state.tmdb) return originalBrowse.apply(this, args);
    node('browseMore').hidden = true;
    node('resultCount').textContent = '';
    node('browseGrid').replaceChildren(el('p', 'sub', 'Add your TMDB key to browse and search the catalogue.'),
      button('Add TMDB key', openSettings, 'primary'));
  };

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
        const download = options.mode === 'download' || options.saveCopy === true;
        const result = await read('/api/mobile/direct', {url: link, title: title || undefined, kind: options.kind, download});
        node('settingsWrap').hidden = true;
        if (download) toast(result.offline ? 'Already downloaded.' : 'Download queued in Library.');
        else bbxAndroidOpen('play', result.id);
        return;
      }
    } catch (error) { toast(error.message); return; }
    let job, cancelled = false;
    const started = Date.now();
    state.resolveId = 'starting';
    state.findingCancel = async () => {
      cancelled = true;
      if (job) await read(`/api/content-job/${job}/cancel`, {}).catch(() => {});
    };
    showFinding(`Checking complete manifest for ${name.slice(0, 72)}…`);
    try {
      ({job} = await read('/api/content-resolve', {uri: link, kind: options.kind}));
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
        findingText(`${reply.msg || 'Waiting for the selected source…'} · ${Math.floor((Date.now() - started) / 1000)}s`);
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

  // Refresh progress while a job is active, and stop polling when Library is hidden.
  let libraryTimer, libraryBusy = false, librarySnapshot = '';
  refreshLibrary = async function () {
    clearTimeout(libraryTimer);
    const target = node('libraryList');
    if (state.view !== 'library' || !target || libraryBusy) return;
    libraryBusy = true;
    let activeJob = false;
    try {
      const {items} = await read('/api/mobile/library');
      if (state.view !== 'library') return;
      activeJob = items.some(entry => entry.queued);
      const snapshot = JSON.stringify(items);
      if (snapshot === librarySnapshot && target.querySelector('.android-library-tools')) return;
      librarySnapshot = snapshot;
      const toolbar = el('div', 'android-tools-row android-library-tools');
      toolbar.append(button('Device library & imports', () => bbxAndroidOpen('library')), button('Open a torrent', () => bbxAndroidOpen('torrent')),
        button('Refresh', () => refreshLibrary()));
      target.replaceChildren(toolbar);
      if (!items.length) target.append(el('p', 'sub', 'Nothing here yet. Play or download a title, or import a source.'));
      for (const entry of items) {
        const card = el('article', 'download-card-v70 android-library-row');
        card.dataset.mediaId = entry.id;
        card.dataset.offline = String(entry.offline);
        const copy = el('div', 'download-card-copy-v70');
        const status = entry.offline ? 'Offline copy' : entry.queued ? (entry.total > 0
          ? `${Math.min(99, Math.floor(100 * entry.received / entry.total))}% saved`
          : entry.received > 0 ? `${fmtBytes(entry.received)} saved` : 'Waiting to download') : entry.error || entry.label;
        copy.append(el('h3', '', entry.title), el('p', entry.error && !entry.queued ? 'msg err' : 'sub', `${entry.kind} · ${status}`));
        const actions = el('div', 'android-tools-row');
        actions.append(button(entry.position > 0 ? 'Resume' : 'Play', () => bbxAndroidOpen('play', entry.id), 'primary android-library-play'));
        if (entry.queued) actions.append(button('Cancel download', async () => {
          if (!confirm('Cancel this unfinished download?')) return;
          try { await read(`/api/mobile/transfer/${entry.id}/cancel`, {}); refreshLibrary(); }
          catch (error) { toast(error.message); }
        }));
        else if (entry.downloadable && !entry.offline) actions.append(button(entry.error ? 'Retry download' : 'Download', async event => {
          const control = event.currentTarget;
          control.disabled = true;
          try { await read(`/api/mobile/transfer/${entry.id}/download`, {}); refreshLibrary(); }
          catch (error) { toast(error.message); }
          finally { control.disabled = false; }
        }));
        card.append(copy, actions); target.append(card);
      }
    } catch (error) { target.replaceChildren(el('p', 'msg err', error.message), button('Retry', () => refreshLibrary())); }
    finally {
      libraryBusy = false;
      if (state.view === 'library' && activeJob) libraryTimer = setTimeout(refreshLibrary, 1300);
    }
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
    menu.dataset.platform = 'android';
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', installMobileControls, {once: true});
  else installMobileControls();
})();
