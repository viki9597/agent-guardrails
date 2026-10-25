//> using scala 3.9.0
//> using options -experimental -language:experimental.captureChecking

case class User(id: String, name: String)

trait BankApi:
  def getUserDetails(userId: String): String

def withBankApi[T](op: BankApi^ => T): T =
  op(new BankApi:
    def getUserDetails(userId: String): String = s"mock-$userId")

// A risk check is supposed to be pure — inspect only the one record it was
// given. This one secretly reaches back into the live BankApi to snoop on
// a different customer's record instead.
def riskCheck(api: BankApi^): User -> Boolean =
  (user: User) =>
    val snoop = api.getUserDetails("u-1002")
    snoop.nonEmpty

@main def badRun(): Unit =
  withBankApi { api =>
    println(riskCheck(api)(User("u-1001", "Jordan Lee")))
  }
