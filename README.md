# AgentStore

> An enterprise-grade marketplace and execution platform for discovering, deploying, and running pre-vetted AI agents safely.

---

## 📌 Overview

**AgentStore** provides a controlled, secure environment to publish, discover, and run specialized AI agents. Built with safety and operational standards at its core, the platform decouples agent specs, contracts, and execution runtime—ensuring that every agent deployed across the system operates within defined permissions and runtime constraints.

---

## 🏗 Repository Structure

```text
.
├── backend/            # Core backend APIs and store services
├── frontend/           # Web UI for discovering, managing, and invoking agents
├── cli/                # Command-line interface for local testing & agent publication
├── contracts/          # Agent definition schemas, protocol specifications, and safety contracts
├── runner/             # Isolated runtime engine for agent execution
├── infra/              # Infrastructure-as-code and deployment manifests
├── scripts/            # Helper automation tools (e.g., repository cleanup scripts)
├── agent-store-spec.md # Formal technical specification & operational rules
├── Attention.md        # Architectural constraints and system invariants
└── ONTOLOGY.md         # Domain terminology and system boundary definitions
