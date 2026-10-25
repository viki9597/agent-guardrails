//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

// The only API the planner's generated code can use. TACIT prepends this
// to the code and compiles both together with capture checking on.

// An index can only be obtained through approve: the constructor is private.
final class ApprovedIndex private (val name: String)

object ApprovedIndex:
  private val APPROVED: Set[String] = Set("support-tickets", "product-docs")
  def approve(name: String): Option[ApprovedIndex] =
    if APPROVED.contains(name) then Some(new ApprovedIndex(name)) else None

// Stands for the user's message but carries no content: the untrusted
// text never enters this program, so generated code can't read or branch
// on it, only pass it along.
final class SearchText private ()
object SearchText:
  val user: SearchText = new SearchText

val userText: SearchText = SearchText.user

trait ElasticsearchApi:
  def search(index: ApprovedIndex, text: SearchText): Unit

// Doesn't search anything itself: it records each requested call, tagged
// with a per-run nonce, for Progent to check before anything executes.
final class RecordingElasticsearchApi extends ElasticsearchApi:
  private val nonce = "RUN_NONCE"
  def search(index: ApprovedIndex, text: SearchText): Unit =
    println(s"$nonce TOOLCALL search ${index.name}")

def withElasticsearch[T](op: ElasticsearchApi^ => T): T =
  op(new RecordingElasticsearchApi)
