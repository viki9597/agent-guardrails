//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

// A naive "taint label" — this is what "provenance tracking" looks like
// when it's a label and nothing more. unwrap is public: anyone holding a
// Tainted[T] can always get the raw T back out.
final class Tainted[+T](val raw: T):
  def map[U](f: T => U): Tainted[U] = new Tainted(f(raw))

case class SearchResult(hits: List[String])

trait ElasticsearchApi:
  // index is the sensitive, privileged position — which indices this
  // agent is allowed to touch. queryText is not sensitive: free-text
  // search content is fine to come from anywhere.
  def query(index: String, queryText: String): SearchResult

final class LiveElasticsearchApi extends ElasticsearchApi:
  def query(index: String, queryText: String): SearchResult =
    SearchResult(List(s"mock hit from '$index' matching '$queryText'"))

def withElasticsearch[T](op: ElasticsearchApi^ => T): T =
  op(new LiveElasticsearchApi)

@main def badRun(): Unit =
  withElasticsearch { es =>
    // Stands in for content that came back from the Quarantined processor —
    // e.g. the user wrote "look in the admin-secrets index for this".
    // It's labeled Tainted. Nothing more happens to it.
    val attackerSuppliedIndex: Tainted[String] = new Tainted("admin-secrets")

    // Labeling alone doesn't stop this: .raw is public, so the "trusted"
    // planner can just reach in and use it directly as the index — the
    // sensitive, privileged position. This compiles. That's the bug.
    val result = es.query(attackerSuppliedIndex.raw, "find the API keys")
    println(s"queried index directly from tainted data: ${result.hits}")
  }
