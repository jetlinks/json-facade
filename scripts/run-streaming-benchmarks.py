#!/usr/bin/env python3
"""Compile and run the opt-in JMH fixture against current module classes."""
import argparse
import os
from pathlib import Path
import re
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STRESS = runpy.run_path(str(ROOT / "scripts" / "run-streaming-stress.py"))
M2 = STRESS["M2"]
DEPS = {
    "org.openjdk.jmh:jmh-core:1.37": "org/openjdk/jmh/jmh-core/1.37/jmh-core-1.37.jar",
    "org.openjdk.jmh:jmh-generator-annprocess:1.37":
        "org/openjdk/jmh/jmh-generator-annprocess/1.37/jmh-generator-annprocess-1.37.jar",
    "net.sf.jopt-simple:jopt-simple:5.0.4": "net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar",
    "org.apache.commons:commons-math3:3.6.1":
        "org/apache/commons/commons-math3/3.6.1/commons-math3-3.6.1.jar",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--result", default=str(ROOT / "target" / "benchmarks" / "results.json"))
    parser.add_argument("--netty", action="store_true", help="Compare ordinary and pooled Netty paths")
    args, extra = parser.parse_known_args()
    for artifact, path in DEPS.items():
        if not (M2 / path).is_file():
            subprocess.run(["mvn", "-B", "-ntp", "dependency:get", f"-Dartifact={artifact}"], check=True, cwd=ROOT)
    classes = ROOT / "target" / "benchmarks" / "classes"
    generated = ROOT / "target" / "benchmarks" / "generated"
    classes.mkdir(parents=True, exist_ok=True)
    generated.mkdir(parents=True, exist_ok=True)
    base_cp = runpy.run_path(str(ROOT / "scripts" / "run-netty-stress.py"))["classpath"]() \
        if args.netty else STRESS["classpath"]()
    fixture = "NettyStreamBenchmark" if args.netty else "StreamBenchmark"
    cp = os.pathsep.join([str(classes), base_cp] + [str(M2 / path) for path in DEPS.values()])
    subprocess.run(["javac", "-cp", cp, "-d", str(classes), "-s", str(generated),
                    str(ROOT / "benchmarks" / f"{fixture}.java")], check=True, cwd=ROOT)
    defaults = [
        ("-f", "2"), ("-wi", "3"), ("-i", "4"), ("-w", "300ms"), ("-r", "300ms"),
        ("-jvmArgs", "-Xms512m -Xmx512m"), ("-prof", "gc"), ("-rf", "json"), ("-rff", args.result),
    ]
    options = [item for flag, value in defaults if flag not in extra for item in (flag, value)]
    package = "netty" if args.netty else "reactor"
    benchmark = f"org.jetlinks.json.{package}.{fixture}.run"
    subprocess.run(["java", "-cp", cp, "org.openjdk.jmh.Main", "^" + re.escape(benchmark) + "$"]
                   + options + extra, check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
