package bank.authz

import rego.v1

# Mirrors bank_good.scala / bank_bad.scala: a tool call is only allowed if
# the customer it's asking about matches the customer the current session
# was actually authorized for.
#
# authorized_user_id is NOT taken from input (the caller could just claim
# anything). It's looked up in data.sessions — a trusted table loaded
# alongside the policy, not supplied by whoever is making the request.

default allow := false

allow if {
    input.tool in {"getUserDetails", "listRecentTransactions"}
    data.sessions[input.session_id] == input.requested_user_id
}
