//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

// The capability library the Specialist planner (an LLM) is told about.
// Prepended to whatever Scala code it writes, then compiled for real.
//
// ApprovedIndex is deliberately opaque: its constructor is private, so
// NO code — however it was written, by a careful engineer or a careless
// LLM — can construct one except through IndexApproval.approve. A
// literal string like "admin-secrets" simply cannot become an
// ApprovedIndex by typing it; it has to be looked up and approved.

final class ApprovedIndex private (val name: String)

object ApprovedIndex:
  private val APPROVED: Set[String] = Set("support-tickets", "product-docs")
  // The companion object is the only code with access to the private
  // constructor — so this is the ONLY place in the whole program an
  // ApprovedIndex can be made.
  def approve(candidate: String): Option[ApprovedIndex] =
    if APPROVED.contains(candidate) then Some(new ApprovedIndex(candidate)) else None

case class SearchResult(hits: List[String])

trait ElasticsearchApi:
  def query(index: ApprovedIndex, queryText: String): SearchResult

final class LiveElasticsearchApi extends ElasticsearchApi:
  def query(index: ApprovedIndex, queryText: String): SearchResult =
    SearchResult(List(s"[mock hit from '${index.name}'] matching: $queryText"))

def withElasticsearch[T](op: ElasticsearchApi^ => T): T =
  op(new LiveElasticsearchApi)
