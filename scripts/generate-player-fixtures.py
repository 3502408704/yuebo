"""Generate synthetic playback QA assets; no downloaded or copyrighted media.

Usage: rtk python scripts/generate-player-fixtures.py --ffmpeg <ffmpeg.exe>
The host encoder is a test tool only and is never packaged in the app.
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import struct


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    args = parser.parse_args()
    if not args.ffmpeg:
        parser.error("Pass --ffmpeg with the path to a host FFmpeg executable")
    out = Path(__file__).resolve().parents[1] / "build" / "player-fixtures"
    out.mkdir(parents=True, exist_ok=True)

    def encode(*options):
        subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *options],
                       cwd=out, check=True)

    for filename, codec in [("tone.wav", "pcm_s16le"), ("tone.wv", "wavpack"),
                            ("tone.tta", "tta"), ("tone.wma", "wmav2"),
                            ("tone.m4a", "alac"), ("tone.flac", "flac")]:
        encode("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
               "-ac", "2", "-c:a", codec, filename)
    encode("-f", "lavfi", "-i", "color=c=blue:s=64x64", "-frames:v", "1", "cover.jpg")
    encode("-i", "tone.flac", "-i", "cover.jpg", "-map", "0:a", "-map", "1:v",
           "-c", "copy", "-disposition:v", "attached_pic", "cover.flac")
    # DSF stereo DSD64 silence (0x96 LSBF), based on the FFmpeg dsf demuxer layout.
    samples = 2_822_400 * 6
    payload = bytes([0x96]) * (((samples // 8 + 4095) // 4096) * 4096 * 2)
    (out / "silence.dsf").write_bytes(
        b"DSD " + struct.pack("<QQQ", 28, 92 + len(payload), 0)
        + b"fmt " + struct.pack("<QIIIIIIQII", 52, 1, 0, 2, 2, 2_822_400, 1, samples, 4096, 0)
        + b"data" + struct.pack("<Q", 12 + len(payload)) + payload)
    encode("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=25:duration=12",
           "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=12",
           "-c:v", "libx264", "-preset", "ultrafast", "-g", "50", "-sc_threshold", "0",
           "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-shortest", "stream.mp4")
    encode("-i", "stream.mp4", "-t", "6", "-c:v", "wmv2", "-c:a", "wmav2", "legacy.wmv")
    for folder in ("hls", "encrypted", "dash"):
        (out / folder).mkdir(exist_ok=True)
    encode("-i", "stream.mp4", "-c", "copy", "-hls_time", "2", "-hls_list_size", "0",
           "-hls_segment_filename", "hls/seg%d.ts", "hls/index.m3u8")
    (out / "encrypted" / "key").write_bytes(bytes(range(16)))
    # Deterministic public test key, not an application credential.
    (out / "encrypted" / "key-info").write_text(
        "key\nencrypted/key\n00000000000000000000000000000001\n", encoding="ascii")
    encode("-i", "stream.mp4", "-c", "copy", "-hls_time", "2", "-hls_list_size", "0",
           "-hls_key_info_file", "encrypted/key-info", "-hls_segment_filename",
           "encrypted/seg%d.ts", "encrypted/index.m3u8")
    encode("-i", "stream.mp4", "-c", "copy", "-f", "dash", "-seg_duration", "2",
           "dash/index.mpd")
    print(f"Generated synthetic fixtures: {out}")


if __name__ == "__main__":
    main()
