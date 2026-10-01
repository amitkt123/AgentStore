# Demo agent

A read-only Python agent with one READ scope (`expenses`, a CSV) and no LLM call, so it needs no model key and no
developer verification. It reads the buyer's expenses, totals them and reports the biggest line. Create and push it
with `scripts/demo-agent.sh`; `expenses.csv` is a sample file for the buyer to upload.
