"""Fail closed on missing coverage, or <80% branches in any production module.

Aggregating execution data counts cross-provider TCK tests against the API they
actually execute. No production class or branch is excluded from this report.
"""
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal


def verify(path):
    report = ET.parse(path).getroot()
    expected = {"json-facade-api", "json-facade-jackson2", "json-facade-jackson3", "json-facade-fastjson2",
                "json-facade-reactor", "json-facade-netty", "json-facade-spring"}
    actual = {group.attrib["name"] for group in report.findall("group")}
    if actual != expected:
        raise SystemExit(f"Missing or unexpected coverage modules: {actual ^ expected}")
    failures = []
    for group in report.findall("group"):
        counter = group.find("counter[@type='BRANCH']")
        if counter is None:
            raise SystemExit(f"Missing branch counter: {group.attrib['name']}")
        covered = int(counter.attrib["covered"])
        total = covered + int(counter.attrib["missed"])
        ratio = Decimal(covered) / total if total else Decimal(0)
        print(f"{group.attrib['name']}: {covered}/{total} branches ({ratio:.2%})")
        if ratio < Decimal("0.80"):
            failures.append(group.attrib["name"])
    if failures:
        raise SystemExit(f"Branch coverage below 80%: {', '.join(failures)}")


if __name__ == "__main__":
    verify(sys.argv[1])
