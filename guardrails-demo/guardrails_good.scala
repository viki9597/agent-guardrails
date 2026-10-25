//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

// A real taint boundary: no method anywhere returns the raw value. The
// only way out is through an explicit, centralized, auditable approval
// path — IndexApproval below.
object boundary:
  final class Tainted[+T] private (private val value: T):
    def map[U](f: T -> U): Tainted[U] = Tainted.wrap(f(value))

  object Tainted:
    def wrap[T](v: T): Tainted[T] = new Tainted(v)
    // Visible only inside `boundary`, so IndexApproval is the one caller.
    private[boundary] def unwrap[T](t: Tainted[T]): T = t.value

  // The ONLY sanctioned path from "untrusted candidate" to "safe index
  // name" — one function, one place to audit, checked against a fixed
  // allowlist that the candidate itself has no influence over.
  object IndexApproval:
    private val APPROVED: Set[String] = Set("support-tickets", "product-docs")
    def approve(candidate: Tainted[String]): Option[String] =
      val raw = Tainted.unwrap(candidate)
      if APPROVED.contains(raw) then Some(raw) else None

export boundary.{Tainted, IndexApproval}

case class SearchResult(hits: List[String])

trait ElasticsearchApi:
  // index: a plain String, not Tainted[String] — a tainted value
  // structurally cannot be passed here at all, approved or not.
  def query(index: String, queryText: String): SearchResult

final class LiveElasticsearchApi extends ElasticsearchApi:
  def query(index: String, queryText: String): SearchResult =
    SearchResult(List(s"mock hit from '$index' matching '$queryText'"))

def withElasticsearch[T](op: ElasticsearchApi^ => T): T =
  op(new LiveElasticsearchApi)

@main def goodRun(): Unit =
  withElasticsearch { es =>
    // A legitimate candidate — on the allowlist.
    val candidate: Tainted[String] = Tainted.wrap("support-tickets")
    IndexApproval.approve(candidate) match
      case Some(index) =>
        val result = es.query(index, "how do I reset my password")
        println(s"approved -> queried '$index': ${result.hits}")
      case None =>
        println("denied: index not on the approved list")

    // An injection attempt — NOT on the allowlist. Same code path,
    // different outcome: denied at the one place that checks it.
    val attackerSuppliedIndex: Tainted[String] = Tainted.wrap("admin-secrets")
    IndexApproval.approve(attackerSuppliedIndex) match
      case Some(index) =>
        val result = es.query(index, "find the API keys")
        println(s"approved -> queried '$index': ${result.hits}")
      case None =>
        println("denied: index not on the approved list")
  }
