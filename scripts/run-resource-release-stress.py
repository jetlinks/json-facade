#!/usr/bin/env python3
"""Retain closed facades while their former heap/direct buffers exceed each fork's memory limit."""
import os
from pathlib import Path
import runpy
import subprocess

ROOT = Path(__file__).resolve().parents[1]
STRESS = runpy.run_path(str(ROOT / "scripts" / "run-streaming-stress.py"))


def main():
    for engine in ("2", "3"):
        for mode in ("session", "direct", "session-error", "reader", "reader-error", "writer", "writer-error", "queue"):
            subprocess.run([
                os.environ.get("JSON_STRESS_JAVA", "java"), "-Xmx64m", "-XX:MaxDirectMemorySize=32m",
                "-cp", STRESS["classpath"](), "org.jetlinks.json.reactor.ResourceReleaseStress", engine, mode,
            ], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
