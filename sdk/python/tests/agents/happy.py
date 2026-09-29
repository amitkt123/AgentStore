from agentstore import AgentContext, run


def handler(ctx: AgentContext) -> dict:
    statement = list(ctx.data.records("bank_statement"))
    ctx.log.info("read bank statement", rows=len(statement))
    entries = [{"account": "1000", "amount": r["amount"]} for r in statement]
    decision = ctx.gates.request("post_journal_entries", f"Post {len(entries)} entries",
                                 {"rows": len(entries)}, affects=["journal_entries"], raise_on_reject=True)
    result = ctx.data.write("journal_entries", entries)
    ctx.usage.report(len(entries), "reconciled_transaction")
    return {"posted": result.accepted, "staged": result.staged, "gate": decision.status}


if __name__ == "__main__":
    run(handler)
