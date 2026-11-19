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
  const youtube = (query, id) => external(/^[A-Za-z0-9_-]{11}$/.test(String(id || ''))
    ? `https://www.youtube.com/watch?v=${id}`
    : `https://www.youtube.com/results?search_query=${encodeURIComponent(String(query).slice(0, 220))}`);
  const canTrailer = item => !!(item?._trailer || state.tmdb && !item?._openFilm);
  async function trailer(item) {
    let info;
    try { if (canTrailer(item)) info = await getTrailer(item); } catch {}
    const name = titleOf(item);
    if (name) youtube(`${name} ${yearOf(item) || ''} official trailer`, info?.key);
  }
  mountHeroTrailer = async () => {};
  mountDetailTrailer = async () => {};
  watchHeroTrailer = () => { const item = state.heroItems[state.heroIdx]; if (item) trailer(item); };
  openTrailerPop = (_control, id, title) => youtube(`${title || ''} official trailer`, id);
  mountExperienceComments = () => {}; // Native private rooms are available in Settings.
  loadYoutubeCardStreamsV74 = async movie => {
    const section = byId('youtubeCardWatchV74');
    if (!section) return;
    if (movie._androidPlayable) { section.hidden = true; return; }
    section.replaceChildren(el('p', 'sub', 'Optional trailer. This is not a playable film source.'),
      button('Find official trailer ↗', () => trailer(movie)));
  };
  const filmProjects = {open_sintel:'sintel', open_big_buck_bunny:'big-buck-bunny', open_tears_of_steel:'tears-of-steel', open_cosmos_laundromat:'cosmos-laundromat'};
  const loadWatchOptions = loadOfficialWatchOptionsV74;
  loadOfficialWatchOptionsV74 = function (movie) {
    const section = byId('watchProvidersV74');
    if (section && movie._androidPlayable) { section.hidden = true; return Promise.resolve(); }
    return loadWatchOptions(movie);
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
  function playableActions(key, status, name) {
    const actions = el('div', 'android-tools-row');
    const play = button('▶ Play', e => openMedia('/api/mobile/playable', {key}, e.currentTarget, status), 'primary android-media-play');
    const download = button('↓ Download', e => openMedia('/api/mobile/playable', {key, download:true}, e.currentTarget, status), 'secondary android-media-download');
    play.setAttribute('aria-label', `Play ${name} in BlackBox`);
    download.setAttribute('aria-label', `Download ${name}`);
    actions.append(play, download);
    return actions;
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

  // Original v90 hides the torrent list for anime while its Windows-only helper runs.
  // Android uses that list for episodes, alongside explicitly playable archive films.
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
  sourceParamsV69 = function (item, imdb) {
    const params = sourceParams(item, imdb), anime = isAnime(item);
    if (anime) params.set('type', isTv(item) ? 'anime' : 'anime-film');
    const alternate = anime ? (item.altTitle || item._native) : (item.original_title || item.original_name);
    if (alternate && alternate !== titleOf(item)) params.set('alt', alternate);
    return params;
  };
  const toAnimeCard = animeToMovie;
  animeToMovie = function (item) {
    const card = toAnimeCard(item);
    if (item.playable) Object.assign(card, {_androidPlayable:item.playable, _androidCredits:item.credits,
      _androidReference:item.reference, runtime:Math.round(item.duration / 60)});
    return card;
  };
  let archive;
  async function showAnimeArchive() {
    const browse = byId('browseGrid');
    let host = byId('androidAnimeArchive');
    if (!host) { host = el('section', 'android-anime-archive'); host.id = 'androidAnimeArchive'; browse.before(host); }
    host.hidden = false;
    if (host.firstChild) return;
    try {
      archive ||= read('/api/mobile/open-anime');
      const items = (await archive).items.map(animeToMovie);
      const row = makeRow('Playable anime · complete archive films', items, null);
      row.querySelectorAll('.card').forEach((card, i) => { card.dataset.playableKey = items[i]._androidPlayable; });
      if (state.browse?.mode === 'anime') host.replaceChildren(row);
    } catch {
      archive = null;
      if (state.browse?.mode === 'anime') host.replaceChildren(button('Retry playable anime', () => {
        host.replaceChildren(); showAnimeArchive();
      }));
    }
  }
  const lookupSources = loadSourcesFor;
  loadSourcesFor = function (item) {
    if (item?._androidPlayable?.startsWith('anime:')) {
      sourceLookupAbortV69?.abort();
      return Promise.resolve();
    }
    return lookupSources(item);
  };
  const renderDetail = renderDetailLayout;
  renderDetailLayout = function (movie, spec) {
    renderDetail(movie, spec);
    const play = byId('detailPlay'), sources = byId('sourcesBox'), detail = byId('detailBody'), watch = byId('watchProvidersV74');
    if (!sources || !detail) return;
    // Avoid a misleading "YouTube streams" shortcut on Android title details.
    detail.querySelectorAll('.hero-actions button').forEach(b => { if (b.textContent.startsWith('YouTube streams')) b.hidden = true; });
    if (watch) detail.insertBefore(sources, watch);
    const exact = movie._androidPlayable, openAnime = exact?.startsWith('anime:');
    sources.hidden = !!openAnime;
    const kind = isAnime(movie) ? 'ANIME' : isTv(movie) ? 'SERIES' : 'MOVIE';
    const heading = el('h2', 'android-source-heading', 'Sources');
    sources.before(heading);
    const station = el('section', 'android-direct-station');
    station.id = 'androidDetailMedia';
    const status = statusText();
    if (exact) {
      station.dataset.playableKey = exact;
      const project = filmProjects[movie.id];
      const credit = movie._androidCredits || 'Full original film by Blender · Creative Commons Attribution 3.0';
      const reference = movie._androidReference || (project ? `https://studio.blender.org/projects/${project}/` : '');
      station.append(el('strong', '', openAnime ? 'Full archive film · direct file' : 'Full film · direct HTTPS'),
        el('p', 'sub', credit), playableActions(exact, status, titleOf(movie)), status);
      if (reference) station.append(button('Source & credits ↗', () => external(reference), 'ghost'));
      if (openAnime) {
        if (byId('srcCount')) byId('srcCount').textContent = '1 direct file';
        if (byId('detailRetry')) byId('detailRetry').hidden = true;
      }
    } else station.append(el('p', 'sub', spec.unreleased ? 'No released file yet.' : 'Catalogue information does not include video. Choose an exact release below, or provide a direct file.'),
      linkInput(kind, titleOf(movie)));
    sources.before(station);
    const epi = byId('epInput');
    if (epi && kind === 'ANIME') epi.max = '9999';
    if (play) {
      play.textContent = exact ? '▶ Play in BlackBox' : 'Choose a source ↓';
      // The original openDetail sets onclick later; capture the mobile action first.
      play.addEventListener('click', event => {
        event.stopImmediatePropagation();
        if (exact) openMedia('/api/mobile/playable', {key:exact}, play, status);
        else heading.scrollIntoView({behavior:'smooth', block:'start'});
      }, true);
    }
  };

  const originalView = showView;
  showView = function (next) {
    const r = originalView.call(this, next);
    const anime = next === 'browse' && state.browse?.mode === 'anime';
    if (anime) showAnimeArchive();
    else if (byId('androidAnimeArchive')) byId('androidAnimeArchive').hidden = true;
    if (next === 'library') refreshLibrary();
    return r;
  };
  window.bbxAndroidResume = () => {
    if (state.view === 'library') refreshLibrary();
  };
})();
