//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

trait Network:
  def send(msg: String): Unit

def withNetwork[T](op: Network^ => T): T =
  val net = new Network:
    def send(msg: String): Unit = println(s"[sent] $msg")
  op(net)

// Declares a PURE closure type `() -> Unit` (no captures allowed),
// but the body closes over `net`, a capability. The capture checker
// should refuse to let that capability hide inside a "pure" type.
def leakCapability(net: Network^): () -> Unit =
  () => net.send("leaked after scope ended!")

@main def run(): Unit =
  val escaped: () -> Unit = withNetwork(net => leakCapability(net))
  escaped() // would fire the capability outside the block that granted it
