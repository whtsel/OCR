/* Regression checks against v90's shipped catalogue and genre picks. No network or audio. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const assets = path.resolve(__dirname, '../app/src/main/assets/v90');
const read = name => fs.readFileSync(path.join(assets, name), 'utf8');
const realm = vm.createContext({inferMusicGenre: () => 'Trending Now'});
realm.window = realm;
const originals = read('app.js');
function sourceFrom(marker, endAt) {
  const start = originals.indexOf(marker);
  const end = originals.indexOf(endAt, start + marker.length);
  assert(start >= 0 && end > start, `Missing pinned v90 source: ${marker}`);
  return originals.slice(start, end);
}
vm.runInContext(sourceFrom('const MUSIC_GENRES_V50=', ';') + ';\n' +
  sourceFrom('function musicTextNorm(', 'function musicGenreRecord('), realm);
for (const name of ['music-catalog-v64.js', 'music-albums-v64.js']) {
  vm.runInContext(read(name), realm, {filename:name});
}
const file = path.resolve(__dirname, '../app/src/offlineMusic/assets/v90-offline/offline-find.js');
vm.runInContext(fs.readFileSync(file,'utf8'), realm, {filename:file});
const find = realm.BbxOfflineFind;
let search;
test.before(async () => {
  const catalogue = await realm.loadMusicAlbumCatalogV63();
  const picks = vm.runInContext('MUSIC_GENRES_V50.flatMap(genre => genre.results)', realm);
  search = find.index(catalogue.albums, realm.musicAlbumTracksV63, picks);
});

test('Joro is returned as the real curated single in a submitted or instant search', () => {
  for (const limit of [7,30]) {
    const got = search('Joro Wizkid', limit);
    assert.deepEqual(Array.from(got.songs, t => [t.title,t.artist,t.id]), [['Joro','Wizkid','705337482']]);
    assert.equal(got.curatedHits[0], got.songs[0]);
    assert.equal(got.albums.length, 0, 'Do not invent albums from picks');
  }
});

test('Calm Down keeps both album recordings, and a duet query does not return the solo', () => {
  const rows = search('Calm Down Rave & Roses Ultra').tracks;
  assert.deepEqual(Array.from(rows, t => [t.duration,t.catalogTrackId]), [
    [220,'d432510347:1:4'], [239,'d432510347:1:22'],
  ]);
  assert.equal(find.credits(rows[0]), 'Rema');
  assert.equal(find.credits(rows[1]), 'Rema & Selena Gomez');
  assert.equal(rows[1].artist, 'Rema', 'Display must not change the stored recording key');
  const duet = search('Calm Down Selena Gomez');
  assert.deepEqual(Array.from(duet.songs, t => t.duration), [239,239]);
  assert.equal(duet.curatedHits[0].id, '1873297197');
  assert.equal(duet.tracks[0].catalogTrackId, 'd432510347:1:22');
});

test('Title, artist, release and punctuation normalize without fuzzy substitutions', () => {
  const query = 'Black Sherif — The Homeless Song (Official Audio)';
  assert.equal(search(query).songs[0].catalogTrackId, 'd363110777:1:1');
  assert.equal(search('Me & U Tems').tracks[0].catalogTrackId, 'd596953982:1:15');
  assert.equal(search('Espresso Short n’ Sweet').songs[0].catalogTrackId, '1752214909:1:7');
  assert.equal(search('Tyla Wate', 7).songs[0].track, 'Water');
  assert.equal(search('zzzz no such recording').songs.length, 0);
  assert.equal(search('Joro Beyoncé').songs.length, 0);
});

test('An exact artist retains its album catalogue, and Unicode titles remain searchable', () => {
  const artist = search('Black Sherif');
  assert.equal(artist.artistExact.name, 'Black Sherif');
  assert(artist.albums.some(a => a.title === 'IRON BOY'));
  const album = search('Short n Sweet');
  assert.equal(album.exactAlbum.id, '1752214909');
  assert.equal(album.exactAlbum.rawTracks[6][0], 'Espresso');
  assert.equal(search('オリオン YOASOBI').songs[0].track, 'オリオン');
  assert.equal(search('!?!?').songs.length, 0);
});
