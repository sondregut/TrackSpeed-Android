#!/usr/bin/env python3
"""Verify actual exported MP4s against native preview samples; requires ffmpeg/ffprobe."""
import argparse
import json
from pathlib import Path
import subprocess


def run(*args):
    return subprocess.check_output(args)


def dimensions(path):
    streams = json.loads(run('ffprobe', '-v', 'error', '-show_entries', 'stream=width,height', '-of', 'json', str(path)))['streams']
    return streams[0]['width'], streams[0]['height']


def pixels(path, time=None):
    args = ['ffmpeg', '-v', 'error', '-i', str(path)]
    if time is not None:
        args += ['-ss', str(time)]
    return run(*args, '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'pipe:1')


def verify(folder):
    results = []
    for case in ('portrait', 'landscape', 'social-landscape'):
        reference = folder / f'{case}-after.png'
        video = folder / f'{case}-export.mp4'
        width, height = dimensions(reference)
        expected = pixels(reference)
        actual = pixels(video, 3.5)
        assert len(actual) == len(expected) == width * height * 3, f'{case}: wrong display dimensions'
        total = 0
        tile_totals = [0] * 64
        tile_counts = [0] * 64
        # Sample a regular grid over the entire displayed frame, including footage
        # outside the overlay. Per-tile bounds catch blank/cropped video regions.
        for y in range(0, height, 4):
            for x in range(0, width, 4):
                offset = (y * width + x) * 3
                delta = sum(abs(actual[offset+c] - expected[offset+c]) for c in range(3))
                tile = min(y * 8 // height, 7) * 8 + min(x * 8 // width, 7)
                total += delta
                tile_totals[tile] += delta
                tile_counts[tile] += 3
        mean = total / sum(tile_counts)
        max_tile = max(d / n for d, n in zip(tile_totals, tile_counts) if n)
        assert mean < 8 and max_tile < 25, f'{case}: mean={mean:.3f}, worst tile={max_tile:.3f}'
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(video), '-ss', '3.5', '-frames:v', '1', '-y', str(folder/f'{case}-export-frame.png')], check=True)
        results.append({'case': case, 'display_width': width, 'display_height': height,
                        'mean_channel_error': round(mean, 3), 'worst_tile_channel_error': round(max_tile, 3)})
    (folder/'export-verification.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('samples', type=Path)
    verify(parser.parse_args().samples)
