# TACIT / Safe Scala demos

Local, runnable companions to the two artifacts:
- "Making AI Agents Safe With Scala" (talk brief + worked examples)
- "TACIT Over Spring Boot" (the Spring Boot integration)

Everything here was actually compiled/run, not just sketched — this file is the
exact command sequence to reproduce it.

## Prerequisites

```bash
# Java 17 + Maven (for the Spring Boot server)
java -version
mvn -version

# scala-cli (for every .scala example)
brew install scala-cli

# opa (for the policy comparison in section 3)
brew install opa
```

## Layout

```
tacit-demo-bank/
  verify-mcp-server.sh    Scripted MCP handshake against the running server
tacit-mcp/
  verify-tacit-server.sh  Scripted calls to evaluateScala: good, bad, blocked-import
capture-demo/
  run-all-examples.sh     Runs bank_good, compiles bank_bad, runs the OPA comparison
opa-demo/
  bank.rego         Same access rule as bank_good/bank_bad, as a Rego policy
  sessions.json     Trusted session->customer table (data, not caller input)
  input-good.json   Scenario matching bank_good (allow)
  input-bad.json    Scenario matching bank_bad (deny)
```

All scripts are plain `bash` files — run them with `./script.sh`, not by
pasting their contents into a terminal.

---

## 1. Run the mock Spring Boot MCP server

```bash
cd tacit-demo-bank
mvn -DskipTests clean package
java -jar target/tacit-demo-bank-0.1.0.jar
```

Starts on `localhost:8081`, exposes two MCP tools over mock data:
`getUserDetails(userId)` and `listRecentTransactions(userId)`.
Mock users: `u-1001` (Jordan Lee), `u-1002` (Priya Nair).

Leave this running in its own terminal — `bank_good.scala` in section 2 and the
`tacit-mcp` server in section 4 both depend on it (section 3's OPA comparison
doesn't; it never touches the server).

### Verify it manually (optional)

The server uses the legacy SSE transport: `GET /sse` hands back a session-scoped
`POST /mcp/message?sessionId=...` to actually talk to. `verify-mcp-server.sh`
does the full handshake (initialize, tools/list, tools/call) for you:

```bash
./verify-mcp-server.sh
```

---

## 2. Capture-checking examples

All run from `capture-demo/`. Each pair is one working example (`run`) and one
deliberate capability leak that should fail to compile (`compile`).

```bash
cd capture-demo
```

| File | Command | What it shows |
|---|---|---|
| `probe.scala` | `scala-cli run probe.scala` | A capability granted in a scope, used safely inside it |
| `leak.scala` | `scala-cli compile leak.scala` | Same capability, smuggled out via a closure — **rejected** |
| `mic_good.scala` | `scala-cli run mic_good.scala` | A `Mic^` capability used while "on air" |
| `mic_bad.scala` | `scala-cli compile mic_bad.scala` | A note that claims to need nothing but still touches the mic — **rejected** |
| `api_good.scala` | `scala-cli run api_good.scala` | A verifying agent (pure) + an orchestrating agent (holds the API capability) |
| `api_bad.scala` | `scala-cli compile api_bad.scala` | The verifier tries to smuggle in the API capability — **rejected** |
| `role_bad.scala` | `scala-cli compile role_bad.scala` | A "role-agnostic" helper secretly captures a role-specific `Dataset^` — **rejected** |
| `bank_good.scala` | `scala-cli run bank_good.scala` | **Needs the server from step 1 running.** Real MCP calls, `Classified` wrapping, pure transforms, declassify |
| `bank_bad.scala` | `scala-cli compile bank_bad.scala` | A "risk check" secretly calls `BankApi` to snoop on a different customer — **rejected** |

`run-all-examples.sh` runs the bank pair *and* the OPA comparison in one
pass (server must already be up) — no copy-pasting multi-line loops into a
terminal. The other examples above are still there to run individually if
you want them.

```bash
./run-all-examples.sh
```

Each step prints what it's actually doing before the raw tool output —
for `bank_bad`, that means the declared signature, what it secretly calls
instead, and the expected result, so the compiler error below it makes
sense without re-reading the source.

### Expected output, `bank_good.scala`

```
getUserDetails(u-1001)   -> Classified(***)
  pure check result      -> Classified(***)
listRecentTransactions() -> Classified(***)
  pure check result      -> Classified(***)

--- proof the pipeline actually ran against the live server ---
  real user record       -> User(u-1001,Jordan Lee,jordan.lee@acmebank.test,521-04-9981)
  is acme address?       -> true
  debit count            -> 2
```

### Expected error, `bank_bad.scala`

```
error: ./bank_bad.scala:17:3
Found:    (user: User) ->{api} Boolean
Required: User -> Boolean

Note that capability `api` cannot flow into capture set {}.
```

---

## 3. Same boundary, as an OPA policy

`opa-demo/bank.rego` encodes the identical rule as a Rego policy instead of
a Scala type: a lookup is only allowed when the requested customer matches
the one the current session was actually authorized for.

The authorized customer is **not** taken from the caller's input — that
was the first draft, and it was a real hole: nothing stopped a caller from
just writing whatever `authorized_user_id` made the request look valid.
`bank.rego` instead looks it up in `sessions.json`, a trusted `data`
document loaded alongside the policy (OPA's `data` is reference
information the policy owner controls; `input` is the untrusted,
per-request JSON the caller sends). Run directly:

```bash
cd opa-demo
opa eval -d bank.rego -d sessions.json -i input-good.json "data.bank.authz.allow"   # -> true
opa eval -d bank.rego -d sessions.json -i input-bad.json "data.bank.authz.allow"    # -> false
```

Proof the fix actually closes the hole — a caller tries to directly claim
`authorized_user_id` in its own request and is ignored:

```bash
echo '{"tool":"getUserDetails","session_id":"sess-1001","requested_user_id":"u-1002","authorized_user_id":"u-1002"}' > /tmp/attack.json
opa eval -d bank.rego -d sessions.json -i /tmp/attack.json "data.bank.authz.allow"   # -> false
```

Same verdict as the compiler on both scenarios — but the remaining trust
boundary didn't disappear, it narrowed: whatever creates `session_id` in
the first place still has to get that right, every session. TACIT has no
equivalent gap at all, because it reads the real function body instead of
trusting any report about it. `run-all-examples.sh` above runs the
session-backed version of both scenarios.

---

## 4. TACIT as its own MCP server

Everything above runs the `BankApi`/`Classified` facade as a standalone
`scala-cli` script. `tacit-mcp/` is the real-world shape instead: a
separate, long-lived service — itself an MCP server — exposing exactly
**one** tool, `evaluateScala`. The agent never sees `getUserDetails` or
`listRecentTransactions` as tools at all; it only ever submits Scala code,
which gets wrapped around the facade and compiled fresh, with capture
checking on, before anything runs. Unlike `bank_good.scala`, the facade
here has **no declassify path whatsoever** — not even for debugging.

```bash
cd tacit-mcp
mvn -DskipTests clean package
java -jar target/tacit-mcp-0.1.0.jar
```

Starts on `localhost:8090`. Needs the mock bank server from section 1
running too — it's the only thing `evaluateScala`'s facade is allowed to
reach.

```bash
./verify-tacit-server.sh
```

Three real calls over the live MCP protocol:

1. **Legitimate** — `withBankApi { api => println(api.getUserDetails("u-1001").preview) }` → compiles, really calls the bank server, returns `Classified(***)`.
2. **Capability leak** — a closure capturing `api` assigned to a `User -> Boolean` val → rejected with the same `Found: ...{api} Boolean` / `Required: User -> Boolean` error as `bank_bad.scala`, now coming back through a live tool call.
3. **Blocked import** — `import java.io.File` → rejected by the code validator before compilation is even attempted.

### Connect it from Claude Code

```bash
claude mcp add --transport sse tacit http://localhost:8090/sse -s user
```

A fresh `claude` session (any directory, with `-s user`) will see one tool,
`evaluateScala`, and nothing else — no direct path to the bank server, no
matter what the agent asks for.

---

## Cleanup

```bash
# stop both Spring Boot servers
pkill -f tacit-demo-bank-0.1.0.jar
pkill -f tacit-mcp-0.1.0.jar

# optionally, unregister from Claude Code
claude mcp remove tacit -s user
claude mcp remove acme-bank -s user   # only if you also registered it there
```
