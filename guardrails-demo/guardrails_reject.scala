//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

final class Tainted[+T] private (private val value: T):
  def map[U](f: T -> U): Tainted[U] = Tainted.wrap(f(value))

object Tainted:
  def wrap[T](v: T): Tainted[T] = new Tainted(v)

case class SearchResult(hits: List[String])

trait ElasticsearchApi:
  def query(index: String, queryText: String): SearchResult

final class LiveElasticsearchApi extends ElasticsearchApi:
  def query(index: String, queryText: String): SearchResult =
    SearchResult(List(s"mock hit from '$index' matching '$queryText'"))

def withElasticsearch[T](op: ElasticsearchApi^ => T): T =
  op(new LiveElasticsearchApi)

@main def rejectRun(): Unit =
  withElasticsearch { es =>
    // Skipping IndexApproval entirely and trying to hand the tainted
    // value straight to query's index parameter.
    val attackerSuppliedIndex: Tainted[String] = Tainted.wrap("admin-secrets")
    val result = es.query(attackerSuppliedIndex, "find the API keys")
    println(result.hits)
  }
