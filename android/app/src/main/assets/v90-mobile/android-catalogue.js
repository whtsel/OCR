/* Keep the v90 views; catalogue entries only play when they have an actual Android source. */
(() => {
  'use strict';
  if (!window.bbxAndroidOpen) return;
  const byId = id => document.getElementById(id);
  const read = window.bbxAndroidRequest;
  const external = url => bbxAndroidOpen('browser', url);
  const button = (label, fn, classes = 'secondary') => {
    const b = el('button', classes, label);
    b.type = 'button'; b.onclick = fn; return b;
  };
  const youtube = id => {
    if (/^[A-Za-z0-9_-]{11}$/.test(id || '')) external(`https://www.youtube.com/watch?v=${id}`);
  };
  async function trailer(item) {
    if (!state.tmdb) return;
    try {
      const info = await getTrailer(item);
      if (info?.key) youtube(info.key);
    } catch { /* No listing is not a substitute video. */ }
  }
  mountHeroTrailer = async () => {};
  mountDetailTrailer = async () => {};
  watchHeroTrailer = () => { const item = state.heroItems[state.heroIdx]; if (item) trailer(item); };
  openTrailerPop = (_control, id) => { if (id) youtube(id); };
  mountExperienceComments = () => {}; // Native private rooms are available in Settings.
  loadYoutubeCardStreamsV74 = async movie => {
    const section = byId('youtubeCardWatchV74');
    if (!section) return;
    section.hidden = true;
    if (!state.tmdb) return;
    try {
      const info = await getTrailer(movie);
      if (!info?.key || !section.isConnected) return;
      section.replaceChildren(button('Watch TMDB-listed trailer ↗', () => youtube(info.key)));
      section.hidden = false;
    } catch { /* No trailer or fake playback. */ }
  };
  document.addEventListener('click', event => {
    const link = event.target.closest?.('a.watch-provider-link-v74');
    if (link) { event.preventDefault(); external(link.href); }
  });

  const statusText = () => {
    const p = el('p', 'android-action-status sub');
    p.setAttribute('role', 'status'); return p;
  };
  async function openMedia(path, data, control, status) {
    if (control?.disabled) return;
    if (control) control.disabled = true;
    status.classList.remove('err');
    status.textContent = data.download ? 'Adding to downloads…' : 'Opening in BlackBox…';
    try {
      const result = await read(path, data);
      if (!result.id) throw new Error('The selected file could not be opened.');
      if (data.download) {
        status.replaceChildren(document.createTextNode(result.offline ? 'Already saved offline. ' : 'Download queued. '),
          button('View Library', () => { showView('library'); refreshLibrary(); }, 'ghost'));
      } else {
        status.textContent = '';
        bbxAndroidOpen('play', result.id);
      }
    } catch (error) { status.classList.add('err'); status.textContent = error.message; }
    finally { if (control) control.disabled = false; }
  }
  function linkInput(kind, title) {
    const details = el('details', 'android-direct-link');
    details.append(el('summary', '', kind === 'MUSIC' ? 'Open your own audio link' : 'Play or download your own file link'));
    const form = el('form', 'android-tools-row');
    form.addEventListener('submit', e => e.preventDefault());
    const input = document.createElement('input');
    input.type = 'url'; input.placeholder = kind === 'MUSIC' ? 'https://…/track.mp3' : 'https://…/video.mp4';
    input.setAttribute('aria-label', 'Direct HTTPS media URL');
    const status = statusText();
    const run = (e, download) => {
      const url = input.value.trim();
      if (!url) { status.textContent = 'Enter an authorized direct HTTPS media link.'; input.focus(); return; }
      openMedia('/api/mobile/direct', {kind, title:title || undefined, url, download}, e.currentTarget, status);
    };
    form.append(input, button('Play link', e => run(e, false), 'primary'), button('Download file', e => run(e, true)));
    details.append(form, status); return details;
  }

  // Keep the original Anime view, but its cards are TMDB series rather than a second catalogue.
  animeToMovie = item => ({...item, media_type:'tv'});
  window.bbxDecorateCardMediaV74 = undefined;
  window.bbxMountAnimeStudioV74 = undefined;
  window.bbxMountDetailMediaStripV74 = undefined;
  window.bbxOpenCardMediaModalV74 = undefined;
  findAnimeStreams = async () => { byId('streamBox')?.closest('.streambox')?.setAttribute('hidden', ''); };
  renderSourceFallback = target => {
    target.append(button('Search by title instead', () => byId('searchInput').focus()),
      button('Open a magnet or .torrent', () => bbxAndroidOpen('torrent')));
  };
  const sourceParams = sourceParamsV69;
  const isJapaneseAnimation = item => item.original_language === 'ja' && (item.genre_ids?.includes(16) || item.genres?.some(g => g.id === 16));
  sourceParamsV69 = function (item, imdb) {
    const params = sourceParams(item, imdb);
    if (isJapaneseAnimation(item)) params.set('type', isTv(item) ? 'anime' : 'anime-film');
    const alternate = item.original_title || item.original_name;
    if (alternate && alternate !== titleOf(item)) params.set('alt', alternate);
    return params;
  };
  const renderDetail = renderDetailLayout;
  renderDetailLayout = function (movie, spec) {
    renderDetail(movie, spec);
    const play = byId('detailPlay'), sources = byId('sourcesBox'), detail = byId('detailBody'), watch = byId('watchProvidersV74');
    if (!sources || !detail) return;
    // Avoid a misleading "YouTube streams" shortcut on Android title details.
    detail.querySelectorAll('.hero-actions button').forEach(b => { if (b.textContent.startsWith('YouTube streams')) b.hidden = true; });
    if (watch) detail.insertBefore(sources, watch);
    const kind = isJapaneseAnimation(movie) ? 'ANIME' : isTv(movie) ? 'SERIES' : 'MOVIE';
    const heading = el('h2', 'android-source-heading', 'Sources');
    sources.before(heading);
    const station = el('section', 'android-direct-station');
    station.id = 'androidDetailMedia';
    station.append(el('p', 'sub', spec.unreleased ? 'No released file yet.' : 'TMDB provides catalogue information, not the video. Choose a source or open a file you have permission to use.'),
      linkInput(kind, titleOf(movie)));
    sources.before(station);
    const epi = byId('epInput');
    if (epi && kind === 'ANIME') epi.max = '9999';
    if (play) {
      play.textContent = 'Choose a source ↓';
      // The original openDetail sets onclick later; capture the mobile action first.
      play.addEventListener('click', event => {
        event.stopImmediatePropagation();
        heading.scrollIntoView({behavior:'smooth', block:'start'});
      }, true);
    }
  };

  const originalView = showView;
  showView = function (next) {
    const r = originalView.call(this, next);
    if (next === 'library') refreshLibrary();
    return r;
  };
  window.bbxAndroidResume = () => {
    if (state.view === 'library') refreshLibrary();
  };
})();
