"""Demo agent for AgentStore: no scopes, no LLM, standard library only.

The build only imports this file, so all the work sits under main(). It reports a few steps, one usage event and a
summary through the Runtime API, then completes the run.
"""
import json
import os
import time
import urllib.request


def call(path, body):
    request = urllib.request.Request(
        os.environ["AGENTSTORE_API_URL"] + "/runtime/v1/" + path,
        data=json.dumps(body).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + os.environ["AGENTSTORE_RUN_TOKEN"],
        },
        method="POST",
    )
    urllib.request.urlopen(request, timeout=10).read()


def log(message, **fields):
    call("logs", {"entries": [{"level": "INFO", "message": message, "fields": fields}]})


def main():
    steps = ["Reading the request", "Checking the numbers", "Writing the summary"]
    for number, step in enumerate(steps, start=1):
        log(step, step=number, of=len(steps))
        time.sleep(2)
    call("usage", {"quantity": len(steps), "unit": "step"})
    call("complete", {
        "summary": "Demo agent finished all %d steps." % len(steps),
        "outputs": {"steps": len(steps), "status": "ok"},
    })


if __name__ == "__main__":
    main()
