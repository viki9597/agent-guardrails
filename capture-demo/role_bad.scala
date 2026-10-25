//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

trait Dataset:
  def readRow(id: Int): String

def withDataset[T](op: Dataset^ => T): T =
  val ds = new Dataset:
    def readRow(id: Int): String = s"row-$id"
  op(ds)

// Written as a shared formatter any role should be able to call —
// declared pure. But the body still reaches for the analyst-only
// Dataset capability.
def summarize(dataset: Dataset^): String -> String =
  (label: String) => s"$label: ${dataset.readRow(0)}"

@main def badRun(): Unit =
  withDataset { dataset =>
    println(summarize(dataset)("preview"))
  }
