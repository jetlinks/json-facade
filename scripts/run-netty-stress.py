#!/usr/bin/env python3
"""Stream generated documents through Netty with independently bounded heap/direct memory."""
import argparse
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = runpy.run_path(str(ROOT / "scripts" / "run-streaming-stress.py"))


def classpath():
    paths = [ROOT / "json-facade-netty" / "target" / name for name in ("classes", "test-classes")]
    paths += [BASE["M2"] / "io" / "netty" / name / "4.1.138.Final" / f"{name}-4.1.138.Final.jar"
              for name in ("netty-buffer", "netty-common")]
    for path in paths:
        if not path.exists():
            raise SystemExit(f"Missing build/dependency: {path}")
    return os.pathsep.join([BASE["classpath"]()] + list(map(str, paths)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mib", type=int, default=1024)
    parser.add_argument("--release-only", action="store_true", help="Retain closed owners under allocation pressure")
    parser.add_argument("--engines", nargs="+", choices=["2", "3"], default=["2", "3"])
    parser.add_argument("--chunks", nargs="+", type=int, default=[8192])
    parser.add_argument("--modes", nargs="+", choices=["array-read", "selected-read", "array-write", "document-copy"],
                        default=["array-read", "selected-read", "array-write", "document-copy"])
    parser.add_argument("--memory", nargs="+", choices=["heap", "direct"], default=["heap", "direct"])
    args = parser.parse_args()
    for engine in args.engines:
        for chunk in args.chunks:
            for memory in args.memory:
                for mode in (["closed-resources"] if args.release_only else args.modes):
                    fixture = "NettyResourceStress" if args.release_only else "NettyStreamingStress"
                    fixture_args = [engine, str(memory == "direct").lower()] if args.release_only else \
                        [engine, mode, str(args.mib), str(chunk), str(memory == "direct").lower()]
                    subprocess.run([
                        os.environ.get("JSON_STRESS_JAVA", "java"), "-Xmx128m", "-XX:MaxDirectMemorySize=32m",
                        "-Dio.netty.allocator.numDirectArenas=1", "-Dio.netty.allocator.numHeapArenas=1",
                        "-Dio.netty.allocator.maxOrder=3", "-Dio.netty.allocator.smallCacheSize=0",
                        "-Dio.netty.allocator.normalCacheSize=0", "-cp", classpath(),
                        f"org.jetlinks.json.netty.{fixture}", *fixture_args], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
