#!/usr/bin/env python3
"""Generate BlackBox's original sample art, MP4, and M4A. Requires Pillow and FFmpeg."""
from argparse import ArgumentParser
from math import atan2, cos, exp, hypot, pi, sin
from pathlib import Path
from random import Random
import subprocess

from PIL import Image, ImageDraw, ImageFont

WIDTH, HEIGHT, FPS, SECONDS = 640, 360, 24, 14
RAW = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'


def make_art(font_dir, frames):
    sky = Image.new('RGB', (WIDTH, HEIGHT))
    cx, cy = 428, 189
    sky.putdata([
        (min(255, int(8 + 16 * (y / HEIGHT) + 19 * exp(-((hypot(x - cx, y - cy)) / 230)**2))),
         min(255, int(14 + 14 * (y / HEIGHT) + 51 * exp(-((hypot(x - cx, y - cy)) / 205)**2))),
         min(255, int(26 + 12 * (y / HEIGHT) + 39 * exp(-((hypot(x - cx, y - cy)) / 215)**2))))
        for y in range(HEIGHT) for x in range(WIDTH)
    ])
    grid = ImageDraw.Draw(sky)
    for x in range(20, WIDTH, 24):
        grid.line((x, 0, x, HEIGHT), fill=(27, 47, 51))
    for y in range(20, HEIGHT, 24):
        grid.line((0, y, WIDTH, y), fill=(27, 47, 51))
    rng = Random(1227)
    for _ in range(126):
        x, y, light = rng.randrange(WIDTH), rng.randrange(237), rng.randrange(70, 149)
        grid.point((x, y), fill=(light - 22, light + 24, light + 12))
    for r in (34, 62, 92, 125, 156):
        grid.ellipse((cx-r, cy-r, cx+r, cy+r), outline=(40, 80, 75))
    for i, (shift, color) in enumerate([(0, (20, 40, 44)), (14, (16, 29, 38)), (42, (11, 20, 30))]):
        ridge = [(x, int(236 + shift + 19 * sin(x / (80 - i * 8) + 2 * i) + 9 * cos(x / 26 + i)))
                 for x in range(0, WIDTH + 1, 3)]
        grid.polygon(ridge + [(WIDTH, HEIGHT), (0, HEIGHT)], fill=color)
    mono = ImageFont.truetype(str(font_dir / 'DejaVuSansMono.ttf'), 12)
    heading = ImageFont.truetype(str(font_dir / 'DejaVuSans-Bold.ttf'), 40)
    smaller = ImageFont.truetype(str(font_dir / 'DejaVuSans.ttf'), 14)
    grid.rounded_rectangle((17, 17, WIDTH - 17, HEIGHT - 17), radius=12, outline=(47, 85, 82))
    grid.text((37, 50), 'BLACKBOX STUDIO   /   001', font=mono, fill=(128, 230, 203))
    grid.text((34, 81), 'Signal at', font=heading, fill=(242, 249, 246))
    grid.text((34, 126), 'Dawn', font=heading, fill=(242, 249, 246))
    grid.text((37, 188), 'An original playback sample', font=smaller, fill=(169, 197, 199))
    grid.line((37, 220, 122, 220), fill=(118, 242, 206), width=2)
    for f in range(frames):
        at = f / FPS
        img = sky.copy()
        d = ImageDraw.Draw(img)
        for i, r in enumerate((34, 62, 92, 125, 156)):
            a = (at * (.26 - i * .021) + 1.2 * i) % (2 * pi)
            # Only satellites above the ridgeline are visible.
            x, y = cx + r * cos(a), cy - r * sin(a)
            if y < 225:
                d.ellipse((x - 2, y - 2, x + 2, y + 2), fill=(164, 252, 224))
        a = atan2(sin(at / 7), cos(at / 7))
        x, y = cx + round(23 * sin(a)), cy - 8
        d.ellipse((x - 25, y - 25, x + 25, y + 25), fill=(48, 109, 99), outline=(131, 231, 207))
        d.polygon(((x - 7, y - 11), (x + 12, y), (x - 7, y + 11)), fill=(187, 252, 227))
        d.text((37, 304), f'ORIGINAL SAMPLE  •  {int(at):02d}s', font=mono, fill=(136, 192, 186))
        d.line((37, 324, WIDTH - 37, 324), fill=(39, 68, 70), width=2)
        d.line((37, 324, 37 + (WIDTH - 74) * f / (frames - 1), 324), fill=(118, 242, 206), width=2)
        yield img.tobytes()


def run(ffmpeg, *args, **kwargs):
    return subprocess.Popen([ffmpeg, '-hide_banner', '-loglevel', 'error', '-y', *args], **kwargs)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--fonts', type=Path, default=Path('/usr/share/fonts/truetype/dejavu'))
    args = parser.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)
    with run(args.ffmpeg, '-threads', '2', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{WIDTH}x{HEIGHT}',
             '-r', str(FPS), '-i', 'pipe:0', '-f', 'lavfi', '-i',
             'aevalsrc=0.06*(sin(2*PI*110*t)+0.35*sin(2*PI*220*t))*sin(PI*t/14)^2:s=44100:d=14',
             '-c:v', 'libx264', '-threads', '2', '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-crf', '24',
             '-g', '24', '-c:a', 'aac', '-b:a', '56k', '-movflags', '+faststart', '-shortest',
             str(RAW / 'dawn.mp4'), stdin=subprocess.PIPE) as ff:
        for frame in make_art(args.fonts, FPS * SECONDS):
            ff.stdin.write(frame)
        ff.stdin.close()
        if ff.wait():
            raise SystemExit('FFmpeg could not create the video sample')
    with run(args.ffmpeg, '-f', 'lavfi', '-i',
             'aevalsrc=0.14*(sin(2*PI*130.81*t)+0.28*sin(2*PI*196*t)+0.16*sin(2*PI*261.63*t))*(0.77+0.23*sin(2*PI*0.8*t))*sin(PI*t/24)^2:s=44100:d=24',
             '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', str(RAW / 'orbit.m4a')) as ff:
        if ff.wait():
            raise SystemExit('FFmpeg could not create the audio sample')


if __name__ == '__main__':
    main()
