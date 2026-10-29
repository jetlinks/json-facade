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
    fixtures = parser.add_mutually_exclusive_group()
    fixtures.add_argument("--netty", action="store_true", help="Compare ordinary and pooled Netty paths")
    fixtures.add_argument("--tree", action="store_true", help="Compare chained and fused tree access")
    fixtures.add_argument("--tree-wrap", action="store_true", help="Compare borrowed and copied Map/List trees")
    fixtures.add_argument("--collections", action="store_true", help="Compare JDK, neutral and migration collections")
    fixtures.add_argument("--global-lookup", action="store_true", help="Compare published global and direct references")
    fixtures.add_argument("--iterator", action="store_true", help="Compare configured native/cursor/iterator binding")
    fixtures.add_argument("--events", action="store_true", help="Separate native, Session and Reactor event costs")
    fixtures.add_argument("--provider-read", action="store_true", help="Compare configured native/facade inputs")
    fixtures.add_argument("--published-write", action="store_true", help="Compare independently owned output")
    fixtures.add_argument("--operation-options", action="store_true", help="Compare preparation and operation reuse")
    fixtures.add_argument("--fastjson2", action="store_true", help="Compare native and facade synchronous fastjson2 binding")
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
    if args.fastjson2:
        base_cp = runpy.run_path(str(ROOT / "scripts" / "run-fastjson2-stress.py"))["classpath"]()
        fixture, package = "Fastjson2Benchmark", "org.jetlinks.json"
    elif args.collections:
        fast_cp = runpy.run_path(str(ROOT / "scripts" / "run-fastjson2-stress.py"))["classpath"]()
        base_cp += os.pathsep + fast_cp + os.pathsep + str(ROOT / "json-facade-compat-fastjson" / "target" / "classes")
        fixture, package = "CollectionBenchmark", "org.jetlinks.json"
    elif args.operation_options:
        fixture, package = "OperationOptionsBenchmark", "org.jetlinks.json"
    elif args.global_lookup:
        fixture, package = "GlobalLookupBenchmark", "org.jetlinks.json"
    elif args.tree_wrap:
        fixture, package = "TreeWrapBenchmark", "org.jetlinks.json"
    elif args.provider_read:
        fixture, package = "ProviderReadBenchmark", "org.jetlinks.json"
    elif args.published_write:
        fixture, package = "PublishedWriteBenchmark", "org.jetlinks.json.reactor"
    elif args.iterator:
        fixture, package = "ArrayIteratorBenchmark", "org.jetlinks.json"
    elif args.events:
        fixture, package = "EventPipelineBenchmark", "org.jetlinks.json.reactor"
    elif args.tree:
        fixture, package = "TreeAccessBenchmark", "org.jetlinks.json"
    elif args.netty:
        fixture, package = "NettyStreamBenchmark", "org.jetlinks.json.netty"
    else:
        fixture, package = "StreamBenchmark", "org.jetlinks.json.reactor"
    cp = os.pathsep.join([str(classes), base_cp] + [str(M2 / path) for path in DEPS.values()])
    subprocess.run(["javac", "-cp", cp, "-d", str(classes), "-s", str(generated),
                    str(ROOT / "benchmarks" / f"{fixture}.java")], check=True, cwd=ROOT)
    defaults = [
        ("-f", "2"), ("-wi", "3"), ("-i", "4"), ("-w", "300ms"), ("-r", "300ms"),
        ("-jvmArgs", "-Xms512m -Xmx512m"), ("-prof", "gc"), ("-rf", "json"), ("-rff", args.result),
    ]
    options = [item for flag, value in defaults if flag not in extra for item in (flag, value)]
    multiple = args.tree or args.tree_wrap or args.global_lookup or args.iterator \
        or args.provider_read or args.published_write or args.operation_options or args.fastjson2 or args.events \
        or args.collections
    benchmark = "^" + re.escape(f"{package}.{fixture}") + (r"\..*$" if multiple else r"\.run$")
    subprocess.run(["java", "-cp", cp, "org.openjdk.jmh.Main", benchmark]
                   + options + extra, check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
