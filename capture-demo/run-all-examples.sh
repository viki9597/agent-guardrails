#!/bin/bash
# Runs the bank capture-checking examples, then the equivalent OPA policy,
# against the same two scenarios.
#
# bank_good.scala needs the mock Spring Boot MCP server from
# tacit-demo-bank/ running on localhost:8081 first.
# The OPA section needs `opa` installed: brew install opa

cd "$(dirname "$0")"

echo "=== working example ==="
echo "--- run bank_good ---"
echo "  legitimate call  : getUserDetails(\"u-1001\")  ->  fetches only the customer it was asked about"
echo ""
scala-cli run bank_good.scala

echo
echo "=== deliberate capability leak (expect rejection) ==="
echo "--- compile bank_bad (expect rejection) ---"
echo "  declared as      : riskCheck(user: User) -> Boolean   (pure — no capability allowed in the body)"
echo "  actually calls   : api.getUserDetails(\"u-1002\")       (a DIFFERENT customer than the one being evaluated)"
echo "  expected result  : compiler catches the mismatch and refuses to compile — u-1002 is never contacted"
echo ""
scala-cli compile bank_bad.scala

echo
echo "=== same boundary, as an OPA policy ==="
if ! command -v opa &> /dev/null; then
  echo "  opa not found — install it with: brew install opa"
else
  echo "--- opa eval: input-good.json ---"
  echo "  policy            : ../opa-demo/bank.rego  (package bank.authz)"
  echo "  data              : ../opa-demo/sessions.json  — trusted session->customer table, NOT from the caller"
  echo "  session_id        : sess-1001   ->  data.sessions[\"sess-1001\"] = u-1001  (the real authorized customer)"
  echo "  requested_user_id : u-1001      (what this specific tool call is actually asking about)"
  echo "  rule checked      : data.sessions[input.session_id] == input.requested_user_id"
  echo "  expected result   : allow = true    (requested MATCHES the session's real authorized customer)"
  echo ""
  opa eval -d ../opa-demo/bank.rego -d ../opa-demo/sessions.json -i ../opa-demo/input-good.json "data.bank.authz.allow" --format pretty

  echo
  echo "--- opa eval: input-bad.json ---"
  echo "  policy            : ../opa-demo/bank.rego  (package bank.authz)"
  echo "  data              : ../opa-demo/sessions.json  — trusted session->customer table, NOT from the caller"
  echo "  session_id        : sess-1001   ->  data.sessions[\"sess-1001\"] = u-1001  (the real authorized customer)"
  echo "  requested_user_id : u-1002      (what this specific tool call is actually asking about — DIFFERENT!)"
  echo "  rule checked      : data.sessions[input.session_id] == input.requested_user_id   ->  u-1001 != u-1002"
  echo "  expected result   : allow = false   (same leak as bank_bad.scala — the caller can't just claim"
  echo "                       u-1002 is authorized, because allow is never looking at a claim it makes;"
  echo "                       it looks up the real answer itself from the trusted session table)"
  echo ""
  opa eval -d ../opa-demo/bank.rego -d ../opa-demo/sessions.json -i ../opa-demo/input-bad.json "data.bank.authz.allow" --format pretty
fi
