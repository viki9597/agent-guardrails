#!/bin/bash
# Runs every case in src/main/resources/test-cases.json through the live
# Camel route and prints one row per case. Needs the app running:
#   java -jar target/guardrails-camel-0.1.0.jar
#
# Usage: ./run-test-cases.sh        all cases
#        ./run-test-cases.sh 5      only case 5

BASE_URL="http://localhost:8095"
QUERY=""
[ -n "$1" ] && QUERY="?only=$1"

echo "Running test cases (cases 1-4 and 10-11 call Claude, so this takes a few minutes)..."
curl -s --max-time 1200 -X POST "$BASE_URL/api/test-cases/run$QUERY" | python3 -c '
import json, sys
raw = sys.stdin.read()
try:
    results = json.loads(raw)
except ValueError:
    results = None
if not isinstance(results, list):
    print("Server did not return test results:")
    print(raw[:2000] or "(empty response: is the app running on port 8095?)")
    sys.exit(2)
row = "{:<3} {:<44} {:<38} {:<38} {}"
print()
print(row.format("#", "Case", "Expected", "Actual", "Result"))
print("-" * 132)
for r in results:
    print(row.format(r["number"], r["name"], r["expect"], r["actual"], "PASS" if r["pass"] else "FAIL"))
    print("    why  : " + r["why"])
    if r["turn"].get("taskOverride") is None:
        for p in r["turn"]["parts"]:
            print("    {} {:<14} {}".format("✓ answered" if p["answered"] else "✗ dropped ", p["label"], p["text"]))
    for s in r["turn"]["trace"]:
        print("    {:<5} {:<13} {}".format(s["verdict"], s["layer"], s["detail"]))
    print()
passed = sum(r["pass"] for r in results)
print("-" * 132)
print(f"{passed}/{len(results)} passed")
sys.exit(0 if passed == len(results) else 1)
'
