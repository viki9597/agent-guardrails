//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

trait Mic:
  def announce(msg: String): Unit

def onAir[T](show: Mic^ => T): T =
  val mic = new Mic:
    def announce(msg: String): Unit = println(s"ON AIR: $msg")
  show(mic)

// Tries to write down "announce this LATER" and hand it out as something
// that needs nothing (() -> Unit) — but it still secretly needs the mic.
def saveForLater(mic: Mic^): () -> Unit =
  () => mic.announce("Surprise announcement after the show!")

@main def badRun(): Unit =
  val later: () -> Unit = onAir(mic => saveForLater(mic))
  later() // the show is over — this mic shouldn't still work
