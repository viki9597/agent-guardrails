//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

trait Network:
  def send(msg: String): Unit

def withNetwork[T](op: Network^ => T): T =
  val net = new Network:
    def send(msg: String): Unit = println(s"[sent] $msg")
  op(net)

def sendGreeting(net: Network^): Unit =
  net.send("hello")

@main def run(): Unit =
  withNetwork { net => sendGreeting(net) }
