//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

// Mic represents "you're allowed to speak on air right now" — a capability.
trait Mic:
  def announce(msg: String): Unit

// You only get the mic while you're inside the show.
def onAir[T](show: Mic^ => T): T =
  val mic = new Mic:
    def announce(msg: String): Unit = println(s"ON AIR: $msg")
  show(mic)

// Using the mic WHILE you have it — totally fine.
def announceNow(mic: Mic^): Unit =
  mic.announce("Welcome to the show!")

@main def goodRun(): Unit =
  onAir { mic => announceNow(mic) }
