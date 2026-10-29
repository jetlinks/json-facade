#!/usr/bin/env python3
"""Verify synchronous fastjson2 identity and ownership in an independent bounded heap."""
import argparse
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
M2 = Path(os.environ.get("M2_REPO", Path.home() / ".m2" / "repository"))


def classpath():
    version = ET.parse(ROOT / "pom.xml").findtext("{*}properties/{*}fastjson2.version")
    return os.pathsep.join(map(str, [
        ROOT / "json-facade-api" / "target" / "classes",
        ROOT / "json-facade-fastjson2" / "target" / "classes",
        ROOT / "json-facade-fastjson2" / "target" / "test-classes",
        M2 / f"com/alibaba/fastjson2/fastjson2/{version}/fastjson2-{version}.jar",
    ]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--records", type=int, default=1_000_000)
    parser.add_argument("--heap", type=int, default=32)
    args = parser.parse_args()
    if args.records < 1 or args.heap < 1:
        parser.error("records and heap must be positive")
    subprocess.run([os.environ.get("JSON_STRESS_JAVA", "java"), f"-Xmx{args.heap}m", "-cp", classpath(),
                    "org.jetlinks.json.fastjson2.Fastjson2Stress", str(args.records)], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
