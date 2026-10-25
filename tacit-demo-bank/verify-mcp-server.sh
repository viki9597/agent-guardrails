#!/bin/bash
# Manually verifies the mock bank MCP server is up and working, using the
# real MCP handshake over the legacy SSE transport.
# Requires the server running first: java -jar target/tacit-demo-bank-0.1.0.jar

BASE_URL="http://localhost:8081"

curl -sN "$BASE_URL/sse" > /tmp/sse.log &
SSE_PID=$!
sleep 1

MSG_PATH=$(grep "data:/mcp/message" /tmp/sse.log | head -1 | sed 's/^data://')
echo "message endpoint: $MSG_PATH"

curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"demo-client","version":"0.1"}}}' > /dev/null

curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null

curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > /dev/null

curl -s -X POST "$BASE_URL$MSG_PATH" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getUserDetails","arguments":{"userId":"u-1001"}}}' > /dev/null

sleep 1
echo "--- responses (arrive over the SSE stream, not the POST output) ---"
cat /tmp/sse.log

kill "$SSE_PID" 2>/dev/null
