/* Use one local catalogue index for both Android Find surfaces. No provider calls. */
(() => {
  'use strict';
  const text = value => String(value || '').normalize('NFKD').toLowerCase()
    .replace(/[\u0300-\u036f]/g, '').replace(/['’]/g, '')
    .replace(/[^\p{L}\p{N}\p{M}]+/gu, ' ').trim();
  const spelling = value => String(value || '').slice(0, 240).trim()
    .replace(/\s*[([]official (?:music )?(?:audio|video|visualizer)[)\]]\s*$/i, '');
  const name = item => item.track || item.title || '';
  const artist = item => item.artist || item.channel || '';
  const idOf = item => item.catalogTrackId ? `album:${item.catalogTrackId}` : `pick:${item.id || ''}`;

  // The packaged rows omit Selena's credit. Correct only the two 3:59 rows,
  // for presentation/search; explicitly do not change the item sent to Downloads.
  function credits(item) {
    if (artist(item) === 'Rema' && name(item) === 'Calm Down' && Number(item.duration) === 239 &&
        (idOf(item) === 'pick:1873297197' && item.album === 'Calm Down' ||
         idOf(item) === 'album:d432510347:1:22' && item.album === 'Rave & Roses Ultra')) return 'Rema & Selena Gomez';
    return artist(item);
  }

  function index(albums, albumTracks, picks) {
    const artists = new Map(), songs = [];
    const entry = (item, title, performer = '', release = '') => ({
      item, title: text(title), combined: ` ${text(`${title} ${performer} ${release}`)} `,
    });
    const releases = albums.map(album => {
      const names = album.artistAliases || [album.artist];
      for (const alias of names) {
        const key = text(alias);
        if (!key) continue;
        if (!artists.has(key)) artists.set(key, {key, name:alias, albums:new Set()});
        artists.get(key).albums.add(album);
      }
      for (const track of albumTracks(album)) songs.push(entry(track, name(track), credits(track), track.album));
      return entry(album, album.title, [album.artist, ...names].join(' '));
    });
    for (const value of artists.values()) value.albums = [...value.albums].sort((a,b) => b.year-a.year || a.title.localeCompare(b.title));
    const people = [...artists.values()].map(item => entry(item, item.name));
    const seen = new Set();
    for (const track of picks) {
      if (!track.id || seen.has(track.id)) continue;
      seen.add(track.id);
      songs.push(entry(track, name(track), credits(track), track.album));
    }
    const picked = item => !item.catalogTrackId;

    return function search(query, limit = 30) {
      const q = text(spelling(query));
      const words = q ? [...new Set(q.split(' '))] : [];
      const last = q.split(' ').pop();
      const phrase = ` ${q} `;
      function score(row) {
        if (!words.length) return 0;
        let exact = 0;
        for (const word of words) {
          if (row.combined.includes(` ${word} `)) exact++;
          else if (word !== last || word.length < 2 || !row.combined.includes(` ${word}`)) return 0;
        }
        return exact * 1000 + (row.title === q ? 400 : phrase.includes(` ${row.title} `) ? 200
          : row.title.startsWith(q) ? 100 : 0);
      }
      function matches(rows) {
        return rows.map(row => ({row, score:score(row)})).filter(hit => hit.score)
          .sort((a,b) => b.score-a.score).map(hit => hit.row.item);
      }
      const artistExact = artists.get(q) || null;
      const artistMatches = matches(people).slice(0, 8);
      const exactTitleAlbums = releases.filter(row => row.title === q && q).map(row => row.item);
      const exactAlbum = exactTitleAlbums[0] || null;
      const albumMatches = artistExact ? [...new Set([...exactTitleAlbums, ...artistExact.albums])] : matches(releases).slice(0, limit);
      const allSongs = matches(songs).filter(item => !artistExact && !exactAlbum || text(name(item)) === q || picked(item));
      return {query, q, artistExact, artists:artistMatches, albums:albumMatches, exactTitleAlbums, exactAlbum,
        curatedHits:allSongs.filter(picked).slice(0, Math.min(18, limit)),
        tracks:allSongs.filter(item => !picked(item)).slice(0, limit), songs:allSongs.slice(0, limit)};
    };
  }

  // Keep the v90 suggestion styling and control roles, but use the same results as Search.
  function wire(input, {search, visible, artwork, activate, render}) {
    const element = (type, classes, copy) => {
      const item = document.createElement(type);
      if (classes) item.className = classes;
      if (copy) item.textContent = copy;
      return item;
    };
    const panel = element('div', 'music-instant-v76');
    panel.id = 'musicInstantV76'; panel.hidden = true;
    panel.setAttribute('role', 'listbox'); panel.setAttribute('aria-label', 'Music suggestions');
    input.setAttribute('role', 'combobox'); input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-controls', panel.id); input.setAttribute('aria-expanded', 'false');
    document.body.append(panel);
    let rows = [], cursor = -1, timer, generation = 0;

    function position() {
      if (panel.hidden) return;
      const box = input.getBoundingClientRect(), dock = (input.closest('.music-search-dock-v47') || input).getBoundingClientRect();
      const width = Math.min(window.innerWidth - 16, Math.max(280, dock.width));
      const below = window.innerHeight - box.bottom - 8, above = box.top - 8;
      const raise = below < 180 && above > below;
      Object.assign(panel.style, {position:'fixed', width:`${width}px`,
        left:`${Math.min(Math.max(8, dock.left), window.innerWidth-width-8)}px`,
        top:raise ? 'auto' : `${box.bottom+6}px`, bottom:raise ? `${window.innerHeight-box.top+6}px` : 'auto',
        maxHeight:`${Math.max(0, Math.min(540, window.innerHeight*.64, raise ? above : below))}px`});
    }
    function close() {
      generation++; clearTimeout(timer);
      panel.hidden = true; panel.replaceChildren(); rows = []; cursor = -1;
      input.setAttribute('aria-expanded', 'false'); input.removeAttribute('aria-activedescendant');
    }
    function heading(label) { panel.append(element('div', 'music-instant-head-v76', label)); }
    function addRow(kind, hit) {
      const item = kind === 'song' ? hit.track : hit;
      const row = element(kind === 'artist' ? 'button' : 'div', 'music-instant-row-v76');
      row.id = `music-instant-choice-${rows.length}`;
      row.dataset.kind = kind; row.dataset.instantRow = '1'; row._hit = hit;
      row.setAttribute('role', 'option'); row.setAttribute('aria-selected', 'false');
      if (kind === 'artist') row.type = 'button';
      const label = kind === 'artist' ? item.name : name(item);
      const cover = element('span', `music-instant-art-v76 ${kind === 'artist' ? 'is-letter-v76' : ''}`,
        kind === 'artist' ? label.slice(0,1).toUpperCase() : '');
      if (kind !== 'artist') {
        const src = artwork(item.artwork || item.thumbnail || '');
        if (src) { const img = element('img'); img.src = src; img.alt = ''; img.loading = 'lazy'; cover.append(img); }
      }
      const copy = element('span', 'music-instant-copy-v76');
      const seconds = Math.round(Number(item.duration));
      const duration = seconds > 0 ? ` · ${Math.floor(seconds/60)}:${String(seconds%60).padStart(2,'0')}` : '';
      copy.append(element('strong', '', label), element('small', '', kind === 'artist' ? `${item.albums.length} indexed releases`
        : kind === 'album' ? `${item.artist} · ${item.rawTracks.length} tracks`
        : `${credits(item)}${item.album ? ` · ${item.album}` : ''}${duration}`));
      row.append(cover, copy);
      if (kind !== 'artist') {
        const button = element('button', 'music-instant-add-v76', '↓');
        button.type = 'button'; button.dataset.instantAction = 'add';
        button.setAttribute('aria-label', `Queue ${label}`); row.append(button);
        if (kind === 'song') {
          const play = element('span', 'music-instant-play-v76', '▶');
          play.setAttribute('aria-hidden', 'true'); row.append(play);
        }
      } else row.append(element('span', 'music-instant-go-v76', 'See all'));
      rows.push(row); panel.append(row);
    }
    async function populate(value, ticket) {
      let result;
      try { result = await search(value); } catch { /* Find can still report a catalogue load error on submit. */ }
      if (ticket !== generation || !visible()) return;
      panel.replaceChildren(); rows = []; cursor = -1;
      input.removeAttribute('aria-activedescendant');
      const groups = result ? [['ARTISTS', 'artist', result.artists.slice(0,5)], ['ALBUMS', 'album', result.albums.slice(0,7)],
        ['SONGS', 'song', result.songs.slice(0,7).map(track => ({track}))]] : [];
      for (const [label, kind, items] of groups) if (items.length) { heading(label); items.forEach(item => addRow(kind, item)); }
      if (!rows.length) panel.append(element('div', 'music-instant-empty-v76',
        result ? 'No match in the included v90 catalogue.' : 'Could not open the included v90 catalogue.'));
      panel.hidden = false; input.setAttribute('aria-expanded', 'true');
      position(); render();
    }
    function schedule() {
      close();
      const value = input.value.trim();
      if (!visible() || value.length < 2) return;
      const ticket = generation;
      timer = setTimeout(() => populate(value, ticket), 80);
    }
    function choose(row) {
      close(); activate(row.dataset.kind, row._hit);
    }
    input.addEventListener('input', event => { if (!event.isComposing) schedule(); });
    input.addEventListener('compositionend', schedule);
    input.addEventListener('focus', schedule);
    panel.addEventListener('mousedown', event => { if (event.target.closest('[data-instant-row]')) event.preventDefault(); });
    panel.addEventListener('click', event => {
      const row = event.target.closest('[data-instant-row]');
      if (row && !event.target.closest('[data-instant-action]')) choose(row);
    });
    input.addEventListener('keydown', event => {
      if (event.isComposing || panel.hidden) return;
      if (event.key === 'Escape') { event.preventDefault(); close(); }
      else if (event.key === 'Enter') {
        if (cursor < 0) { close(); return; }
        event.preventDefault(); event.stopImmediatePropagation(); choose(rows[cursor]);
      } else if (rows.length && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
        event.preventDefault();
        cursor = cursor < 0 ? event.key === 'ArrowDown' ? 0 : rows.length-1
          : (cursor + rows.length + (event.key === 'ArrowDown' ? 1 : -1)) % rows.length;
        rows.forEach((row,i) => { row.classList.toggle('on', i === cursor); row.setAttribute('aria-selected', String(i === cursor)); });
        input.setAttribute('aria-activedescendant', rows[cursor].id);
        rows[cursor].scrollIntoView({block:'nearest'});
      }
    }, true);
    document.addEventListener('pointerdown', event => {
      if (event.target !== input && !panel.contains(event.target)) close();
    });
    for (const type of ['resize','scroll']) window.addEventListener(type, position, {capture:true, passive:true});
    return {close};
  }

  window.BbxOfflineFind = {index, wire, credits};
})();
