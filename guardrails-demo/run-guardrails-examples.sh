#!/bin/bash
# Proves the "provenance label vs. real enforcement" gap identified when
# reviewing the agent guardrails architecture.

cd "$(dirname "$0")"

echo "=== 1. guardrails_bad.scala — a label alone, nothing stops misuse ==="
echo "  Tainted.raw is public — the planner just reaches in and uses the"
echo "  untrusted value directly as the index, the sensitive position."
echo "  expected result  : compiles and runs. That's the bug."
echo
scala-cli run guardrails_bad.scala

echo
echo "=== 2. guardrails_good.scala — real enforcement: no unwrap, one approval path ==="
echo "  Tainted has no public unwrap. IndexApproval.approve is the only"
echo "  sanctioned way out, checked against a fixed allowlist."
echo "  expected result  : approved index succeeds; disallowed index is denied — same code path"
echo
scala-cli run guardrails_good.scala

echo
echo "=== 3. guardrails_reject.scala — bypassing approval entirely (expect rejection) ==="
echo "  Tries to hand a Tainted[String] straight to query's index: String parameter,"
echo "  skipping IndexApproval altogether."
echo "  expected result  : fails to compile — Tainted[String] does not satisfy String"
echo
scala-cli compile guardrails_reject.scala

echo
echo "=== 4. simulation.scala — the full pipeline, four scenarios ==="
echo "  Walks a request through every layer: Quarantined processor -> Specialist"
echo "  planner -> TACIT -> Trusted tool wrappers -> Progent -> Elasticsearch."
echo "  Each scenario shows a DIFFERENT layer catching a different problem."
echo
scala-cli run simulation.scala
