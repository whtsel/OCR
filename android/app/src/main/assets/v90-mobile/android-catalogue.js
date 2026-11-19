/* Portable catalogue integrations for the original v90 detail and Music layouts. */
(() => {
  'use strict';
  if (!window.bbxAndroidOpen) return;
  const byId = id => document.getElementById(id);
  const external = url => bbxAndroidOpen('browser', url);
  const youtube = (query, id) => external(/^[A-Za-z0-9_-]{11}$/.test(String(id || ''))
    ? `https://www.youtube.com/watch?v=${id}`
    : `https://www.youtube.com/results?search_query=${encodeURIComponent(String(query).slice(0, 220))}`);
  const action = (label, fn) => {
    const b = el('button', 'secondary', label);
    b.type = 'button'; b.onclick = fn; return b;
  };
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
  mountExperienceComments = () => {}; // Native private rooms are available in Settings instead.
  loadYoutubeCardStreamsV74 = async movie => {
    const section = byId('youtubeCardWatchV74');
    if (!section) return;
    section.replaceChildren(
      el('p', 'sub', 'Trailers open in the YouTube app or browser. No sign-in cookies or video downloads are used by BlackBox.'),
      action('Find official trailer ↗', () => trailer(movie)),
    );
  };
  const openCredits = {
    open_sintel: 'sintel', open_big_buck_bunny: 'big-buck-bunny', open_tears_of_steel: 'tears-of-steel',
    open_cosmos_laundromat: 'cosmos-laundromat',
  };
  const loadWatchOptions = loadOfficialWatchOptionsV74;
  loadOfficialWatchOptionsV74 = function (movie) {
    const section = byId('watchProvidersV74');
    const project = openCredits[movie.id];
    if (movie._openFilm && project && section) {
      section.replaceChildren(el('p', 'sub', 'Full original open film · Blender · Creative Commons Attribution 3.0'),
        action('Original project & credits ↗', () => external(`https://studio.blender.org/projects/${project}/`)));
      return Promise.resolve();
    }
    return loadWatchOptions(movie);
  };
  document.addEventListener('click', event => {
    const link = event.target.closest?.('a.watch-provider-link-v74');
    if (link) { event.preventDefault(); external(link.href); }
  });

  // Retain the shipped anime catalogue and details; use the original source list and Android
  // player instead of the Windows helper and web-server embeds.
  window.bbxDecorateCardMediaV74 = undefined;
  window.bbxMountAnimeStudioV74 = undefined;
  window.bbxMountDetailMediaStripV74 = undefined;
  window.bbxOpenCardMediaModalV74 = undefined;
  findAnimeStreams = async () => { byId('streamBox')?.closest('.streambox')?.setAttribute('hidden', ''); };
  renderSourceFallback = target => {
    target.append(action('Search by title instead', () => { byId('searchInput').focus(); }),
      action('Open an authorized torrent', () => bbxAndroidOpen('torrent')));
  };
  const sourceParams = sourceParamsV69;
  sourceParamsV69 = function (item, imdb) {
    const params = sourceParams(item, imdb);
    if (isAnime(item)) {
      if (isTv(item)) params.set('type', 'anime');
      if (item.altTitle && item.altTitle !== titleOf(item)) params.set('alt', item.altTitle);
    }
    return params;
  };
  // Prioritize exact source selection on phone. The header action never chooses a random release.
  const renderDetail = renderDetailLayout;
  renderDetailLayout = function (movie, spec) {
    renderDetail(movie, spec);
    const play = byId('detailPlay'), sources = byId('sourcesBox');
    if (sources) {
      const detail = byId('detailBody'), watch = byId('watchProvidersV74');
      if (detail && watch) detail.insertBefore(sources, watch);
      const label = el('h2', 'android-source-heading', 'Sources');
      sources.before(label);
      if (play) {
        play.textContent = 'Choose a source ↓';
        // v90 sets onclick after renderDetailLayout. The capture handler is the mobile override.
        play.addEventListener('click', event => {
          event.stopImmediatePropagation(); label.scrollIntoView({behavior:'smooth', block:'start'});
        }, true);
      }
    }
  };

  // Music search and genre/album browsing stay in the original desktop layout. Live search
  // returns MusicBrainz metadata, and a track either opens as your own media or at its provider.
  getCachedMusicSearchResults = async query => {
    const text = String(query).trim().slice(0, 130), key = `android:${text.toLowerCase()}`;
    const cached = musicSearchCache.get(key);
    if (cached) return cached;
    const index = window.bbxMusicSearchV76;
    const matches = (index?.search(text, {limit: 20}).songs || []).map(hit => hit.track).filter(Boolean);
    let tracks = [];
    try {
      const r = await fetch(`/api/mobile/music?q=${encodeURIComponent(text)}`);
      if (r.ok) tracks = (await r.json()).results || [];
      else if (!matches.length) throw new Error((await r.json()).message || 'MusicBrainz is unavailable.');
    } catch (error) { if (!matches.length) throw error; }
    const rows = [...new Map([...matches, ...tracks].map(row => [musicSearchRecordKey(row), row])).values()].slice(0, 30);
    if (musicSearchCache.size >= 80) musicSearchCache.delete(musicSearchCache.keys().next().value);
    musicSearchCache.set(key, rows);
    return rows;
  };
  playMusicList = (list, index = 0) => {
    const track = list?.[index];
    if (track) youtube(`${track.artist || track.channel || ''} ${track.track || track.title || ''} official audio`, track.ytId);
  };
  const oldQueueCurrent = queuePlayCurrent;
  queuePlayCurrent = options => {
    const track = musicQueue.list[musicQueue.idx];
    if (track?.ytId && musicOfflineStateForAndroid(track.ytId)) return oldQueueCurrent(options);
    if (track && options?.intent === 'user') return playMusicList([track]);
  };
  function musicOfflineStateForAndroid(id) {
    return Array.isArray(musicDownloads) && musicDownloads.some(track => track?.ytId === id && track?.fileName);
  }
  warmMusicTrack = async track => track?.ytId || '';
  proactivelyWarmMusicItems = () => {};
  wireMusicWarmup = () => {};
  const showOriginalMusic = showView;
  showView = function (next) {
    const r = showOriginalMusic.call(this, next);
    if (next === 'music') refreshAudioRows();
    return r;
  };
  async function refreshAudioRows() {
    const host = byId('androidAudioLibrary');
    if (!host) return;
    try {
      const r = await fetch('/api/mobile/library');
      if (!r.ok) return;
      const entries = (await r.json()).items.filter(entry => entry.kindId === 'MUSIC');
      host.replaceChildren(action('Import audio / playlists', () => bbxAndroidOpen('library')));
      for (const entry of entries.slice(0, 16)) host.append(action(`${entry.offline ? 'Offline · ' : ''}${entry.title}`, () => bbxAndroidOpen('play', entry.id)));
    } catch { /* Native library remains available through the import action. */ }
  }
  function musicControls() {
    const shelf = byId('musicLibrarySidebar'), hero = document.querySelector('.music-hero-content');
    if (!shelf || !hero) return;
    const p = hero.querySelector(':scope > p');
    if (p) p.textContent = 'Browse the v90 genres, artists and albums. Search the music catalogue, find official audio in YouTube, or play your own saved audio here.';
    const meta = hero.querySelector('.music-hero-meta-v47');
    if (meta?.lastElementChild) meta.lastElementChild.textContent = 'On-device audio library';
    const library = el('section', 'android-audio-library');
    library.id = 'androidAudioLibrary';
    library.append(action('Import audio / playlists', () => bbxAndroidOpen('library')));
    shelf.prepend(library);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', musicControls, {once:true});
  else musicControls();
})();
