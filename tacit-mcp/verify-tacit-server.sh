#!/bin/bash
# Verifies the TACIT MCP server end to end over the real MCP protocol:
# a legitimate call, a capability-leak attempt (expect compiler rejection),
# and a blocked-import attempt (expect validator rejection).
#
# Requires this server running: java -jar target/tacit-mcp-0.1.0.jar
# Requires the mock bank server too: tacit-demo-bank, localhost:8081

BASE_URL="http://localhost:8090"
LOG="/tmp/tacit_sse.log"

# Pulls the real, human-readable result out of a JSON-RPC response for one
# call id — unescaped, with the noisy upickle version hint dropped.
show_result() {
  python3 -c "
import json, sys
call_id = $1
for line in open('$LOG'):
    line = line.strip()
    if not line.startswith('data:{'):
        continue
    try:
        obj = json.loads(line[5:])
    except Exception:
        continue
    if obj.get('id') == call_id:
        text = obj['result']['content'][0]['text']
        # The tool result is itself JSON-encoded (Spring AI wraps the
        # returned String as a JSON string value) — decode once more so
        # \\n becomes a real newline instead of two literal characters.
        try:
            text = json.loads(text)
        except Exception:
            pass
        for l in text.splitlines():
            if '[hint]' in l or 'upickle' in l:
                continue
            if l.strip() and set(l.strip()) <= set('^'):
                continue
            print(l)
"
}

curl -sN "$BASE_URL/sse" > "$LOG" &
SSE_PID=$!
sleep 1
MSG_PATH=$(grep "data:/mcp/message" "$LOG" | head -1 | sed 's/^data://')

curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"demo-client","version":"0.1"}}}' > /dev/null
curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null

echo "=== TACIT MCP server — the only tool it exposes is evaluateScala ==="
echo "    every call below submits Scala code; nothing else is possible"
echo

echo "--- 1. legitimate call ---"
echo "  submitted code   : withBankApi { api => println(api.getUserDetails(\"u-1001\").preview) }"
echo "  what it does     : asks for customer u-1001's record through the BankApi capability"
echo "  expected result  : compiles, really calls the bank server, prints Classified(***) — never the raw record"
echo
python3 -c '
import json
code = "withBankApi { api =>\n  val u = api.getUserDetails(\"u-1001\")\n  println(u.preview)\n}"
print(json.dumps({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"evaluateScala","arguments":{"code": code}}}))
' > /tmp/tacit_good.json
curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" -d @/tmp/tacit_good.json > /dev/null
sleep 20
echo "  >> what the server actually returned:"
show_result 2 | sed 's/^/     /'
echo

echo "--- 2. capability leak attempt (expect compiler rejection) ---"
echo "  declared as      : riskCheck(u: User) -> Boolean   (pure — no capability allowed in the body)"
echo "  actually calls   : api.getUserDetails(\"u-1002\")    (a DIFFERENT customer than the one being evaluated)"
echo "  expected result  : compiler catches the mismatch and refuses to compile — u-1002 is never contacted"
echo
python3 -c '
import json
code = ("withBankApi { api =>\n"
        "  def riskCheck(u: User): Boolean =\n"
        "    val x = api.getUserDetails(\"u-1002\")\n"
        "    true\n"
        "  val pureFn: User -> Boolean = riskCheck\n"
        "  println(pureFn(User(\"u-1001\",\"x\",\"y\",\"z\")))\n"
        "}")
print(json.dumps({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"evaluateScala","arguments":{"code": code}}}))
' > /tmp/tacit_bad.json
curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" -d @/tmp/tacit_bad.json > /dev/null
sleep 20
echo "  >> what the server actually returned:"
show_result 3 | sed 's/^/     /'
echo

echo "--- 3. blocked import (expect validator rejection, no compile attempted) ---"
echo "  submitted code   : import java.io.File ; new File(\"/etc/passwd\").exists()"
echo "  what it does     : tries to reach the real filesystem directly, bypassing BankApi entirely"
echo "  expected result  : rejected immediately by the code validator — the compiler never even runs"
echo
python3 -c '
import json
code = "import java.io.File\nprintln(new File(\"/etc/passwd\").exists())"
print(json.dumps({"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"evaluateScala","arguments":{"code": code}}}))
' > /tmp/tacit_validator.json
curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" -d @/tmp/tacit_validator.json > /dev/null
sleep 2
echo "  >> what the server actually returned:"
show_result 4 | sed 's/^/     /'

kill "$SSE_PID" 2>/dev/null
