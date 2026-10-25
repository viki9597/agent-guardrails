//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking
//> using dep com.lihaoyi::upickle:3.3.1

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.io.{BufferedReader, InputStreamReader}
import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import upickle.default.*

case class User(id: String, name: String, email: String, ssn: String) derives ReadWriter
case class Transaction(id: String, description: String, amount: Double, date: String) derives ReadWriter

// Classified never gives up the raw value: map only ever returns another
// Classified, and there is no unwrap/declassify method in this library at
// all. Agent-submitted code has no path out, by construction — not by
// convention.
final class Classified[+T] private (private val value: T):
  def map[U](f: T -> U): Classified[U] = Classified.wrap(f(value))
  def preview: String = "Classified(***)"

object Classified:
  def wrap[T](v: T): Classified[T] = new Classified(v)

// The capability: being able to call the Acme Bank MCP server, and nothing else.
trait BankApi:
  def getUserDetails(userId: String): Classified[User]
  def listRecentTransactions(userId: String): Classified[List[Transaction]]

final class LiveBankApi(baseUrl: String) extends BankApi:
  private val client = HttpClient.newHttpClient()
  private val pending = new ConcurrentHashMap[Int, LinkedBlockingQueue[ujson.Value]]()
  private val endpointReady = new LinkedBlockingQueue[String]()
  private val nextId = new AtomicInteger(1)
  @volatile private var messageEndpoint: String = ""

  private val sseThread = new Thread(() =>
    val req = HttpRequest.newBuilder(URI.create(s"$baseUrl/sse"))
      .header("Accept", "text/event-stream").GET().build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
    val reader = new BufferedReader(new InputStreamReader(resp.body()))
    var currentEvent = ""
    var line = reader.readLine()
    while line != null do
      if line.startsWith("event:") then
        currentEvent = line.stripPrefix("event:").trim
      else if line.startsWith("data:") then
        val data = line.stripPrefix("data:")
        if currentEvent == "endpoint" then
          messageEndpoint = data
          endpointReady.put(data)
        else if currentEvent == "message" then
          val json = ujson.read(data)
          json.obj.get("id").foreach { idVal =>
            val id = idVal.num.toInt
            pending.computeIfAbsent(id, _ => new LinkedBlockingQueue[ujson.Value]()).put(json)
          }
      line = reader.readLine()
  )
  sseThread.setDaemon(true)
  sseThread.start()
  endpointReady.poll(5, TimeUnit.SECONDS)

  private def post(body: String): Unit =
    val req = HttpRequest.newBuilder(URI.create(s"$baseUrl$messageEndpoint"))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body)).build()
    client.send(req, HttpResponse.BodyHandlers.discarding())

  private def call(method: String, params: ujson.Value): ujson.Value =
    val id = nextId.getAndIncrement()
    val queue = new LinkedBlockingQueue[ujson.Value]()
    pending.put(id, queue)
    post(ujson.write(ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "method" -> method, "params" -> params)))
    val resp = queue.poll(5, TimeUnit.SECONDS)
    resp("result")

  call("initialize", ujson.Obj(
    "protocolVersion" -> "2024-11-05",
    "capabilities" -> ujson.Obj(),
    "clientInfo" -> ujson.Obj("name" -> "tacit-facade", "version" -> "0.1")
  ))
  post(ujson.write(ujson.Obj("jsonrpc" -> "2.0", "method" -> "notifications/initialized")))

  private def callTool(name: String, args: ujson.Value): String =
    call("tools/call", ujson.Obj("name" -> name, "arguments" -> args))("content")(0)("text").str

  def getUserDetails(userId: String): Classified[User] =
    Classified.wrap(read[User](callTool("getUserDetails", ujson.Obj("userId" -> userId))))

  def listRecentTransactions(userId: String): Classified[List[Transaction]] =
    Classified.wrap(read[List[Transaction]](callTool("listRecentTransactions", ujson.Obj("userId" -> userId))))

// The agent only ever gets a BankApi inside this block — it can't escape.
// BANK_HOST is substituted by the server before compiling.
def withBankApi[T](op: BankApi^ => T): T =
  op(new LiveBankApi("BANK_HOST"))
