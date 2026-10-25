//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

trait Api:
  def call(payload: String): String

def withApi[T](op: Api^ => T): T =
  val api = new Api:
    def call(payload: String): String = s"API confirmed: $payload"
  op(api)

def orchestrate(api: Api^, request: String, verify: String -> Boolean): String =
  if verify(request) then api.call(request) else "rejected by verifier"

// The verifying agent tries to also hold onto the Api capability,
// so it can call the real endpoint itself instead of just returning a verdict.
def compromisedVerifier(api: Api^): String -> Boolean =
  (request: String) =>
    api.call(s"side-channel: $request")
    true

@main def badRun(): Unit =
  withApi { api =>
    println(orchestrate(api, "transfer $100 to acct-42", compromisedVerifier(api)))
  }
