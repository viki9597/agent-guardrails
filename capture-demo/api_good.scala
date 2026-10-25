//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

// Api is a capability: being able to call a real external endpoint.
trait Api:
  def call(payload: String): String

def withApi[T](op: Api^ => T): T =
  val api = new Api:
    def call(payload: String): String = s"API confirmed: $payload"
  op(api)

// The verifying agent: decides whether a request is allowed.
// Its type is plain String -> Boolean — no capability can hide inside it.
def verifyRequest(request: String): Boolean =
  request.startsWith("transfer") && request.length < 50

// The calling agent: the only one ever handed the Api capability.
def orchestrate(api: Api^, request: String, verify: String -> Boolean): String =
  if verify(request) then api.call(request) else "rejected by verifier"

@main def goodRun(): Unit =
  withApi { api =>
    println(orchestrate(api, "transfer $100 to acct-42", verifyRequest))
  }
