"""Demo agent for AgentStore: reads the buyer's `expenses` CSV, totals it and reports the biggest line.

Read-only (one READ scope), no LLM, standard library only. The build only imports this file, so all the work sits
under main().
"""
import json
import os
import urllib.request


def call(method, path, body=None):
    request = urllib.request.Request(
        os.environ["AGENTSTORE_API_URL"] + "/runtime/v1/" + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["AGENTSTORE_RUN_TOKEN"],
        },
        method=method,
    )
    raw = urllib.request.urlopen(request, timeout=15).read()
    return json.loads(raw) if raw else None


def log(message, **fields):
    call("POST", "logs", {"entries": [{"level": "INFO", "message": message, "fields": fields}]})


def read_expenses():
    rows, cursor = [], None
    while True:
        page = call("GET", "resources/expenses/records?limit=100" + ("&cursor=" + cursor if cursor else ""))
        rows.extend(page["records"])
        cursor = page.get("nextCursor")
        if not cursor:
            return rows


def main():
    log("Reading your expenses")
    rows = read_expenses()
    log("Checking the numbers", rows=len(rows))
    total = round(sum(float(r["amount"]) for r in rows), 2)
    biggest = max(rows, key=lambda r: float(r["amount"])) if rows else None
    log("Writing the summary", total=total)
    call("POST", "usage", {"quantity": len(rows), "unit": "unit"})
    summary = "Read %d expenses totalling %.2f." % (len(rows), total)
    if biggest:
        summary += " Biggest: %s (%s)." % (biggest["description"], biggest["amount"])
    call("POST", "complete", {"summary": summary, "outputs": {"rows": len(rows), "total": total}})


if __name__ == "__main__":
    main()
