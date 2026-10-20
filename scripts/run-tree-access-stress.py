#!/usr/bin/env python3
"""Retain tree child/absent views and errors while former ancestor storage exceeds the heap."""
import argparse
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STREAM = runpy.run_path(str(ROOT / "scripts" / "run-streaming-stress.py"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engines", nargs="+", choices=["jackson2", "jackson3"],
                        default=["jackson2", "jackson3"])
    args = parser.parse_args()
    classpath = STREAM["classpath"]() + os.pathsep + str(ROOT / "json-facade-tck" / "target" / "classes")
    for engine in args.engines:
        subprocess.run([
            os.environ.get("JSON_STRESS_JAVA", "java"), "-Xmx64m", "-cp", classpath,
            "org.jetlinks.json.tck.JsonTreeAccessStress", engine,
        ], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
