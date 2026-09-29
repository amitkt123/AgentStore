from agentstore import AgentContext, run


def handler(ctx: AgentContext) -> dict:
    ctx.gates.request("post_journal_entries", "Post 1 entry", {"rows": 1},
                      affects=["journal_entries"], raise_on_reject=True)
    return {}


if __name__ == "__main__":
    run(handler)
