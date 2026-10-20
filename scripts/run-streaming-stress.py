#!/usr/bin/env python3
"""Run independent bounded-heap JVMs; stdout is newline-delimited machine-readable evidence."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
M2 = Path(os.environ.get("M2_REPO", Path.home() / ".m2" / "repository"))
DEPS = [
    "com/fasterxml/jackson/core/jackson-databind/2.22.3/jackson-databind-2.22.3.jar",
    "com/fasterxml/jackson/core/jackson-core/2.22.3/jackson-core-2.22.3.jar",
    "com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar",
    "tools/jackson/core/jackson-databind/3.0.3/jackson-databind-3.0.3.jar",
    "tools/jackson/core/jackson-core/3.0.3/jackson-core-3.0.3.jar",
    "io/projectreactor/reactor-core/3.7.18/reactor-core-3.7.18.jar",
    "org/reactivestreams/reactive-streams/1.0.4/reactive-streams-1.0.4.jar",
]


def classpath():
    classes = [ROOT / name / "target" / "classes" for name in (
        "json-facade-api", "json-facade-jackson2", "json-facade-jackson3", "json-facade-reactor")]
    classes.append(ROOT / "json-facade-reactor" / "target" / "test-classes")
    return os.pathsep.join(map(str, classes + [M2 / path for path in DEPS]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mib", type=int, default=1024)
    parser.add_argument("--heaps", nargs="+", type=int, default=[128, 256])
    parser.add_argument("--engines", nargs="+", choices=["2", "3"], default=["2", "3"])
    parser.add_argument("--chunks", nargs="+", type=int, default=[8192])
    parser.add_argument("--jfr-dir", type=Path)
    parser.add_argument("--modes", nargs="+", default=[
        "array-read", "ndjson-read", "wrapped-read", "array-write", "ndjson-write", "document-copy"])
    args = parser.parse_args()
    for heap in args.heaps:
        for engine in args.engines:
            for chunk in args.chunks:
                for mode in args.modes:
                    recording = []
                    if args.jfr_dir:
                        args.jfr_dir.mkdir(parents=True, exist_ok=True)
                        path = args.jfr_dir.resolve() / f"{engine}-{mode}-{heap}m-{chunk}.jfr"
                        recording = [
                            f"-XX:StartFlightRecording=settings=profile,filename={path},dumponexit=true",
                            "-Xlog:jfr+startup=off",
                        ]
                    subprocess.run([
                        os.environ.get("JSON_STRESS_JAVA", "java"), f"-Xmx{heap}m",
                        *recording,
                        "-cp", classpath(), "org.jetlinks.json.reactor.StreamingStress",
                        engine, mode, str(args.mib), str(chunk)], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
