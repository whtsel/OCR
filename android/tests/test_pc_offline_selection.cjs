/* The port must choose the same ID as the shipped desktop selector for the same listing. */
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');

const src = path.join(__dirname, '../app/src');
const app = fs.readFileSync(path.join(src, 'main/assets/v90/app.js'), 'utf8');
const companion = path.join(src, 'musicHelper/res/raw/catalogue_music.py');
const desktop = vm.runInNewContext(['musicTextNorm', 'musicWords', 'validPlaybackId', 'bestFullTrackResult'].map(name => {
  const start = app.indexOf(`function ${name}(`);
  assert(start >= 0, name);
  const next = app.slice(start + 9).search(/(?:async )?function [A-Za-z_$][\w$]*\(/);
  assert(next >= 0, name);
  return app.slice(start, start + 9 + next);
}).join('\n') + '\nbestFullTrackResult');

const row = {track:'Low Orbit', artist:'North Signal', album:'Night Transit', duration:183};
const good = {id:'WINNER00001', title:'Low Orbit', channel:'North Signal - Topic', duration:218};
const ties = [{...good, id:'FIRST000001'}, {...good, id:'SECOND00001'}];
const not = (title, duration = 183, channel = good.channel) => ({id:'REJECT00001', title, duration, channel});
const cases = [
  ['no album tag and within the desktop duration window', row, [good], good.id],
  ['exclude the cover and radio edit', row, [not('Low Orbit (Cover)'), not('Low Orbit (Radio Edit)'), good], good.id],
  ['reject unexpected live uploads, mixes, or out-of-range runtimes', row,
    [not('Low Orbit (Live)'), not('Low Orbit remix'), not('Low Orbit', 91), not('Low Orbit', 254)], ''],
  ['artist is required when it has usable words', row, [not('Low Orbit', 183, 'Another artist')], ''],
  ['title coverage is required', {...row, track:'Low Orbit Over Mars'}, [good], ''],
  ['ties keep the provider order', row, ties, ties[0].id],
  ['the exact title beats a noisier result', row, [{...good, id:'OTHER000001', title:'Low Orbit (Official Audio)'}, good], good.id],
  ['allow an explicitly requested remix, not the same title with another label', {...row, track:'Low Orbit Remix'},
    [not('Low Orbit'), {...good, title:'Low Orbit Remix', duration:198}], good.id],
  ['missing duration in the listing is not a ranking rejection', row, [{...good, duration:0}], good.id],
  ['reject invalid video IDs', row, [{...good, id:'invalid'}], ''],
  ['desktop cannot tokenize some non-Latin titles', {...row, track:'夜の便り'}, [{...good, title:'夜の便り'}], ''],
];

test('Offline matches the actual v90 PC selector on provider-shaped listings', () => {
  const input = cases.map(([, request, entries]) => [entries, request.track, request.artist, request.duration]);
  const py = spawnSync('python3', ['-B', '-c',
    'import json, runpy, sys; pick = runpy.run_path(sys.argv[1])["pc_pick"]; print(json.dumps([pick(*x) for x in json.load(sys.stdin)]))', companion],
  {input:JSON.stringify(input), encoding:'utf8'});
  assert.equal(py.status, 0, py.stderr);
  const actual = JSON.parse(py.stdout);
  cases.forEach(([name, request, entries, expected], i) => {
    assert.equal(desktop(entries, request)?.ytId || '', expected, name);
    assert.equal(actual[i], expected, name);
  });
});
