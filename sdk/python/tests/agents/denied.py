from agentstore import AgentContext, ScopeNotGrantedError, run


def handler(ctx: AgentContext) -> dict:
    try:
        list(ctx.data.records("payroll"))
    except ScopeNotGrantedError:
        ctx.log.error("payroll read denied as expected")
        raise
    return {}


if __name__ == "__main__":
    run(handler)
