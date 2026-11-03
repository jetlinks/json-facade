#!/usr/bin/env python3
"""Validate Spring DataBuffer streams and closed owners under bounded heap/direct memory."""
import argparse
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = runpy.run_path(str(ROOT / "scripts" / "run-streaming-stress.py"))
M2 = BASE["M2"]


def classpath():
    paths = [ROOT / "json-facade-spring" / "target" / name for name in ("classes", "test-classes")]
    paths += [M2 / "io" / "netty" / name / "4.1.138.Final" / f"{name}-4.1.138.Final.jar"
              for name in ("netty-buffer", "netty-common")]
    paths += [M2 / "org" / "springframework" / name / "6.2.19" / f"{name}-6.2.19.jar"
              for name in ("spring-core", "spring-jcl")]
    for path in paths:
        if not path.exists():
            raise SystemExit(f"Missing build/dependency: {path}")
    return os.pathsep.join([BASE["classpath"]()] + list(map(str, paths)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mib", type=int, default=1024)
    parser.add_argument("--engines", nargs="+", choices=["2", "3"], default=["2", "3"])
    parser.add_argument("--factories", nargs="+", choices=["heap", "direct", "netty-heap", "netty-direct"],
                        default=["heap", "direct", "netty-heap", "netty-direct"])
    parser.add_argument("--chunks", nargs="+", type=int, default=[8192])
    parser.add_argument("--modes", nargs="+", choices=["array-read", "selected-read", "array-write", "document-copy"],
                        default=["array-read", "selected-read", "array-write", "document-copy"])
    parser.add_argument("--release-only", action="store_true")
    args = parser.parse_args()
    for engine in args.engines:
        for factory in args.factories:
            for chunk in ([8192] if args.release_only else args.chunks):
                for mode in (["closed-resources"] if args.release_only else args.modes):
                    subprocess.run([
                        os.environ.get("JSON_STRESS_JAVA", "java"), "-Xmx128m", "-XX:MaxDirectMemorySize=32m",
                        "-Dio.netty.allocator.numDirectArenas=1", "-Dio.netty.allocator.numHeapArenas=1",
                        "-Dio.netty.allocator.maxOrder=3", "-Dio.netty.allocator.smallCacheSize=0",
                        "-Dio.netty.allocator.normalCacheSize=0", "-cp", classpath(),
                        "org.jetlinks.json.spring.DataBufferStreamingStress", engine, mode, factory,
                        str(args.mib), str(chunk)], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
