"""Bank reconciliation demo agent for AgentStore.

Reads the buyer's `bank_statement` CSV, maps each line to a ledger account by keyword, stages the journal entries for
`ledger`, and asks the buyer to approve before anything is posted. Standard library only, no LLM. The build only imports
this file, so all the work sits under main().
"""
import json
import os
import urllib.request
from decimal import Decimal

# First keyword found in the narration wins; anything else goes to suspense for a person to look at.
ACCOUNTS = [
    ("salary", "5000 Salaries"),
    ("rent", "5100 Rent"),
    ("aws", "5200 Cloud hosting"),
    ("hosting", "5200 Cloud hosting"),
    ("insurance", "5300 Insurance"),
    ("invoice", "1200 Accounts receivable"),
    ("interest", "4100 Interest income"),
]
SUSPENSE = "9999 Suspense"


def call(method, path, body=None, timeout=15):
    request = urllib.request.Request(
        os.environ["AGENTSTORE_API_URL"] + "/runtime/v1/" + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["AGENTSTORE_RUN_TOKEN"],
        },
        method=method,
    )
    raw = urllib.request.urlopen(request, timeout=timeout).read()
    return json.loads(raw) if raw else None


def log(message, **fields):
    call("POST", "logs", {"entries": [{"level": "INFO", "message": message, "fields": fields}]})


def read_statement():
    rows, cursor = [], None
    while True:
        page = call("GET", "resources/bank_statement/records?limit=100" + ("&cursor=" + cursor if cursor else ""))
        rows.extend(page["records"])
        cursor = page.get("nextCursor")
        if not cursor:
            return rows


def account_for(narration):
    text = narration.lower()
    for keyword, account in ACCOUNTS:
        if keyword in text:
            return account
    return SUSPENSE


def money(value):
    # Do the arithmetic in Decimal; JSON gets a plain number because the gate preview only totals numeric fields.
    # float() of a two-place Decimal prints back as the same digits, so nothing drifts on the way out.
    return float(value.quantize(Decimal("0.01")))


def journal_entry(row):
    amount = Decimal(str(row["amount"]))
    return {
        "date": row["date"],
        "account": account_for(row["narration"]),
        "narration": row["narration"],
        "debit": money(-amount) if amount < 0 else 0,
        "credit": money(amount) if amount > 0 else 0,
    }


def wait_for_decision(gate):
    while gate["status"] == "PENDING":
        gate = call("GET", "gates/%s?wait=25" % gate["gateId"], timeout=40)
    return gate


def main():
    log("Reading the bank statement")
    rows = read_statement()
    entries = [journal_entry(r) for r in rows]
    unmatched = sum(1 for e in entries if e["account"] == SUSPENSE)
    log("Matched the lines to accounts", lines=len(entries), unmatched=unmatched)

    # Stage first: the rows sit in a holding area and nothing is posted. The gate then shows them to the buyer, and
    # approving it is what commits them. A rejection discards them and cancels the run.
    staged = call("POST", "resources/ledger/records", {"records": entries})
    log("Staged the entries for approval", staged=staged["accepted"])
    gate = call("POST", "gates", {
        "action": "post_entries",
        "summary": "Post %d journal entries (%d need a look in suspense)" % (len(entries), unmatched),
        "payload": {"entries": len(entries), "unmatched": unmatched},
        "affectsResources": ["ledger"],
        "estimatedUnits": len(entries),
    })
    log("Waiting for your approval before posting", gate=gate["gateId"])
    decision = wait_for_decision(gate)
    if decision["status"] != "APPROVED":
        call("POST", "complete", {"summary": "Not posted: the request was %s." % decision["status"].lower(),
                                  "outputs": {"posted": 0}})
        return

    log("Entries approved and posted", posted=staged["accepted"])
    call("POST", "usage", {"quantity": len(entries), "unit": "reconciled_transaction"})
    call("POST", "complete", {
        "summary": "Posted %d entries, %d in suspense for review." % (staged["accepted"], unmatched),
        "outputs": {"posted": staged["accepted"], "suspense": unmatched},
    })

if __name__ == "__main__":
    main()
