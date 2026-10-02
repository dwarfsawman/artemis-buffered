"""Offline evidence for the punktfunk audio investigation; no Android device required.

Runs the repository's actual Java WSOLA reference model, then runs the unmodified
punktfunk jitter/sync/recovery unit tests in a small, dependency-free Rust harness.
Generated files go under out/ by default. Requires python, javac, java and rustc.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import struct
import subprocess
import wave


ROOT = Path(__file__).resolve().parents[1]
JAVA_HARNESS = r"""
package com.limelight.binding.audio;
import java.io.*;
import java.nio.file.*;
public final class ToneProbe {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        for (int frequency : new int[] {440, 3000}) {
            for (double rate : new double[] {1.0, 0.97, 1.03}) {
                WsolaTimeStretcher stretcher = new WsolaTimeStretcher(48000, 2);
                String name = "tone_" + frequency + "_rate_" + rate + ".pcm";
                try (DataOutputStream file = new DataOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(output.resolve(name))))) {
                    for (int packet = 0; packet < 400; packet++) {
                        short[] input = new short[240 * 2];
                        for (int frame = 0; frame < 240; frame++) {
                            short sample = (short)Math.round(12000 * Math.sin(
                                    2 * Math.PI * frequency * (packet * 240 + frame) / 48000));
                            input[frame * 2] = input[frame * 2 + 1] = sample;
                        }
                        for (short sample : stretcher.process(input, rate)) {
                            file.writeShort(Short.reverseBytes(sample));
                        }
                    }
                }
            }
        }
    }
}
"""


def run(command, cwd=None):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed:\n{result.stdout}\n{result.stderr}")
    return result.stdout


def rust_function(source, name):
    match = re.search(rf"^(?:pub )?(?:const )?fn {name}\([^\n]*", source, re.M)
    if match is None:
        raise ValueError(f"Rust helper not found: {name}")
    opening = source.index("{", match.start())
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[match.start():end]


def analyze_period(samples, sample_rate):
    # A 3 kHz input has exactly 15 periods per 5 ms input packet. The output
    # repeats one adjusted packet, allowing an exact one-period DFT, with no
    # FFT window leakage. Other test tones are exported for listening only.
    n = len(samples)
    energy = sum(x * x for x in samples) / n
    lines = []
    for harmonic in range(1, (n - 1) // 2 + 1):
        real = sum(x * math.cos(2 * math.pi * harmonic * i / n)
                   for i, x in enumerate(samples)) / n
        imag = sum(x * math.sin(2 * math.pi * harmonic * i / n)
                   for i, x in enumerate(samples)) / n
        lines.append((2 * (real * real + imag * imag), harmonic * sample_rate / n))
    lines.sort(reverse=True)
    remaining = max(0.0, (energy - lines[0][0]) / energy)
    rms_windows = [math.sqrt(sum(x * x for x in samples[i:i + 48]) / 48)
                   for i in range(n - 48 + 1)]
    return {
        "output_packet_frames": n,
        "output_duration_ms": n * 1000 / sample_rate,
        "largest_spectral_line_hz": round(lines[0][1], 3),
        "power_outside_largest_line_percent": round(remaining * 100, 4),
        "minimum_1ms_rms_vs_input_db": round(
            20 * math.log10(min(rms_windows) / (12000 / math.sqrt(2))), 3),
        "top_spectral_lines": [
            {"hz": round(hz, 3), "percent_total_power": round(power / energy * 100, 4)}
            for power, hz in lines[:4]
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--punktfunk", type=Path, required=True,
                        help="Read-only punktfunk source checkout")
    parser.add_argument("--output", type=Path,
                        default=ROOT / "out" / "punktfunk-audio-research")
    args = parser.parse_args()
    upstream = args.punktfunk.resolve()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    harness = output / "harness"
    harness.mkdir(exist_ok=True)
    java_source = ROOT / "research/legacy-audio/WsolaTimeStretcher.java"
    java_probe = harness / "ToneProbe.java"
    java_probe.write_text(JAVA_HARNESS, encoding="utf-8")
    javac = Path(shutil.which("javac") or "javac").resolve()
    java = javac.with_name("java.exe" if javac.suffix == ".exe" else "java")
    run([str(javac), "-d", str(harness), str(java_source), str(java_probe)])
    run([str(java), "-cp", str(harness), "com.limelight.binding.audio.ToneProbe", str(output)])
    metrics = {}
    for pcm_path in sorted(output.glob("tone_*.pcm")):
        raw = pcm_path.read_bytes()
        with wave.open(str(pcm_path.with_suffix(".wav")), "wb") as file:
            file.setnchannels(2)
            file.setsampwidth(2)
            file.setframerate(48000)
            file.writeframes(raw)
        if "tone_3000_" in pcm_path.name:
            rate = float(pcm_path.stem.split("_rate_")[1])
            packet_frames = math.floor(240 / rate + 0.5)
            packet_bytes = raw[:packet_frames * 4]
            if raw != packet_bytes * 400:
                raise ValueError("3 kHz probe is not periodic; one-period DFT is invalid")
            first_packet = list(struct.unpack(f"<{packet_frames * 2}h",
                                             packet_bytes))[::2]
            metrics[str(rate)] = analyze_period(first_packet, 48000)

    audio = upstream / "crates/punktfunk-core/src/audio"
    shared = (audio / "mod.rs").read_text(encoding="utf-8")
    pcm = (audio / "pcm.rs").read_text(encoding="utf-8")
    helpers = "\n".join(rust_function(shared, name) for name in (
        "interleaved_per_sec", "ms_to_samples", "samples_to_ms"))
    pcm_helpers = "\n".join(rust_function(pcm, name) for name in (
        "rate_is_supported", "samples_per_frame"))
    ladder = re.search(r"^pub const FRAME_US_LADDER:.*;$", pcm, re.M).group()
    constants = "\n".join(re.search(rf"^pub const {name}:.*;$", shared, re.M).group()
                          for name in ("FRAME_MS", "SAMPLE_RATE_HZ"))
    modules = "\n".join(
        f'#[path = "{(audio / (name + ".rs")).as_posix()}"] mod {name};\n'
        f'pub use self::{name}::*;'
        for name in ("jitter", "sync", "recovery"))
    rust_probe = harness / "punktfunk_audio_tests.rs"
    rust_probe.write_text(
        "// Read-only source inclusion; original MIT notice copied beside this harness.\n"
        f"#![allow(dead_code)]\nmod audio {{\n{constants}\n{helpers}\n"
        f"mod pcm {{\n{ladder}\n{pcm_helpers}\n}}\n{modules}\n}}\n",
        encoding="utf-8")
    shutil.copyfile(upstream / "LICENSE-MIT", harness / "LICENSE-MIT")
    rust_exe = harness / "punktfunk_audio_tests.exe"
    run(["rustc", "--edition=2024", "--test", "-O", str(rust_probe), "-o", str(rust_exe)])
    test_log = run([str(rust_exe)])
    (output / "punktfunk-core-tests.log").write_text(test_log, encoding="utf-8")
    result = {
        "local_commit": run(["git", "rev-parse", "HEAD"], ROOT).strip(),
        "punktfunk_commit": run(["git", "rev-parse", "HEAD"], upstream).strip(),
        "java_reference_sha256": hashlib.sha256(java_source.read_bytes()).hexdigest(),
        "upstream_audio_sha256": {
            name: hashlib.sha256((audio / name).read_bytes()).hexdigest()
            for name in ("jitter.rs", "sync.rs", "recovery.rs", "mod.rs", "pcm.rs")
        },
        "scope": "Java reference model tone probe and isolated, unmodified Rust audio policy tests; not Android runtime validation",
        "tone_probe": metrics,
        "punktfunk_tests": next(line for line in test_log.splitlines()
                                if line.startswith("test result:")),
    }
    (output / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
