"""Validate discovered/completed tests against a reviewed manifest and emit JUnit XML."""
import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("mode", choices=["discovery", "results"])
parser.add_argument("log", type=Path)
parser.add_argument("--filter", default="")
parser.add_argument("--manifest", type=Path, default=Path(__file__).with_name("instrumented-tests.txt"))
args = parser.parse_args()
expected = set(args.manifest.read_text(encoding="utf-8-sig").splitlines()) - {""}
if args.filter:
    filters = args.filter.split(",")
    expected = {t for t in expected if any(t == f or t.startswith(f + "#") for f in filters)}
if not expected:
    sys.exit("No expected tests selected")
text = args.log.read_text(encoding="utf-8-sig", errors="replace")
events = []
fields = {}
for line in text.splitlines():
    match = re.match(r"INSTRUMENTATION_STATUS: ([^=]+)=(.*)", line)
    if match:
        fields[match[1]] = match[2]
    elif line.startswith("INSTRUMENTATION_STATUS_CODE:"):
        if "class" in fields and "test" in fields:
            events.append((fields["class"] + "#" + fields["test"], int(line.split(":", 1)[1]), dict(fields)))
        fields = {}
    elif "stack" in fields:
        fields["stack"] += "\n" + line
discovered = {name for name, code, _ in events if code == 1}
missing, extra = expected - discovered, discovered - expected
if missing or extra:
    sys.exit(f"Discovery mismatch. Missing: {sorted(missing)}; unexpected: {sorted(extra)}")
if args.mode == "discovery":
    print(f"Discovery verified: {len(expected)} tests")
    sys.exit(0)
completed = {name: (code, fields) for name, code, fields in events if code != 1}
root = ET.Element("testsuite", name="AnaAlarm instrumentation", tests=str(len(expected)))
failed = 0
for name in sorted(expected):
    class_name, method = name.split("#", 1)
    case = ET.SubElement(root, "testcase", classname=class_name, name=method)
    code, fields = completed.get(name, (-1, {"stack": "Test did not complete"}))
    if code != 0:
        failed += 1
        ET.SubElement(case, "failure", message=f"Instrumentation status {code}").text = fields.get("stack", fields.get("stream", "Skipped required test"))
root.set("failures", str(failed))
root.set("skipped", "0")
ET.ElementTree(root).write(args.log.with_suffix(".xml"), encoding="utf-8", xml_declaration=True)
verdict = re.search(r"^OK \((\d+) tests?\)$", text, re.MULTILINE)
if failed or not verdict or int(verdict[1]) != len(expected):
    sys.exit(f"Required suite failed: {failed} failed/incomplete/skipped tests")
print(f"Verified {len(expected)} passing tests; zero skips")
