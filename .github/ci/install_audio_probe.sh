#!/usr/bin/env bash
# CI runners do not all include FFmpeg and apt mirror availability varies.
set -euo pipefail
if command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null; then
  exit 0
fi
out="$RUNNER_TEMP/blackbox-audio-probe"
mkdir "$out"
cd "$out"
tarball=$(npm pack --silent @ffmpeg-installer/linux-x64@4.1.0)
tar -xOf "$tarball" package/ffmpeg > ffmpeg
tarball=$(npm pack --silent @ffprobe-installer/linux-x64@5.2.0)
tar -xOf "$tarball" package/ffprobe > ffprobe
chmod u+x ffmpeg ffprobe
printf '%s\n' "$out" >> "$GITHUB_PATH"
