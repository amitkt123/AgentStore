<!--
ontology-tracked-files: backend/src/main/java/com/agentstore/platform/catalog/Agent.java,backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java,backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java,backend/src/main/java/com/agentstore/platform/catalog/CatalogListingController.java,backend/src/main/java/com/agentstore/platform/catalog/CreateAgentRequest.java,backend/src/main/java/com/agentstore/platform/catalog/CreateAgentVersionRequest.java,backend/src/main/java/com/agentstore/platform/catalog/MyAgentsController.java,backend/src/main/java/com/agentstore/platform/catalog/Tier.java,backend/src/main/java/com/agentstore/platform/catalog/VersionStatus.java,backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java,backend/src/main/java/com/agentstore/platform/execution/AgentEventRequest.java,backend/src/main/java/com/agentstore/platform/execution/Connection.java,backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java,backend/src/main/java/com/agentstore/platform/execution/ConnectionStatus.java,backend/src/main/java/com/agentstore/platform/execution/CreateConnectionRequest.java,backend/src/main/java/com/agentstore/platform/execution/FakeSandboxProvider.java,backend/src/main/java/com/agentstore/platform/execution/GateStatusResponse.java,backend/src/main/java/com/agentstore/platform/execution/InternalRunsController.java,backend/src/main/java/com/agentstore/platform/execution/Run.java,backend/src/main/java/com/agentstore/platform/execution/RunController.java,backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java,backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java,backend/src/main/java/com/agentstore/platform/execution/RunReaper.java,backend/src/main/java/com/agentstore/platform/execution/RunStatus.java,backend/src/main/java/com/agentstore/platform/execution/RunStep.java,backend/src/main/java/com/agentstore/platform/execution/RunStepKind.java,backend/src/main/java/com/agentstore/platform/execution/RunnerSandboxProvider.java,backend/src/main/java/com/agentstore/platform/execution/SandboxCapacityException.java,backend/src/main/java/com/agentstore/platform/execution/SandboxProvider.java,backend/src/main/java/com/agentstore/platform/execution/SandboxSpec.java,backend/src/main/java/com/agentstore/platform/execution/SandboxStatus.java,backend/src/main/java/com/agentstore/platform/execution/Secret.java,backend/src/main/java/com/agentstore/platform/execution/TriggerRunRequest.java,backend/src/main/java/com/agentstore/platform/identity/AuthController.java,backend/src/main/java/com/agentstore/platform/identity/AuthResponse.java,backend/src/main/java/com/agentstore/platform/identity/GoogleSignInService.java,backend/src/main/java/com/agentstore/platform/identity/IdentityService.java,backend/src/main/java/com/agentstore/platform/identity/Invitation.java,backend/src/main/java/com/agentstore/platform/identity/InvitationController.java,backend/src/main/java/com/agentstore/platform/identity/InvitationService.java,backend/src/main/java/com/agentstore/platform/identity/JwtService.java,backend/src/main/java/com/agentstore/platform/identity/LoginRequest.java,backend/src/main/java/com/agentstore/platform/identity/MeController.java,backend/src/main/java/com/agentstore/platform/identity/MembersController.java,backend/src/main/java/com/agentstore/platform/identity/Org.java,backend/src/main/java/com/agentstore/platform/identity/OrgMembership.java,backend/src/main/java/com/agentstore/platform/identity/OrgRoles.java,backend/src/main/java/com/agentstore/platform/identity/OrgType.java,backend/src/main/java/com/agentstore/platform/identity/RefreshTokenService.java,backend/src/main/java/com/agentstore/platform/identity/SignInProblem.java,backend/src/main/java/com/agentstore/platform/identity/SignupRequest.java,backend/src/main/java/com/agentstore/platform/identity/SignupSide.java,backend/src/main/java/com/agentstore/platform/identity/User.java,backend/src/main/java/com/agentstore/platform/identity/UserRole.java,backend/src/main/java/com/agentstore/platform/ledger/LedgerEntry.java,backend/src/main/java/com/agentstore/platform/ledger/LedgerEntryType.java,backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java,backend/src/main/java/com/agentstore/platform/ledger/StatementController.java,backend/src/main/java/com/agentstore/platform/ledger/UsageEvent.java,backend/src/main/java/com/agentstore/platform/ledger/Wallet.java,backend/src/main/java/com/agentstore/platform/ledger/WalletController.java,backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java,backend/src/main/java/com/agentstore/platform/manifest/ApprovalGateDecl.java,backend/src/main/java/com/agentstore/platform/manifest/CostEnvelope.java,backend/src/main/java/com/agentstore/platform/manifest/Manifest.java,backend/src/main/java/com/agentstore/platform/manifest/ManifestParser.java,backend/src/main/java/com/agentstore/platform/manifest/ManifestValidator.java,backend/src/main/java/com/agentstore/platform/manifest/PricingUnit.java,backend/src/main/java/com/agentstore/platform/manifest/Scope.java,backend/src/main/java/com/agentstore/platform/manifest/ScopeType.java,backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java,backend/src/main/java/com/agentstore/platform/publishing/ReviewController.java,backend/src/main/java/com/agentstore/platform/publishing/ReviewItem.java,backend/src/main/java/com/agentstore/platform/publishing/ReviewStatus.java,backend/src/main/java/com/agentstore/platform/publishing/SubmissionController.java,backend/src/main/resources/manifest/manifest-schema.json,frontend/src/lib/types.ts,runner/src/main/java/com/agentstore/runner/api/ApiErrorHandler.java,runner/src/main/java/com/agentstore/runner/api/CreateSandboxRequest.java,runner/src/main/java/com/agentstore/runner/api/RunnerSecretFilter.java,runner/src/main/java/com/agentstore/runner/api/SandboxController.java,runner/src/main/java/com/agentstore/runner/backend/IsolationBackend.java,runner/src/main/java/com/agentstore/runner/backend/OwnedSandbox.java,runner/src/main/java/com/agentstore/runner/backend/SandboxState.java,runner/src/main/java/com/agentstore/runner/backend/e2b/E2bBackend.java,runner/src/main/java/com/agentstore/runner/sandbox/BudgetGuard.java,runner/src/main/java/com/agentstore/runner/sandbox/BudgetLedger.java,runner/src/main/java/com/agentstore/runner/sandbox/RunnerReaper.java,runner/src/main/java/com/agentstore/runner/sandbox/SandboxService.java,runner/src/main/java/com/agentstore/runner/sandbox/SpringRunsClient.java,runner/src/main/resources/application.yml,backend/src/main/java/com/agentstore/platform/build/AgentBundle.java,backend/src/main/java/com/agentstore/platform/build/BuildStatus.java,backend/src/main/java/com/agentstore/platform/build/BundleStatus.java,backend/src/main/java/com/agentstore/platform/build/TemplateBuild.java,backend/src/main/java/com/agentstore/platform/build/BuildFailureCodes.java,backend/src/main/java/com/agentstore/platform/build/BundleService.java,backend/src/main/java/com/agentstore/platform/build/BundleController.java,backend/src/main/java/com/agentstore/platform/build/BundleInspector.java,backend/src/main/java/com/agentstore/platform/build/BuildTransitions.java,backend/src/main/java/com/agentstore/platform/build/BuildDispatcher.java,backend/src/main/java/com/agentstore/platform/build/BuildPoller.java,backend/src/main/java/com/agentstore/platform/build/BuildJob.java,backend/src/main/java/com/agentstore/platform/build/BuildStatusController.java,backend/src/main/java/com/agentstore/platform/build/RebuildService.java,backend/src/main/java/com/agentstore/platform/build/BuildRunnerClient.java,backend/src/main/java/com/agentstore/platform/execution/RunLauncher.java,backend/src/main/java/com/agentstore/platform/execution/PendingBuildRuns.java,backend/src/main/java/com/agentstore/platform/manifest/AgentRuntime.java,backend/src/main/java/com/agentstore/platform/manifest/BundleManifestRules.java,backend/src/main/java/com/agentstore/platform/storage/ObjectStorage.java,backend/src/main/resources/db/migration/V23__bundles_and_builds.sql,runner/src/main/java/com/agentstore/runner/api/BuildController.java,runner/src/main/java/com/agentstore/runner/api/CreateBuildRequest.java,runner/src/main/java/com/agentstore/runner/backend/AgentRuntime.java,runner/src/main/java/com/agentstore/runner/backend/BuildFailedException.java,runner/src/main/java/com/agentstore/runner/build/BuildService.java,runner/src/main/java/com/agentstore/runner/build/BundleFetcher.java,runner/src/main/java/com/agentstore/runner/build/BundleUnpacker.java,runner/src/main/java/com/agentstore/runner/build/SecretScanner.java,runner/src/main/java/com/agentstore/runner/build/LogRedactor.java,runner/e2b-shim/shim.mjs,backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKey.java,backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyController.java,backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyService.java,backend/src/main/java/com/agentstore/platform/identity/ApiKeyAuthFilter.java,backend/src/main/java/com/agentstore/platform/identity/ApiKeyScope.java,backend/src/main/resources/db/migration/V24__developer_api_key.sql,backend/src/main/java/com/agentstore/platform/execution/GateRequest.java,backend/src/main/java/com/agentstore/platform/execution/GateService.java,backend/src/main/java/com/agentstore/platform/execution/RunMode.java,backend/src/main/java/com/agentstore/platform/execution/RunStepSource.java,backend/src/main/java/com/agentstore/platform/execution/GateStatus.java,backend/src/main/java/com/agentstore/platform/runtime/RunPrincipal.java,backend/src/main/java/com/agentstore/platform/runtime/RuntimeAuthFilter.java,backend/src/main/java/com/agentstore/platform/runtime/ScopeGuard.java,backend/src/main/java/com/agentstore/platform/runtime/RuntimeRateLimiter.java,backend/src/main/java/com/agentstore/platform/runtime/ResourceBackend.java,backend/src/main/java/com/agentstore/platform/runtime/LlmBackend.java,backend/src/main/resources/db/migration/V25__runtime_api.sql,backend/src/main/resources/db/migration/V26__gate_request.sql,contracts/runtime-v1.yaml,backend/src/main/java/com/agentstore/platform/execution/RunLlmMode.java,backend/src/main/java/com/agentstore/platform/llm/AnthropicProvider.java,backend/src/main/java/com/agentstore/platform/llm/ByokCredential.java,backend/src/main/java/com/agentstore/platform/llm/ByokCredentialRepository.java,backend/src/main/java/com/agentstore/platform/llm/ByokKeyService.java,backend/src/main/java/com/agentstore/platform/llm/GatewayLlmBackend.java,backend/src/main/java/com/agentstore/platform/llm/LlmApiErrors.java,backend/src/main/java/com/agentstore/platform/llm/LlmApiException.java,backend/src/main/java/com/agentstore/platform/llm/LlmBudget.java,backend/src/main/java/com/agentstore/platform/llm/LlmCall.java,backend/src/main/java/com/agentstore/platform/llm/LlmCallLog.java,backend/src/main/java/com/agentstore/platform/llm/LlmCallRepository.java,backend/src/main/java/com/agentstore/platform/llm/LlmCallStatus.java,backend/src/main/java/com/agentstore/platform/llm/LlmCost.java,backend/src/main/java/com/agentstore/platform/llm/LlmKeyController.java,backend/src/main/java/com/agentstore/platform/llm/LlmKeyMissingException.java,backend/src/main/java/com/agentstore/platform/llm/LlmKeyResolver.java,backend/src/main/java/com/agentstore/platform/llm/LlmModel.java,backend/src/main/java/com/agentstore/platform/llm/LlmModelRepository.java,backend/src/main/java/com/agentstore/platform/llm/LlmProperties.java,backend/src/main/java/com/agentstore/platform/llm/LlmProvider.java,backend/src/main/java/com/agentstore/platform/llm/LlmRecorder.java,backend/src/main/java/com/agentstore/platform/llm/LlmRecordingRetentionJob.java,backend/src/main/java/com/agentstore/platform/llm/LlmRecordingRetentionService.java,backend/src/main/java/com/agentstore/platform/llm/LlmRequest.java,backend/src/main/java/com/agentstore/platform/llm/LlmRequestNormalizer.java,backend/src/main/java/com/agentstore/platform/llm/LlmResponse.java,backend/src/main/java/com/agentstore/platform/llm/LlmSource.java,backend/src/main/java/com/agentstore/platform/llm/MockResponder.java,backend/src/main/java/com/agentstore/platform/llm/OpenAiProvider.java,backend/src/main/java/com/agentstore/platform/llm/ProviderCallException.java,backend/src/main/java/com/agentstore/platform/llm/ProviderClient.java,backend/src/main/java/com/agentstore/platform/llm/ProviderHttp.java,backend/src/main/java/com/agentstore/platform/llm/ReplayResponder.java,backend/src/main/java/com/agentstore/platform/llm/RunLlmContext.java,backend/src/main/java/com/agentstore/platform/llm/RunLlmContextLoader.java,backend/src/main/java/com/agentstore/platform/manifest/LlmDecl.java,backend/src/main/java/com/agentstore/platform/manifest/LlmManifestRules.java,backend/src/main/java/com/agentstore/platform/manifest/LlmMode.java,backend/src/main/java/com/agentstore/platform/manifest/LlmVendor.java
ontology-source-hash: f46a55d54ac938f9c1a92578707569a61370cf7cd1d3c7d2180d07e86b51d029
ontology-generated-at: 2026-09-26T13:11:35Z
-->

# AgentStore System Domain Ontology
_Generated from commit `e295ec6` (2026-09-25, branch `feat/ws5a-sandbox-runner`); the build pipeline (WS5b) was added at commit `3737867` (2026-09-26, branch `feat/ws5b-build-pipeline`). Developer API keys and the CLI (WS5c) were added on branch `feat/ws5c-api-keys-cli`. The Agent Runtime API (WS1) was added on branch `feat/ws1-agent-runtime-api`. Regenerate when core schemas or APIs change._

## 1. Meta & Scope
- **Domain:** A B2B marketplace for autonomous agents. A `DEVELOPER` org publishes containerised agents with a declarative `Manifest`. A `BUYER` org connects to an agent, grants it scopes and a spend cap, and triggers metered `Run`s. Each run executes in an isolated remote sandbox and is billed against the buyer's prepaid `Wallet`. The approval-gate and spend-cap vocabulary, and the frontend's "I run finance" sign-up side (`backend/src/main/java/com/agentstore/platform/identity/SignupSide.java`), suggest finance buyers, but no code limits it to finance.
- **Core Objective:** Handle the whole agent lifecycle: publish (bundle upload, image build, lint, tier, review), connect (consent, scopes, secrets), execute (sandbox launch, agent callbacks, human approval gates, reaping), and bill (usage, ledger, wallet reservation). An agent working in this repo mostly edits these flows. The Spring backend (`backend/`) owns domain state. The sandbox runner (`runner/`) is a separate service that owns vendor credentials and sandbox spend.

## 2. Core Classes (Concepts)

### Identity & tenancy
* **`Org`** (`backend/src/main/java/com/agentstore/platform/identity/Org.java`): A tenant workspace. `type`: `[BUYER | DEVELOPER]` (`OrgType`, `backend/src/main/java/com/agentstore/platform/identity/OrgType.java`).
* **`User`** (`backend/src/main/java/com/agentstore/platform/identity/User.java`): A person, keyed by a unique email. `passwordHash` is null for Google-only users. `lastOrgId` is the org a login lands in. `signupOrgId` is the org they created at signup and is never updated afterwards.
* **`OrgMembership`** (`backend/src/main/java/com/agentstore/platform/identity/OrgMembership.java`): The (`User`, `Org`) pair, with a `role` and an optional `title`. A user can belong to several orgs.
* **`UserRole`** (`backend/src/main/java/com/agentstore/platform/identity/UserRole.java`): `[BUYER_ADMIN | BUYER_APPROVER | DEVELOPER | ADMIN]`. `ADMIN` is the platform-wide reviewer role, not an org admin.
* **`Invitation`** (`backend/src/main/java/com/agentstore/platform/identity/Invitation.java`): A single-use, hashed-token invite that brings an email into an `Org` with a given `role`.
* **`JwtService.Claims`** (`backend/src/main/java/com/agentstore/platform/identity/JwtService.java`): The authenticated principal `(userId, orgId, role)`. Every controller reads it for tenancy checks.
* **`DeveloperApiKey`** (`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKey.java`): An org-scoped credential for the CLI: `orgId`, `createdByUserId`, `name`, `prefix` (first 12 characters, for display), `keyHash` (SHA-256 of the whole `ask_live_…` key; the plaintext is shown once and never stored), `createdAt`, `lastUsedAt`, `revokedAt`. Table `developer_api_key` (migration `backend/src/main/resources/db/migration/V24__developer_api_key.sql`).

### Catalog & publishing
* **`Agent`** (`backend/src/main/java/com/agentstore/platform/catalog/Agent.java`): A listed product owned by a `developerOrg`, with `name`, `category` and `description`.
* **`AgentVersion`** (`backend/src/main/java/com/agentstore/platform/catalog/AgentVersion.java`): An immutable release of an `Agent`: `versionNumber`, raw `manifestJson`, container `imageRef` (null until a build succeeds), `changelog`, `bundle` (the `AgentBundle` the current image was built from; null for a version created with a hand-supplied `imageRef`) and `buildStatus` (`BuildStatus`, null when there is no bundle). `status`: `[DRAFT | IN_REVIEW | LIVE | REJECTED]` (`backend/src/main/java/com/agentstore/platform/catalog/VersionStatus.java`). `tier`: `[TIER_A | TIER_B]` (`backend/src/main/java/com/agentstore/platform/catalog/Tier.java`), null until submitted.
* **`Manifest`** (`backend/src/main/java/com/agentstore/platform/manifest/Manifest.java`): The parsed declaration of what an agent may do: `scopes: List<Scope>`, `networkAllowlist`, `costEnvelope: CostEnvelope`, `approvalGates: List<ApprovalGateDecl>`, `dataClasses`, `runtime` and `entrypoint`. `runtime` and `entrypoint` are required only for a manifest that arrives inside a bundle (`BundleManifestRules`, `backend/src/main/java/com/agentstore/platform/manifest/BundleManifestRules.java`) and are null otherwise. It is not persisted on its own; it is re-parsed from `AgentVersion.manifestJson` whenever needed.
  * **`Scope`** (`backend/src/main/java/com/agentstore/platform/manifest/Scope.java`): `(type, resource, description)`. `type`: `[READ | WRITE]` (`backend/src/main/java/com/agentstore/platform/manifest/ScopeType.java`).
  * **`CostEnvelope`** (`backend/src/main/java/com/agentstore/platform/manifest/CostEnvelope.java`): `currency`, `maxPerRun`, `unitPrice`, `unitLabel`, and `pricingUnit`: `[METERED | OUTCOME | SUBSCRIPTION]` (`backend/src/main/java/com/agentstore/platform/manifest/PricingUnit.java`).
  * **`ApprovalGateDecl`** (`backend/src/main/java/com/agentstore/platform/manifest/ApprovalGateDecl.java`): `(trigger, description)`.
* **`ReviewItem`** (`backend/src/main/java/com/agentstore/platform/publishing/ReviewItem.java`): A manual review ticket for a `TIER_B` `AgentVersion`. `status`: `[PENDING | APPROVED | REJECTED]` (`backend/src/main/java/com/agentstore/platform/publishing/ReviewStatus.java`).

### Build pipeline (backend)
* **`AgentBundle`** (`backend/src/main/java/com/agentstore/platform/build/AgentBundle.java`): An uploaded code bundle (a zip with `manifest.json` at its root) for an `Agent`: `storageKey` (`bundles/{agentId}/{bundleId}.zip`), `sha256`, `sizeBytes`, `uploadedByUserId`. The id is chosen before insert because it is part of the key. `status`: `[UPLOADING | UPLOADED | REJECTED]` (`BundleStatus`, `backend/src/main/java/com/agentstore/platform/build/BundleStatus.java`). Table `agent_bundle` (`backend/src/main/resources/db/migration/V23__bundles_and_builds.sql`).
* **`TemplateBuild`** (`backend/src/main/java/com/agentstore/platform/build/TemplateBuild.java`): One attempt to turn a bundle into an image on one backend, for one `AgentVersion`. Fields: `backend`, `runtime`, `entrypoint`, `imageRef`, `failureCode`, `failureReason`, `logStorageKey` (`builds/{buildId}.log`), `attempts`, `queuedAt`, `startedAt`, `finishedAt`. `status`: `[QUEUED | BUILDING | SUCCEEDED | FAILED]` (`BuildStatus`, `backend/src/main/java/com/agentstore/platform/build/BuildStatus.java`). The class and table (`template_build`) keep the name from the design spec; elsewhere the code says build, image and imageRef.
* **Failure codes** (`backend/src/main/java/com/agentstore/platform/build/BuildFailureCodes.java`, runner side `runner/src/main/java/com/agentstore/runner/backend/BuildFailedException.java`): `[SECRET_FOUND | DEP_VULN | INSTALL_FAILED | SMOKE_FAILED | TIMEOUT | RUNNER_RESTARTED | INVALID_BUNDLE | BUILD_FAILED | SUPERSEDED]`. `SUPERSEDED` exists only in Spring; the runner never returns it.
* **`ObjectStorage`** (`backend/src/main/java/com/agentstore/platform/storage/ObjectStorage.java`): The S3-compatible object store (SeaweedFS in dev, S3 API only, one bucket `agentstore`) reached through the AWS SDK v2 S3 client (path-style, endpoint override). It hands out pre-signed PUT and GET URLs so the CLI and the runner move bytes without credentials. Config: `agentstore.storage.{endpoint,region,access-key,secret-key,bucket}`.
* **`BundleService`** (`backend/src/main/java/com/agentstore/platform/build/BundleService.java`): `createUpload` (creates an `UPLOADING` `AgentBundle` and a 15-minute pre-signed PUT) and `finalizeBundle` (checks sha256, size and manifest, then creates or updates the `DRAFT` `AgentVersion` and queues a `TemplateBuild`). Reached through `BundleController` (`backend/src/main/java/com/agentstore/platform/build/BundleController.java`); readable rejections go through `BuildApiErrors` (`backend/src/main/java/com/agentstore/platform/build/BuildApiErrors.java`). `BundleInspector` (`backend/src/main/java/com/agentstore/platform/build/BundleInspector.java`) reads the zip as a stream.
* **`BuildDispatcher` / `BuildPoller` / `BuildTransitions`** (`backend/src/main/java/com/agentstore/platform/build/BuildDispatcher.java`, `backend/src/main/java/com/agentstore/platform/build/BuildPoller.java`, `backend/src/main/java/com/agentstore/platform/build/BuildTransitions.java`): The dispatcher sends the oldest `QUEUED` build to the runner when none is `BUILDING`. The poller copies runner results into the build and its version. `BuildTransitions` makes every state change, each under row locks. `BuildJob` (`backend/src/main/java/com/agentstore/platform/build/BuildJob.java`) runs poll then dispatch every `agentstore.builds.poll-interval` (`PT5S`). The runner is reached through `BuildRunnerClient` (`backend/src/main/java/com/agentstore/platform/build/BuildRunnerClient.java`): `HttpBuildRunnerClient` (profile `runner`) or `FakeBuildRunnerClient` (profile `!runner`).
* **`RebuildService` / `PendingBuildRuns` / `RunLauncher`** (`backend/src/main/java/com/agentstore/platform/build/RebuildService.java`, `backend/src/main/java/com/agentstore/platform/execution/PendingBuildRuns.java`, `backend/src/main/java/com/agentstore/platform/execution/RunLauncher.java`): `RebuildService` queues a rebuild of a version's current bundle when the configured backend (`agentstore.runner.backend`) differs from the one that built its image. A run that arrives meanwhile is `PENDING_BUILD`. `PendingBuildRuns` (a `BuildFinishedListener`) starts or fails those runs once the outcome is known. `RunLauncher` starts the sandbox for a `PENDING` run: callback token, env, egress allowlist and the run command.
* **`AgentRuntime`** (backend, `backend/src/main/java/com/agentstore/platform/manifest/AgentRuntime.java`): `[PYTHON_3_12 = python-3.12 | NODE_22 = node-22]` with the entrypoint extensions each accepts and `runCommand(entrypoint)`, which is `["sh", "-c", "cd /workspace/app && exec <interpreter> '<entrypoint>'"]`. It must match the runner's `AgentRuntime` (`runner/src/main/java/com/agentstore/runner/backend/AgentRuntime.java`, which adds the base image: `python:3.12-slim`, `node:22-slim`).

### Execution
* **`GateRequest`** (`backend/src/main/java/com/agentstore/platform/execution/GateRequest.java`): One approval request an agent raised through `POST /runtime/v1/gates`: `action` (a declared manifest gate `trigger`), `summary`, `payload`, `affectsResources`, `status`: `[PENDING | APPROVED | REJECTED | EXPIRED]` (`GateStatus`), `decidedByUserId`, `decisionNote`, `expiresAt` (created + the gate's `timeoutMinutes`, default 30). At most one PENDING gate per run; a run that ends for any reason (`RunLifecycleService.finish`) marks its still-PENDING gate `EXPIRED`. Table `gate_request` (migration `V26__gate_request.sql`). `GateService` opens, decides, awaits and expires gates under the run's row lock.
* **`Run`** now also carries `mode`: `[PRODUCTION | SANDBOX | REVIEW | TRIAL]` (`RunMode`; only PRODUCTION runs exist until WS6), `violationCount` (PRODUCTION runs stop at 3 scope violations) and `inputJson` (migration `V25__runtime_api.sql`). `RunStep` now carries `source`: `[PLATFORM | AGENT]` (`RunStepSource`): PLATFORM steps are what the platform itself observed; AGENT steps (legacy events, `AGENT_LOG`) are the agent's own word. New step kinds: `DATA_READ`, `DATA_WRITE`, `WRITE_STAGED`, `GATE_REQUESTED`, `GATE_DECIDED`, `LLM_CALL`, `USAGE`, `SCOPE_VIOLATION`, `AGENT_LOG`.
* **`RunPrincipal`** (`backend/src/main/java/com/agentstore/platform/runtime/RunPrincipal.java`): The authenticated identity of a `/runtime/v1` call: `(runId, orgId, mode, manifest, grantedScopes)`. A scope is granted when the manifest declares it **and** the buyer's connection lists its resource in `grantedScopes`.
* **`Connection`** (`backend/src/main/java/com/agentstore/platform/execution/Connection.java`): A buyer's consented installation of an `Agent`: `buyerOrg`, `agent`, pinned `agentVersion`, `grantedScopes` (resource strings), `spendCap`. `status`: `[ACTIVE | REVOKED]` (`backend/src/main/java/com/agentstore/platform/execution/ConnectionStatus.java`).
* **`Secret`** (`backend/src/main/java/com/agentstore/platform/execution/Secret.java`): A named credential attached to a `Connection`, stored AES-GCM encrypted (`encryptedValue` + `nonce`).
* **`Run`** (`backend/src/main/java/com/agentstore/platform/execution/Run.java`): One execution of a `Connection`'s pinned `AgentVersion`. Fields: `costCap`, `totalCharged`, `reservedAmount`, `sandboxId`, `callbackTokenHash`, `deadlineAt`, `lastHeartbeatAt`, `awaitingSince`. `status`: `[PENDING | PENDING_BUILD | RUNNING | AWAITING_APPROVAL | COMPLETED | FAILED | CANCELLED | TIMED_OUT | EXPIRED]` (`backend/src/main/java/com/agentstore/platform/execution/RunStatus.java`). `PENDING_BUILD` means the run waits for a rebuild of its version and is not terminal. The terminal statuses are `[COMPLETED | FAILED | CANCELLED | TIMED_OUT | EXPIRED]`.
* **`RunStep`** (`backend/src/main/java/com/agentstore/platform/execution/RunStep.java`): An append-only event on a `Run` with a JSON `detail`. `kind`: `[READ | WRITE | GATE | COST | COMPLETE | FAILED | TIMEOUT | COST_CAP_REACHED]` (`backend/src/main/java/com/agentstore/platform/execution/RunStepKind.java`).
* **`SandboxProvider`** (`backend/src/main/java/com/agentstore/platform/execution/SandboxProvider.java`): The backend's vendor-neutral sandbox seam (`create`/`kill`/`status`). Implementations: `RunnerSandboxProvider` (`backend/src/main/java/com/agentstore/platform/execution/RunnerSandboxProvider.java`, profile `runner`) and `FakeSandboxProvider` (`backend/src/main/java/com/agentstore/platform/execution/FakeSandboxProvider.java`, profile `!runner`).
  * **`SandboxSpec`** (`backend/src/main/java/com/agentstore/platform/execution/SandboxSpec.java`): The create request. `requestId` is the run id.
  * **`SandboxStatus`** (`backend/src/main/java/com/agentstore/platform/execution/SandboxStatus.java`): `state`: `[RUNNING | EXITED | GONE | UNKNOWN]`, plus `exitCode`.

### Data plane
* **`Dataset`** (`backend/src/main/java/com/agentstore/platform/data/Dataset.java`): A buyer's file in the workspace library: `ownerOrgId`, `purpose` `[BUYER_DATA | FIXTURE | SAMPLE | EVAL]` (`DatasetPurpose`; only BUYER_DATA is created until WS6/8/9), `displayName`, `sourceLabel`, `format` `[CSV | JSON | PDF]` (`DataFormat`, `backend/src/main/java/com/agentstore/platform/manifest/DataFormat.java`), the original (`storageKey`) and, for CSV/JSON, a normalized JSONL copy (`recordsKey`, one object per line), `sha256`, `sizeBytes`, `recordsSizeBytes`, `rowCount`, `columns`, and the retention stamps `deletedAt`, `purgeAfter`, `purgedAt`. A purged dataset stays as a tombstone (name, size, sha; keys null). Table `dataset` (migration `V27__datasets.sql`, which also adds `org.storage_limit_bytes`, default 5 GiB).
* **`DatasetBinding`** (`backend/src/main/java/com/agentstore/platform/data/DatasetBinding.java`): A file plugged into one resource of one owner (`ownerType` `[CONNECTION | SANDBOX_PROFILE | EVAL_CASE | TRIAL]`, only CONNECTION today), unique per `(ownerType, ownerId, resourceName)`. Keeps the `checkResult` from bind time. Table `dataset_binding`.
* **`RunDatasetPin`** (`backend/src/main/java/com/agentstore/platform/data/RunDatasetPin.java`): The file a run reads for one resource, fixed when the run is created (primary key `(runId, resourceName)`, plus the file's `sha256`). Table `run_dataset_pin`.
* **`RunOutput`** (`backend/src/main/java/com/agentstore/platform/data/RunOutput.java`): One agent write call, stored as a JSONL blob `outputs/{runId}/{outputId}.jsonl`. `status`: `[STAGED | COMMITTED | DISCARDED]` (`OutputStatus`), `recordCount`, `sizeBytes`, `gateRequestId` (the gate that committed it). Table `run_output` (migration `V28__run_output.sql`).
* **`ResourceDecl` / `ResourceSchema`** (`backend/src/main/java/com/agentstore/platform/manifest/ResourceDecl.java`): The manifest's optional `resources[]`: `name`, `format`, and an inline `schema` `(required[], properties{col: [string | number | integer | boolean | date]})`, absent for a pdf. `ResourceManifestRules` holds the cross-field rules.
* **`DatasetService` / `DatasetCheckService` / `SchemaChecker` / `ValueCoercer`** (`backend/src/main/java/com/agentstore/platform/data/`): Upload (streamed to a temp file, hashed, normalized by `CsvNormalizer` or `JsonNormalizer`, stored, registered under the org row lock for the quota), list, delete, and the schema check that runs against a stored file and saves nothing.
* **`BindingService` / `BindingResolver`** (`backend/src/main/java/com/agentstore/platform/data/BindingService.java`, `backend/src/main/java/com/agentstore/platform/data/BindingResolver.java`): `BindingService` binds, unbinds and lists (bind runs the schema check). `BindingResolver` runs inside the run trigger: it resolves the pins for every granted READ resource, blocks the trigger with `MISSING_BINDING`, and creates the run and its pins in one transaction. `DataAccessRules` is the mode guard.
* **`DatasetResourceBackend`** (`backend/src/main/java/com/agentstore/platform/data/DatasetResourceBackend.java`): The `ResourceBackend` the runtime controllers call (it replaced `UnavailableResourceBackend`). Reads only the run's pins; `RecordCursor` is the byte-offset page cursor; `RunOutputWriter` validates and stores writes.
* **`OutputCommitService`** (`backend/src/main/java/com/agentstore/platform/data/OutputCommitService.java`): Listens for `GateOutcomeEvent` and `RunFinishedEvent` (`backend/src/main/java/com/agentstore/platform/execution/GateOutcomeEvent.java`, `RunFinishedEvent.java`) and commits or discards staged outputs in the publisher's transaction. `OutputsController` and `OutputFormats` serve them to the buyer.
* **`DataRetentionService` / `DataRetentionJob`** (`backend/src/main/java/com/agentstore/platform/data/DataRetentionService.java`): Hourly (`agentstore.data.retention-interval`, ShedLock `dataRetention`): purges due datasets to tombstones and deletes the blobs of `DISCARDED` outputs older than 30 days.
* **`RuntimeBodyLimitFilter`** (`backend/src/main/java/com/agentstore/platform/runtime/RuntimeBodyLimitFilter.java`): Caps an agent's record-write body at 5 MB at the transport, after `RuntimeAuthFilter`.

### LLM gateway
* **`LlmModel`** (`backend/src/main/java/com/agentstore/platform/llm/LlmModel.java`): An operator-managed model row: `provider` `[ANTHROPIC | OPENAI]` (`LlmVendor`, `backend/src/main/java/com/agentstore/platform/manifest/LlmVendor.java`), per-million-token `inputPricePerMtok`/`outputPricePerMtok`, `currency`, `enabled`, `providerRetainsData`. The migration seeds `claude-sonnet-5` and `claude-haiku-4-5-20251001` disabled with zero prices. Table `llm_model` (migration `V29__llm_gateway.sql`).
* **`LlmCall`** (`backend/src/main/java/com/agentstore/platform/llm/LlmCall.java`): One call the gateway handled. `source` `[PLATFORM | BYOK_PROXY | MOCK | REPLAY]` (`LlmSource`), `status` `[OK | ERROR | BUDGET_REJECTED | REPLAY_MISS]` (`LlmCallStatus`), token counts, `cost` (PLATFORM only), `requestHash`, `replayOrdinal`, recording keys and expiry, our own `errorCode`. Never holds a prompt, a key or a provider body. Table `llm_call`.
* **`ByokCredential`** (`backend/src/main/java/com/agentstore/platform/llm/ByokCredential.java`): A developer org's LLM key for one provider, AES-GCM encrypted (`encryptedKey`, `nonce`) with only `last4` in the clear; unique per `(developerOrgId, provider)`. Table `byok_credential`.
* **`LlmDecl` / `LlmMode`** (`backend/src/main/java/com/agentstore/platform/manifest/LlmDecl.java`, `backend/src/main/java/com/agentstore/platform/manifest/LlmMode.java`): The manifest's optional `llm` block: `mode` `[platform | byok_proxy | byok_direct]`, `models[]`, `maxTokensPerRun`, `provider` (required exactly for `byok_*`). `LlmManifestRules` holds the cross-field rules.
* **`Run` LLM fields** (`backend/src/main/java/com/agentstore/platform/execution/Run.java`): `llmTokensUsed` and `llmTokensReserved` (read-only in JPA; changed only by the atomic statements in `RunRepository`), `llmMode` `[MOCK | REPLAY]` (`RunLlmMode`), `replayRunId`, `llmMockJson`. Migration `V30__run_llm_columns.sql`, which also adds `org.developer_verified_at`.
* **`GatewayLlmBackend`** (`backend/src/main/java/com/agentstore/platform/llm/GatewayLlmBackend.java`): The `LlmBackend` behind `POST /runtime/v1/llm/chat` (it replaced the 501 `UnavailableLlmBackend`). Validates, checks the model against the manifest and `llm_model`, dispatches to mock, replay or a real provider, meters, records the `LlmCall` and an `LLM_CALL` run step.
* **`LlmRequestNormalizer` / `LlmBudget` / `ProviderClient`** (`backend/src/main/java/com/agentstore/platform/llm/`): The normalizer validates and canonicalises a request and hashes it; `LlmBudget` reserves, settles and releases tokens with single conditional `UPDATE`s; `ProviderClient` wraps `AnthropicProvider` / `OpenAiProvider` with one retry, a per-provider circuit breaker and error mapping.
* **`ByokKeyService` / `LlmKeyController` / `LlmKeyResolver`** (`backend/src/main/java/com/agentstore/platform/llm/`): Store, list, delete and reveal developer keys; the resolver picks the platform key or the developer's key per call. `RunLauncher` uses `reveal` for `byok_direct`.
* **`LlmRecorder` / `MockResponder` / `ReplayResponder`** (`backend/src/main/java/com/agentstore/platform/llm/`): Recordings (`llm/{runId}/{callId}/request.json|response.json`, SANDBOX runs only), canned answers for `MOCK` runs, and ordinal-based replay of a source run's recordings.
* **`LlmRecordingRetentionService` / `LlmRecordingRetentionJob`** (`backend/src/main/java/com/agentstore/platform/llm/`): Hourly (`agentstore.llm.retention-interval`, ShedLock `llmRecordingRetention`): deletes recordings past their 14-day expiry and nulls the keys.

### Billing
* **`Wallet`** (`backend/src/main/java/com/agentstore/platform/ledger/Wallet.java`): Prepaid funds per org (unique `orgId`): `balance`, `reserved`, `cap`.
* **`UsageEvent`** (`backend/src/main/java/com/agentstore/platform/ledger/UsageEvent.java`): A metered consumption record (`quantity × unitCost = amount`) for a run and org, with a `processed` flag.
* **`LedgerEntry`** (`backend/src/main/java/com/agentstore/platform/ledger/LedgerEntry.java`): The financial posting made from a `UsageEvent`. `type`: `[CHARGE | REVERSAL | FEE]` (`backend/src/main/java/com/agentstore/platform/ledger/LedgerEntryType.java`).

### Sandbox runner (separate service)
* **`IsolationBackend`** (`runner/src/main/java/com/agentstore/runner/backend/IsolationBackend.java`): The runner's only vendor seam (`create`, `status`, `readLogs`, `kill`, `listOwned`). Also `buildImage(ImageBuild, BuildLog)`, which turns an unpacked app directory into an image reference (`ImageBuild`, `BuildLog`, `BuildFailedException`). Current implementation: `E2bBackend` (`runner/src/main/java/com/agentstore/runner/backend/e2b/E2bBackend.java`), whose `buildImage` calls the Node shim (`runner/e2b-shim/shim.mjs`) to build an E2B template; the returned `imageRef` is the template id.
* **`SandboxState`** (`runner/src/main/java/com/agentstore/runner/backend/SandboxState.java`): `phase`: `[RUNNING | EXITED | GONE | UNKNOWN]`, plus `exitCode`.
* **`OwnedSandbox`** (`runner/src/main/java/com/agentstore/runner/backend/OwnedSandbox.java`): A live sandbox carrying this runner's labels. `requestId` (the run id) and `deadlineEpochSeconds` are read from those labels.
* **`BuildService`** (`runner/src/main/java/com/agentstore/runner/build/BuildService.java`): The runner's build queue: one build at a time, state in memory only, idempotent on `buildId`. Steps: download, unpack, check the entrypoint, secret scan, `IsolationBackend.buildImage`. Status `[QUEUED | BUILDING | SUCCEEDED | FAILED]`. Exposed by `BuildController` (`runner/src/main/java/com/agentstore/runner/api/BuildController.java`).
* **`BundleFetcher`** (`runner/src/main/java/com/agentstore/runner/build/BundleFetcher.java`): Downloads the bundle from its pre-signed URL, at most 100 MB, and checks sha256.
* **`BundleUnpacker`** (`runner/src/main/java/com/agentstore/runner/build/BundleUnpacker.java`): Unpacks the zip into an empty directory and refuses anything that escapes it. Limits: 500 MB unpacked, 20,000 entries. No symlink is ever created.
* **`SecretScanner`** (`runner/src/main/java/com/agentstore/runner/build/SecretScanner.java`): Runs `gitleaks dir --redact` over the unpacked app. A finding fails the build with `SECRET_FOUND`, naming file, line and rule but never the secret. Removes a bundle's own `.gitleaks.toml` and `.gitleaksignore` first.
* **`LogRedactor`** (`runner/src/main/java/com/agentstore/runner/build/LogRedactor.java`): Masks AWS keys, GitHub tokens, `sk-` keys, Slack tokens, private-key headers and URL credentials in every build log line and failure reason.
* **`BudgetGuard`** (`runner/src/main/java/com/agentstore/runner/sandbox/BudgetGuard.java`): Enforces the runner's concurrency cap and monthly sandbox-time ceiling, persisted through `BudgetLedger` (`runner/src/main/java/com/agentstore/runner/sandbox/BudgetLedger.java`).

## 3. Properties & Semantic Relationships

**Tenancy**
- `User` -> **belongs to (many)** -> `Org` via `OrgMembership` (`backend/src/main/java/com/agentstore/platform/identity/OrgMembership.java`)
- `OrgMembership` -> **carries** -> `UserRole`. `OrgType` determines which roles can be assigned (`backend/src/main/java/com/agentstore/platform/identity/OrgRoles.java`).
- `Invitation` -> **on accept, creates** -> `OrgMembership` (and a `User` if the email has no account) (`backend/src/main/java/com/agentstore/platform/identity/InvitationService.java`)
- `JwtService.Claims` -> **scopes every request to** -> one `Org` (`orgId`) and one `UserRole`. `switch-org` issues a new token for a different membership (`backend/src/main/java/com/agentstore/platform/identity/AuthController.java`).

**Publishing**
- `Org[DEVELOPER]` -> **owns** -> `Agent` -> **has many** -> `AgentVersion`
- `AgentVersion` -> **embeds (as JSON)** -> `Manifest` -> **declares** -> `Scope`, `CostEnvelope`, `ApprovalGateDecl`
- `AgentVersion` status transitions (`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`):
  - `DRAFT` -> `submit` -> `LIVE` (`TIER_A`: no `WRITE` scope). Submit needs `imageRef`, and for a version with a bundle also `buildStatus = SUCCEEDED`.
  - `DRAFT` -> `submit` -> `IN_REVIEW` (`TIER_B`: at least one `WRITE` scope), which also creates a `ReviewItem[PENDING]`
  - `IN_REVIEW` -> `ReviewItem` approve -> `LIVE` (sets `approvedAt`)
  - `IN_REVIEW` -> `ReviewItem` reject -> `REJECTED`
- `ReviewItem` -> **gates** -> `AgentVersion`. The reviewer is recorded as `reviewerUserId` (a raw UUID, not a relation).

**Builds**
- `Org[DEVELOPER]` -> **uploads** -> `AgentBundle` (pre-signed PUT from `BundleService.createUpload`) -> **finalize** -> `AgentVersion[DRAFT]` (created, or the existing `DRAFT` of the same `manifest.version`) + `TemplateBuild[QUEUED]` (`backend/src/main/java/com/agentstore/platform/build/BundleService.java`)
- `TemplateBuild` -> **belongs to** -> one `AgentVersion` and one `AgentBundle`. `AgentVersion.bundle` points at the bundle its current image comes from.
- `BuildDispatcher` -> **sends** -> the oldest `QUEUED` `TemplateBuild` to the runner's `/builds` through `BuildRunnerClient` (pre-signed bundle GET and log PUT, 1 hour each) -> `BuildTransitions.markBuilding`
- `BuildPoller` -> **polls** -> the runner for each `BUILDING` build -> `BuildTransitions.succeed` or `fail` -> **notifies** -> `BuildFinishedListener`s (`PendingBuildRuns`)
- `TemplateBuild` status transitions (`backend/src/main/java/com/agentstore/platform/build/BuildTransitions.java`):
  - `QUEUED` -> dispatch -> `BUILDING` (`attempts` + 1)
  - `BUILDING` -> runner `SUCCEEDED` -> `SUCCEEDED` (sets `imageRef`, and the version's `imageRef` and `buildStatus`, but only if the build is for the version's current bundle)
  - `BUILDING` or `QUEUED` -> `fail` -> `FAILED` with a `failureCode`
  - `BUILDING` -> runner `RUNNER_RESTARTED` with fewer than 3 attempts -> `QUEUED` again
  - `QUEUED` -> newer bundle finalized for the same version -> `FAILED` (`SUPERSEDED`)
- Build result -> **updates** -> `AgentVersion.buildStatus`. A version that already has a working image stays `SUCCEEDED` while a rebuild runs or fails.
- `Run` trigger -> **calls** -> `RebuildService.needsRebuild` -> if the version's last successful build was on another backend, the run is created as `PENDING_BUILD` and `queueRebuild` queues one build (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`)
- `PENDING_BUILD` run -> `PendingBuildRuns.settle` -> `PENDING` -> `RunLauncher.launch` -> `RUNNING` when the version's current bundle has a built image on the configured backend. -> `FAILED` when it has none and no such build is queued or building.

**Execution**
- `Org[BUYER]` -> **creates** -> `Connection` -> **to** -> `Agent`, **pinned to** -> the latest `LIVE` `AgentVersion` at creation time (`backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java`)
- `Connection` -> **holds** -> `Secret` (0..n, unique by `name`)
- `Connection` -> **spawns** -> `Run`. `Run.agentVersion` = `Connection.agentVersion` and `Run.costCap` = `Connection.spendCap` (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`).
- `Run` -> **executes in** -> a sandbox (`Run.sandboxId`) through `SandboxProvider` -> `RunnerSandboxProvider` -> the runner's `SandboxController` -> `SandboxService` -> `IsolationBackend`
- `RunLauncher` -> **starts the sandbox with** -> the image `AgentVersion.imageRef` and the command `AgentRuntime.runCommand(manifest.entrypoint)`. A version without a manifest `runtime` uses `agentstore.runs.agent-command` instead.
- `Secret` -> **is injected as** -> the env var `AGENT_SECRET_<name>`, alongside `AGENT_RUN_ID`, `AGENT_CALLBACK_URL` and `AGENT_CALLBACK_TOKEN` (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`)
- The agent in the sandbox -> **reports** -> `RunStep`s through the callback API (`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java`)
- `Run` status transitions (all under a row lock in `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`):
  - created as `PENDING` -> `markStarted` -> `RUNNING` (after `RunLauncher.launch` creates the sandbox)
  - created as `PENDING_BUILD` (version needs a rebuild) -> `promotePendingBuild` -> `PENDING`
  - `RUNNING` -> agent `GATE` event -> `AWAITING_APPROVAL`
  - `AWAITING_APPROVAL` -> buyer approve -> `RUNNING`
  - `AWAITING_APPROVAL` -> buyer reject -> `CANCELLED`
  - any non-terminal status -> agent `COMPLETE` -> `COMPLETED`
  - any non-terminal status -> agent `FAILED` -> `FAILED`
  - any non-terminal status -> spend exceeds `costCap` -> `FAILED` (+ `COST_CAP_REACHED` step)
  - launch or sandbox-create failure -> `FAILED`
  - `RunReaper` (`backend/src/main/java/com/agentstore/platform/execution/RunReaper.java`):
    - `AWAITING_APPROVAL` past the gate timeout or the deadline -> `EXPIRED`
    - other active run past `deadlineAt` -> `TIMED_OUT` (a `PENDING_BUILD` run's deadline is 25 minutes after the trigger)
    - `RUNNING` whose sandbox has stopped -> `FAILED`

**Data plane**
- `Dataset` -> **is owned by** -> `Org`. `DatasetBinding` -> **plugs** -> a `Dataset` into `(Connection, resource)`.
- `Manifest` -> **declares** -> `ResourceDecl` (optional `resources[]`). `Run` -> **pins** -> one `Dataset` per granted READ resource (`RunDatasetPin`). `RunOutput` -> **belongs to** -> `Run`, and is **committed by** -> a `GateRequest`.
- Upload -> `FormatDetector` -> `CsvNormalizer` / `JsonNormalizer` -> original + JSONL in object storage -> `Dataset` row. The file is untyped: nothing is checked until it is bound.
- Bind -> `DatasetCheckService.check` against the connection's pinned `AgentVersion` manifest -> `DatasetBinding` (with `checkResult`); a replaced or unplugged file that is bound nowhere else gets `purgeAfter = now + 30 days`.
- Run trigger -> `BindingResolver.resolve` -> `RunDatasetPin`s written in the run's transaction (`saveWithPins`).
- Agent read -> `RuntimeResourceController` -> `ScopeGuard` -> `DatasetResourceBackend` -> the run's pin -> ranged read of the JSONL. Agent write -> `RunOutputWriter` -> `run_output` row `STAGED` (scope `requiresApproval`) or `COMMITTED`.
- `GateService.decide` and `RunLifecycleService.finish` -> **publish** -> `GateOutcomeEvent` -> `OutputCommitService` -> `STAGED` becomes `COMMITTED` (approved) or `DISCARDED` (rejected, expired). `RunLifecycleService.finish` also publishes `RunFinishedEvent` -> remaining `STAGED` become `DISCARDED`.

**LLM gateway**
- `Manifest` -> **declares** -> `LlmDecl` (optional `llm` block); a `byok_*` mode makes the version `TIER_B`
- `LlmCall` -> **belongs to** -> `Run` (raw `runId`) and -> **uses** -> `LlmModel` (`modelId`)
- `ByokCredential` -> **is owned by** -> the developer `Org` (raw `developerOrgId`)
- `Run` -> **replays** -> another `Run` (`replayRunId`); `Org.developerVerifiedAt` is null until the verification workflow (WS7) sets it

**Billing**
- `Run` trigger -> **reserves** -> `Connection.spendCap` on `Wallet.reserved` (`backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java`)
- A `COST` `RunStep` -> **creates** -> `UsageEvent` -> **is charged into** -> `LedgerEntry[CHARGE]` -> **debits** -> `Wallet.balance` (`backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java`)
- A charge -> **releases** -> the matching part of `Run.reservedAmount`. When a run finishes, whatever is still reserved is released.
- `UsageEvent` and `LedgerEntry` -> **reference** -> `Run` and `Org` by raw `runId`/`orgId` UUIDs, not JPA relations.

**Runner**
- `SandboxService` -> **labels each sandbox with** -> `REQUEST_ID` (run id), `RUNNER_ID` and `DEADLINE` (`runner/src/main/java/com/agentstore/runner/sandbox/SandboxService.java`)
- `RunnerReaper` -> **reconciles against** -> the backend's `/internal/runs/active-ids` through `SpringRunsClient` (`runner/src/main/java/com/agentstore/runner/sandbox/RunnerReaper.java`, `runner/src/main/java/com/agentstore/runner/sandbox/SpringRunsClient.java`, `backend/src/main/java/com/agentstore/platform/execution/InternalRunsController.java`)

## 4. Operational Axioms & Logical Constraints

**Identity**
- **Rule OrgRoleAssignability:** A `BUYER` org can assign only `[BUYER_ADMIN | BUYER_APPROVER]`. A `DEVELOPER` org can assign only `[DEVELOPER]`. `ADMIN` can never be assigned through org role management. (`backend/src/main/java/com/agentstore/platform/identity/OrgRoles.java`)
- **Rule MemberManagers:** Only `BUYER_ADMIN` or `DEVELOPER` can manage members. The check reads the role from the database, not from the JWT, so a demoted member loses access immediately. (`backend/src/main/java/com/agentstore/platform/identity/MembersController.java`)
- **Rule LastAdmin:** A demotion or removal that would leave an org with zero holders of its admin role (`BUYER_ADMIN` for `BUYER`, `DEVELOPER` for `DEVELOPER`) fails with 409. The org row is locked first. (`backend/src/main/java/com/agentstore/platform/identity/MembersController.java`)
- **Rule UniqueEmail:** Signing up with an email that already exists (case-insensitive) fails with 409. (`backend/src/main/java/com/agentstore/platform/identity/IdentityService.java`)
- **Rule ApiKeyScope:** A request authenticated by an API key may reach only `POST /api/v1/agents`, `GET /api/v1/agents/mine`, `/api/v1/agents/*/versions/**` except `…/versions/*/submit`, `/api/v1/agents/*/bundles/**` and `/api/v1/agents/*/builds/**`. Anything else answers 403, including key management, wallet, buyer, admin and identity endpoints. The allowlist is `ApiKeyScope` (`backend/src/main/java/com/agentstore/platform/identity/ApiKeyScope.java`), enforced by `ApiKeyAuthFilter` before dispatch, so it also covers `permitAll` paths.
- **Rule ApiKeyRole:** A key always acts as `DEVELOPER` in its own org, never as its creator's role. It is rejected (401) when revoked, unknown, or when its creator no longer holds a `DEVELOPER` `OrgMembership` in the key's org. (`backend/src/main/java/com/agentstore/platform/identity/ApiKeyAuthFilter.java`)
- **Rule ApiKeyShownOnce:** `POST /api/v1/developer/api-keys` returns the plaintext key exactly once; only its hash is stored and the list endpoint never returns it. `last_used_at` is written at most once a minute per key.
- **Rule AdminPromotion:** A password signup never grants `ADMIN` directly. `ADMIN` is granted only for `DEVELOPER`-side orgs, only to emails in `agentstore.identity.admin-emails`, and only after inbox control has been proven. (`backend/src/main/java/com/agentstore/platform/identity/IdentityService.java`)
- **Rule InvitationUsable:** An invitation can be used only when `acceptedAt == null && revokedAt == null && expiresAt > now`. The TTL is 7 days. (`backend/src/main/java/com/agentstore/platform/identity/Invitation.java`, `backend/src/main/java/com/agentstore/platform/identity/InvitationService.java`)
- **Rule InvitationIdentity:** A signed-in caller must be the invited email's account (otherwise 403). An anonymous caller whose email already has an account gets 409. A brand-new user must supply `fullName` and `password` (otherwise 400). (`backend/src/main/java/com/agentstore/platform/identity/InvitationService.java`)
- **Rule RefreshReuse:** Presenting a refresh token that was already revoked revokes its entire token family and returns 401. (`backend/src/main/java/com/agentstore/platform/identity/RefreshTokenService.java`)
- **Rule GoogleLinking:** A Google sign-in to an existing unverified account fails with `ACCOUNT_EXISTS_UNVERIFIED`. A Google sign-in to an account already linked to another Google subject fails with `ACCOUNT_LINKED_TO_OTHER_GOOGLE`. A new user without `side` and `orgName` gets `SIGNUP_DETAILS_REQUIRED`. (`backend/src/main/java/com/agentstore/platform/identity/GoogleSignInService.java`)

**Publishing**
- **Rule ManifestSchema:** `manifestJson` must pass the JSON Schema in `backend/src/main/resources/manifest/manifest-schema.json`. It is checked both when a version is created and when it is submitted. (`backend/src/main/java/com/agentstore/platform/manifest/ManifestValidator.java`)
- **Rule SubmitOnlyDraft:** Only a `DRAFT` version owned by the caller's org can be submitted. (`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`)
- **Rule TierAssignment:** `tier = TIER_B` if any `Scope.type == WRITE` or the manifest's `llm.mode` is `byok_proxy`/`byok_direct`, otherwise `TIER_A`. `TIER_A` goes straight to `LIVE`. `TIER_B` requires review. (`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`)
- **Rule ReviewOnce:** Only a `PENDING` `ReviewItem` can be decided. (`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`)
- **Rule VersionVisibility:** A non-`LIVE` version is visible only to its owning `DEVELOPER` org or to `ADMIN`. (`backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`)
- **Rule CatalogIsLive:** The public catalog lists only `LIVE` versions. (`backend/src/main/java/com/agentstore/platform/catalog/CatalogListingController.java`)

**Builds**
- **Rule BundleLimits:** A bundle is at most 100 MB (`104_857_600` bytes), has at most 20,000 zip entries and a `manifest.json` of at most 256 KiB at its root. The runner also refuses more than 500 MB unpacked. Violations reject the bundle (422 `{code: INVALID_BUNDLE, message}` from `BuildApiErrors`) or fail the build with `INVALID_BUNDLE`. (`backend/src/main/java/com/agentstore/platform/build/BundleService.java`, `backend/src/main/java/com/agentstore/platform/build/BundleInspector.java`, `runner/src/main/java/com/agentstore/runner/build/BundleUnpacker.java`)
- **Rule BundleManifest:** `manifest.json` must pass the manifest schema and `BundleManifestRules`: `runtime` is `python-3.12` or `node-22`; `entrypoint` matches `^[A-Za-z0-9_][A-Za-z0-9_./-]*$`, has no `..` segment and no `//`, ends in an extension the runtime accepts (`.py`; `.js`, `.mjs`, `.cjs`) and names a file in the bundle. (`backend/src/main/java/com/agentstore/platform/manifest/BundleManifestRules.java`)
- **Rule FinalizeIdempotent:** A retried or concurrent `finalize` of one bundle returns the same version and build and queues one build. Locks are taken in the order agent row, then bundle row. (`backend/src/main/java/com/agentstore/platform/build/BundleService.java`)
- **Rule SubmittedIsFrozen:** Finalizing a bundle whose `manifest.version` is already submitted (`IN_REVIEW`, `LIVE` or `REJECTED`) rejects the bundle. Pushing to a `DRAFT` replaces its bundle and clears its `imageRef`. (`backend/src/main/java/com/agentstore/platform/build/BundleService.java`)
- **Rule StaleBuildNeverWins:** A build result reaches its version only while the build's bundle is still the version's current bundle. Every build transition locks the agent row, then the build row, the same order as finalize and submit. (`backend/src/main/java/com/agentstore/platform/build/BuildTransitions.java`)
- **Rule OneBuildAtATime:** The dispatcher sends nothing while a build is `BUILDING`, and the runner runs one build at a time. (`backend/src/main/java/com/agentstore/platform/build/BuildDispatcher.java`, `runner/src/main/java/com/agentstore/runner/build/BuildService.java`)
- **Rule BuildTimeouts:** The runner's whole build has a timeout of `PT15M`, and inside it the E2B template build has `PT14M`. Spring's safety timeout for a build still `BUILDING` is `PT20M` (`TIMEOUT`). An unreachable runner never fails a build on its first failed poll. (`backend/src/main/java/com/agentstore/platform/build/BuildPoller.java`, `runner/src/main/resources/application.yml`)
- **Rule RunnerRestartRetry:** The runner answers an unknown `buildId` with `FAILED` / `RUNNER_RESTARTED`. Spring re-queues such a build up to 3 attempts in all, then fails it with `RUNNER_RESTARTED`. (`backend/src/main/java/com/agentstore/platform/build/BuildPoller.java`, `runner/src/main/java/com/agentstore/runner/build/BuildService.java`)
- **Rule SubmitNeedsBuild:** A version with a bundle can be submitted only when `buildStatus = SUCCEEDED`; any version needs an `imageRef`. (`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`)
- **Rule RebuildOnBackendSwitch:** If the backend that built a version's image differs from `agentstore.runner.backend`, a trigger creates the run as `PENDING_BUILD` and queues one rebuild of the same bundle. Versions without a bundle are never rebuilt. (`backend/src/main/java/com/agentstore/platform/build/RebuildService.java`, `backend/src/main/java/com/agentstore/platform/execution/RunController.java`)
- **Rule PendingBuildSettles:** A `PENDING_BUILD` run resumes when its version's current bundle has a built image on the configured backend, and fails when it has none and no build of that bundle on the backend is still queued or building. While one is on its way it waits, bounded by its own deadline. (`backend/src/main/java/com/agentstore/platform/execution/PendingBuildRuns.java`)
- **Rule NoSecretsInBundle:** gitleaks findings fail the build with `SECRET_FOUND`. Reports carry file, line and rule, never the secret. Build logs and failure reasons pass through `LogRedactor`. Pre-signed URLs are never logged, put in exception messages or printed by `toString()`. (`runner/src/main/java/com/agentstore/runner/build/SecretScanner.java`, `runner/src/main/java/com/agentstore/runner/build/LogRedactor.java`)
- **Rule HostileZip:** A zip entry with `../`, an absolute or backslash path, a duplicate path or too many entries, or a zip bomb fails the build with `INVALID_BUNDLE` and writes nothing outside the build directory. (`runner/src/main/java/com/agentstore/runner/build/BundleUnpacker.java`)
- **Rule BuildRecipe:** Python: `pip install -r requirements.txt`, `pip-audit` (`DEP_VULN`), then import the entrypoint (`SMOKE_FAILED`). Node: `npm ci --omit=dev` (needs `package-lock.json`), `npm audit --omit=dev --audit-level=high` (`DEP_VULN`), then `node --check` (`SMOKE_FAILED`). The app lives at `/workspace/app`. E2B template builds cannot restrict egress (accepted for development only). (`runner/e2b-shim/shim.mjs`)
- **Rule ObjectKeysAndTtl:** Object keys are `bundles/{agentId}/{bundleId}.zip` and `builds/{buildId}.log`. Pre-signed URL lifetimes: upload PUT 15 minutes, runner bundle GET and log PUT 1 hour, portal log GET 15 minutes. (`backend/src/main/java/com/agentstore/platform/build/BundleService.java`, `backend/src/main/java/com/agentstore/platform/build/BuildDispatcher.java`, `backend/src/main/java/com/agentstore/platform/build/BuildSummaries.java`)

**Connections & runs**
- **Rule ScopeSubset:** Every `grantedScopes` entry must be a `Scope.resource` declared by the agent's latest `LIVE` manifest. `spendCap` must be positive. (`backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java`, `backend/src/main/java/com/agentstore/platform/execution/CreateConnectionRequest.java`)
- **Rule TriggerPreconditions:** A run can be triggered only when:
  - the `Connection` belongs to the caller's org
  - its status is `ACTIVE`
  - it is pinned to an `AgentVersion`
  - that version is still `LIVE`
  - the `Wallet` can reserve `spendCap`, i.e. `balance - reserved >= spendCap`, otherwise 402

  (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`, `backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java`)
- **Rule SingleTerminalPath:** `RunLifecycleService.finish` is the only way a run becomes terminal. It releases any remaining reservation, sets `completedAt`, and kills the sandbox (best effort). (`backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`)
- **Rule TerminalIsFinal:** A terminal run rejects agent events with 409. `terminate` on a terminal run is a no-op that returns false. (`backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`)
- **Rule AgentReportableKinds:** Agents may report only `[READ | WRITE | GATE | COST | COMPLETE | FAILED]`. `[TIMEOUT | COST_CAP_REACHED]` are platform-only, and reporting them returns 400. (`backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`)
- **Rule CostCap:** A `COST` charge is `quantity × manifest.costEnvelope.unitPrice` rounded HALF_UP to 2 decimal places, clamped to `costCap - totalCharged`. If the requested amount exceeds what remains, the run becomes `FAILED` with a `COST_CAP_REACHED` step. `quantity` must be a positive number (otherwise 400). (`backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`)
- **Rule GateDecision:** Only `BUYER_APPROVER` in the owning org can approve or reject, and only while the run is `AWAITING_APPROVAL` (otherwise 409). (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`, `backend/src/main/java/com/agentstore/platform/execution/RunLifecycleService.java`)
- **Rule GateStatusMapping:** The agent-facing gate decision is `[PENDING | APPROVED | REJECTED]`:
  - `AWAITING_APPROVAL` -> `PENDING`
  - `RUNNING` or `COMPLETED` -> `APPROVED`
  - every other status -> `REJECTED`

  (`backend/src/main/java/com/agentstore/platform/execution/GateStatusResponse.java`)
- **Rule CallbackAuth:** Agent callbacks must send `Authorization: Bearer <token>` that matches `Run.callbackTokenHash`, otherwise 401. (`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java`)
- **Rule StaleReaperSafe:** The reaper terminates a run only if the locked status still equals the status in its snapshot, so it never overwrites a concurrent approval or callback. (`backend/src/main/java/com/agentstore/platform/execution/RunReaper.java`)
- **Rule UnknownIsNotStopped:** `SandboxStatus.UNKNOWN` (runner unreachable) must never be treated as stopped. Only `EXITED` or `GONE` count as stopped. (`backend/src/main/java/com/agentstore/platform/execution/SandboxStatus.java`)
- **Rule SandboxLimits:** Backend requests `cpu=1`, `memoryMb=1024` and `timeoutSeconds=600`. The runner clamps the timeout to `max-lifetime` (default `PT10M`). (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`, `runner/src/main/java/com/agentstore/runner/sandbox/SandboxService.java`, `runner/src/main/resources/application.yml`)
- **Rule EgressAllowlist:** Sandbox outbound traffic is limited to the callback host plus `Manifest.networkAllowlist`. (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`)

**Data plane**
- **Rule FilesAreUntypedUntilBound:** An upload is checked against nothing. Binding runs the schema check for the resource and stores it as `check_result`; a failing check is `422 DATASET_CHECK_FAILED` with the result. `required` means the column is present, not that every cell has a value. (`backend/src/main/java/com/agentstore/platform/data/BindingService.java`, `backend/src/main/java/com/agentstore/platform/data/SchemaChecker.java`)
- **Rule BindOnlyReadableGranted:** Only a declared READ resource that the connection grants can be bound; a WRITE or undeclared resource is `422 RESOURCE_NOT_BINDABLE`, an ungranted one `422 RESOURCE_NOT_GRANTED`. A pdf resource is READ only (manifest validation). (`backend/src/main/java/com/agentstore/platform/data/ResourceLookup.java`, `backend/src/main/java/com/agentstore/platform/manifest/ResourceManifestRules.java`)
- **Rule MissingBindingBlocksTrigger:** A declared, granted READ resource with no usable binding fails the trigger with `422 MISSING_BINDING` before money is reserved. A manifest without `resources` is untyped and never blocked. (`backend/src/main/java/com/agentstore/platform/data/BindingResolver.java`, `backend/src/main/java/com/agentstore/platform/execution/RunController.java`)
- **Rule RunsReadOnlyTheirPins:** A run reads only its `run_dataset_pin` rows, so unplugging, replacing or purging a file mid-run cannot change what the agent reads. A file an open run pins cannot be deleted (`409 DATASET_IN_USE`) or purged. (`backend/src/main/java/com/agentstore/platform/data/DatasetResourceBackend.java`, `backend/src/main/java/com/agentstore/platform/data/DatasetService.java`)
- **Rule BuyerDataOnlyForProduction:** A `BUYER_DATA` dataset is resolved only for a PRODUCTION run; SANDBOX, REVIEW and TRIAL runs may only resolve FIXTURE, SAMPLE or EVAL data. The resolver and the backend (on every read, `403 DATA_MODE_MISMATCH`) both enforce it. (`backend/src/main/java/com/agentstore/platform/data/DataAccessRules.java`)
- **Rule GateCommitsWhatItListed:** An approved gate commits only this run's `STAGED` outputs for the resources in its `affects_resources` that were staged before the gate was requested; an approved gate that lists none commits nothing. Rejection and expiry discard the same rows. (`backend/src/main/java/com/agentstore/platform/data/OutputCommitService.java`, `backend/src/main/java/com/agentstore/platform/execution/GateService.java`)
- **Rule TerminalRunDiscardsStaged:** Every run that reaches a terminal state discards its remaining `STAGED` outputs, including an agent that never asked for approval. (`backend/src/main/java/com/agentstore/platform/data/OutputCommitService.java`, `RunLifecycleService.finish`)
- **Rule WriteCaps:** An agent write is validated whole against the resource schema (`422 VALIDATION_FAILED`, first 50 row errors), at most 5 MB per call (checked at the transport and again after serialization) and 200 MB per run (`413 WRITE_TOO_LARGE`). A failed write leaves no blob and no row. (`backend/src/main/java/com/agentstore/platform/data/RunOutputWriter.java`, `backend/src/main/java/com/agentstore/platform/runtime/RuntimeBodyLimitFilter.java`)
- **Rule UploadCaps:** 50 MB per file (`413 FILE_TOO_LARGE`, the multipart limit) and the org's storage quota (default 5 GiB, `413 QUOTA_EXCEEDED`, counted as originals plus JSONL copies of non-purged datasets, checked under the org row lock). (`backend/src/main/java/com/agentstore/platform/data/DatasetService.java`)
- **Rule RetentionWindows:** An explicit delete sets `purge_after = now`, so the hourly job purges it within about an hour; unplugging or replacing gives 30 days. A purge deletes the blobs, nulls the keys and sets `purged_at`, keeping the row as a tombstone. `DISCARDED` output blobs are deleted after 30 days; `COMMITTED` outputs are kept. (`backend/src/main/java/com/agentstore/platform/data/DataRetentionService.java`, `backend/src/main/java/com/agentstore/platform/data/BindingService.java`)
- **Rule FilesStreamThroughTheBackend:** File content is served by `GET /runtime/v1/resources/{resource}/file/content` under the run token, never a presigned URL, so agents behind an egress allowlist need no object-store host. (`backend/src/main/java/com/agentstore/platform/runtime/RuntimeResourceController.java`)
- **Rule CsvDownloadNeutralisesFormulas:** A downloaded CSV prefixes an apostrophe to any string cell starting with `=`, `+`, `-`, `@`, tab or CR; real JSON numbers are untouched. (`backend/src/main/java/com/agentstore/platform/data/OutputFormats.java`)
- **Rule DataRoles:** Upload, delete, bind and unbind need `BUYER_ADMIN`; list, check, bindings list and outputs need `BUYER_ADMIN` or `BUYER_APPROVER`. A dataset of another org is 404; a connection or run of another org is 403. Developer API keys cannot reach any of these paths (`ApiKeyScope`).

**LLM gateway**
- **Rule ByokIsTierB:** A `byok_proxy` or `byok_direct` manifest makes the version `TIER_B` and needs a verified developer org (`Org.developerVerifiedAt`), checked at submit (`403 BYOK_NOT_VERIFIED`) and at key upload. (`backend/src/main/java/com/agentstore/platform/publishing/PublishingService.java`, `backend/src/main/java/com/agentstore/platform/llm/ByokKeyService.java`)
- **Rule ModelCheckedPerCall:** Model availability (in the manifest's `llm.models` and an enabled `llm_model` row) is checked on every call, never at submit. (`backend/src/main/java/com/agentstore/platform/llm/GatewayLlmBackend.java`)
- **Rule AtomicTokenBudget:** A call reserves estimated input tokens plus `maxTokens` in one conditional `UPDATE run`, and settles afterwards in a second short transaction, so concurrent calls cannot oversubscribe `maxTokensPerRun` (actual usage can exceed the estimate by one call's estimation error). A failed call releases its reservation and leaves `used` unchanged. No transaction spans a provider call. (`backend/src/main/java/com/agentstore/platform/llm/LlmBudget.java`, `backend/src/main/java/com/agentstore/platform/execution/RunRepository.java`)
- **Rule LlmCostRecordedNotDeducted:** Cost is recorded on the `LlmCall` for `PLATFORM` calls only and is not deducted from the buyer's cost cap. (`backend/src/main/java/com/agentstore/platform/llm/LlmCost.java`)
- **Rule NothingProviderLeaks:** Provider response bodies and keys (platform or developer) never appear in a response, an `llm_call` row, a run step, a recording or a log line. (`backend/src/main/java/com/agentstore/platform/llm/ProviderClient.java`)
- **Rule MockReplayNonProduction:** `llm_mode` can only be set on a non-production run (database `CHECK`). Mock and replay never reserve budget or call a provider, and a replay miss never falls through to a paid call. (`backend/src/main/resources/db/migration/V30__run_llm_columns.sql`, `backend/src/main/java/com/agentstore/platform/llm/ReplayResponder.java`)
- **Rule RecordOnlySandbox:** Only `SANDBOX` runs record prompts and responses, for 14 days (`agentstore.llm.recording-ttl`); production, trial and review runs never store them. (`backend/src/main/java/com/agentstore/platform/llm/LlmRecorder.java`)
- **Rule DirectKeyInjection:** A `byok_direct` run gets `AGENT_SECRET_<PROVIDER>_API_KEY` from the developer's credential, set after the buyer's secrets so they cannot replace it; a missing credential fails the launch with `LLM_KEY_MISSING`, and `POST /llm/chat` on such a run is `400 USE_DIRECT_PROVIDER`. (`backend/src/main/java/com/agentstore/platform/execution/RunLauncher.java`)

**Billing**
- **Rule IdempotentCharge:** `chargeUsageEvent` returns the existing `LedgerEntry` if the `UsageEvent` has already been charged. (`backend/src/main/java/com/agentstore/platform/ledger/LedgerService.java`)
- **Rule NonNegativeReservation:** Reserve and release amounts must be ≥ 0. `reserved` never goes below 0. (`backend/src/main/java/com/agentstore/platform/ledger/WalletReservationService.java`)
- **Rule OrgScopedFinance:** A wallet or statement can be read only by `BUYER_ADMIN` of the same org or by `ADMIN`. (`backend/src/main/java/com/agentstore/platform/ledger/WalletController.java`, `backend/src/main/java/com/agentstore/platform/ledger/StatementController.java`)

**Runner**
- **Rule IdempotentCreate:** Sandbox creation is keyed by `requestId` (the run id). A repeat request returns the existing sandbox id, found in the in-memory map or through `listOwned`. (`runner/src/main/java/com/agentstore/runner/sandbox/SandboxService.java`)
- **Rule RunnerBudget:** Creation is refused with 429 when owned sandboxes ≥ `max-concurrent` (`CAPACITY_EXHAUSTED`) or when this month's sandbox time ≥ `monthly-limit` (`BUDGET_EXHAUSTED`). The month is counted in UTC. (`runner/src/main/java/com/agentstore/runner/sandbox/BudgetGuard.java`, `runner/src/main/java/com/agentstore/runner/api/ApiErrorHandler.java`)
- **Rule OrphanKill:** The runner kills any owned sandbox whose `requestId` is not in the backend's active run ids (`[PENDING | RUNNING | AWAITING_APPROVAL]`), and any sandbox past its `DEADLINE` label. It lists sandboxes before it asks the backend. (`runner/src/main/java/com/agentstore/runner/sandbox/RunnerReaper.java`)
- **Rule RunnerSecret:** Runner endpoints other than `/actuator/health` require `X-Runner-Secret`. The backend's `/internal/runs/active-ids` requires the same secret and rejects when it is unset. (`runner/src/main/java/com/agentstore/runner/api/RunnerSecretFilter.java`, `backend/src/main/java/com/agentstore/platform/execution/InternalRunsController.java`)

## 5. Tool, API, & Function Mappings
Every backend endpoint except `/api/v1/catalog`, `/api/v1/auth/*`, `/api/v1/invitations/{token}*` and `/internal/*` needs a JWT. Role gates are shown as `@PreAuthorize` values.

### Auth & identity
- **`AuthController.signup`**: `POST /api/v1/auth/signup` (`backend/src/main/java/com/agentstore/platform/identity/AuthController.java`)
  - Accepts: `SignupRequest` (`backend/src/main/java/com/agentstore/platform/identity/SignupRequest.java`), all required: `fullName`, `orgName`, `email`, `password`, and `side: [BUYER | DEVELOPER]`
  - Returns: `AuthResponse(token, refreshToken=null)` (`backend/src/main/java/com/agentstore/platform/identity/AuthResponse.java`)
  - Errors: 409 duplicate email
- **`AuthController.login`**: `POST /api/v1/auth/login`
  - Accepts: `LoginRequest` (`backend/src/main/java/com/agentstore/platform/identity/LoginRequest.java`): `email` and `password` are required. `remember`, `side` and `orgName` are optional; `side` and `orgName` are used only when the user has no membership.
  - Returns: `AuthResponse` (`refreshToken` only if `remember`)
  - Errors: 401 bad credentials; 422 `NO_WORKSPACE` (`SignInProblem`, `backend/src/main/java/com/agentstore/platform/identity/SignInProblem.java`)
- **`AuthController.google`**: `POST /api/v1/auth/google`
  - Accepts: `idToken` (required), `side`, `orgName`, `remember`
  - Returns: `AuthResponse`
  - Errors: 503 if Google is not configured; 401 invalid token; 409 or 422 `SignInProblem` codes (see Rule GoogleLinking)
- **`AuthController.switchOrg`**: `POST /api/v1/auth/switch-org`
  - Accepts: `orgId`
  - Returns: `AuthResponse`
  - Errors: 403 not a member
- **`AuthController.refresh`**: `POST /api/v1/auth/refresh`
  - Accepts: `refreshToken`
  - Returns: a new `AuthResponse` (the refresh token is rotated)
  - Errors: 401 expired, unknown or reused token
- **`AuthController.logout`**: `POST /api/v1/auth/logout`. Revokes the token family and returns 204.
- **`AuthController.verifyEmail`**: `POST /api/v1/auth/email/verify`
  - Errors: 400 invalid or expired link
- **`AuthController.resendVerification`**: `POST /api/v1/auth/email/resend`
- **`AuthController.forgotPassword`**: `POST /api/v1/auth/password/forgot`
- **`AuthController.resetPassword`**: `POST /api/v1/auth/password/reset`
  - Errors: 400 invalid or expired link
  - Password length is 8–200.
  - Token TTLs: verify 24h, reset 1h.
- **`MeController.me`**: `GET /api/v1/me` (`backend/src/main/java/com/agentstore/platform/identity/MeController.java`)
  - Returns: `MeResponse` (user, `emailVerified`, current `orgId`, memberships)
- **`MeController.addOrg`**: `POST /api/v1/me/orgs`
  - Accepts: `side` and `orgName` (both required)
  - Returns: `AuthResponse` for the new org, with the side's admin role
- **`MembersController`**: `/api/v1/orgs/{orgId}/…` (`backend/src/main/java/com/agentstore/platform/identity/MembersController.java`)
  - `GET members` (same org)
  - `PATCH members/{userId}`: accepts `role` and `title`
  - `DELETE members/{userId}`
  - `POST invitations`: accepts `email` (required), `role` (required), `title`
  - `GET invitations`
  - `DELETE invitations/{id}`
  - Errors: 403 wrong org or not a manager; 400 role not assignable; 409 last admin or already a member; 404 member not found
- **`InvitationController`**: `GET /api/v1/invitations/{token}` (preview) and `POST …/accept` (`backend/src/main/java/com/agentstore/platform/identity/InvitationController.java`)
  - Accepts: optional `fullName` and `password`
  - Returns: `AuthResponse`
  - Errors: see Rule InvitationIdentity; 404 or 410 for invalid or unusable tokens

### Developer API keys
- **`DeveloperApiKeyController.create`**: `POST /api/v1/developer/api-keys` [`DEVELOPER`, JWT only] (`backend/src/main/java/com/agentstore/platform/identity/DeveloperApiKeyController.java`)
  - Accepts: `{name}` (not blank, at most 100 characters)
  - Returns: 201 `{id, name, prefix, key, createdAt}`, the only response that contains `key`
- **`DeveloperApiKeyController.list`**: `GET /api/v1/developer/api-keys` [`DEVELOPER`, JWT only]. Returns `[{id, name, prefix, createdAt, lastUsedAt, revokedAt}]` for the caller's org.
- **`DeveloperApiKeyController.revoke`**: `DELETE /api/v1/developer/api-keys/{id}` [`DEVELOPER`, JWT only]. 204, idempotent; 404 for another org's key.
- **`ApiKeyAuthFilter`**: authenticates `Authorization: Bearer ask_live_…` as `JwtService.Claims(createdByUserId, orgId, DEVELOPER)` with authorities `ROLE_DEVELOPER` and `AUTH_API_KEY`.

### LLM keys
- **`LlmKeyController.put`**: `PUT /api/v1/developer/llm-keys/{provider}` [`DEVELOPER`, JWT only] (`backend/src/main/java/com/agentstore/platform/llm/LlmKeyController.java`). Accepts `{apiKey}`; returns `{provider, last4, createdAt}`; `403 BYOK_NOT_VERIFIED` for an unverified org, `400 INVALID_API_KEY`.
- **`LlmKeyController.list`**: `GET /api/v1/developer/llm-keys` [`DEVELOPER`, JWT only]. Returns `[{provider, last4, createdAt}]`.
- **`LlmKeyController.delete`**: `DELETE /api/v1/developer/llm-keys/{provider}` [`DEVELOPER`, JWT only]. 204, idempotent.

### Developer CLI (`cli/`, TypeScript, run locally)
- **`agentstore login`**: verifies the key with `GET /api/v1/agents/mine`, then stores `{apiKey, apiUrl}` in `~/.config/agentstore/credentials` (0600).
- **`agentstore init [--python|--node]`**: `POST /api/v1/agents`, then scaffolds a project whose `agentstore.yaml` holds the returned `agentId`. Refuses a non-empty directory before calling the API.
- **`agentstore validate`**: checks `manifest.json` against a bundled copy of `manifest-schema.json` and mirrors `BundleManifestRules`, the entrypoint-in-bundle check, the node lockfile rule and the bundle limits.
- **`agentstore push [--wait]`**: `POST …/bundles/uploads`, `PUT` the signed URL, `POST …/bundles/{id}/finalize`, then with `--wait` polls `GET …/builds/{id}` and tails the pre-signed `logUrl`. Exit 0 on `SUCCEEDED`, 1 on `FAILED` or an invalid project, 2 when the tool could not do its job.

### Catalog & publishing
- **`CatalogController.create`**: `POST /api/v1/agents` [`DEVELOPER`] (`backend/src/main/java/com/agentstore/platform/catalog/CatalogController.java`)
  - Accepts: `CreateAgentRequest` (`backend/src/main/java/com/agentstore/platform/catalog/CreateAgentRequest.java`): `name` and `category` are required; `description` is optional
  - Returns: `AgentResponse`
- **`CatalogController.createVersion`**: `POST /api/v1/agents/{agentId}/versions` [`DEVELOPER`]
  - Accepts: `CreateAgentVersionRequest` (`backend/src/main/java/com/agentstore/platform/catalog/CreateAgentVersionRequest.java`): `versionNumber` and `manifestJson` are required; `changelog` is optional. There is no `imageRef`: the version is an image-less `DRAFT` until a pushed bundle builds
  - Returns: `AgentVersionResponse` (status `DRAFT`)
  - Errors: 404 agent; 403 not owner; 400 manifest schema errors
- **`CatalogController.detail`**: `GET /api/v1/agents/{agentId}/versions/{versionId}`
  - Returns: `AgentVersionDetailResponse` (with the parsed `Manifest`)
  - Errors: 404 version not found or not under this agent; 403 not visible
- **`CatalogListingController.list`**: `GET /api/v1/catalog` (`backend/src/main/java/com/agentstore/platform/catalog/CatalogListingController.java`)
  - Returns: `CatalogListingResponse[]` (`LIVE` versions only)
- **`MyAgentsController.mine`**: `GET /api/v1/agents/mine` [`DEVELOPER`] (`backend/src/main/java/com/agentstore/platform/catalog/MyAgentsController.java`)
  - Returns: `MyAgentsResponse[]`
- **`SubmissionController.submit`**: `POST /api/v1/agents/{agentId}/versions/{versionId}/submit` [`DEVELOPER`] (`backend/src/main/java/com/agentstore/platform/publishing/SubmissionController.java`)
  - Returns: `AgentVersionResponse` (`LIVE` or `IN_REVIEW`)
  - Errors: 404 not found; 403 not owner; 409 not `DRAFT` or lint failed
- **`ReviewController`**: `/api/v1/review` [`ADMIN`] (`backend/src/main/java/com/agentstore/platform/publishing/ReviewController.java`)
  - `GET queue`: returns `ReviewItemResponse[]` (only `PENDING` items)
  - `GET {id}`: returns `ReviewItemDetailResponse`
  - `POST {id}/approve` and `POST {id}/reject`: accept `notes` (optional)
  - Errors: 404 item. Deciding an item that is not `PENDING` throws `IllegalStateException`, which is not mapped to an HTTP status here (see Inconsistencies).

### Bundles & builds
- **`BundleController.createUpload`**: `POST /api/v1/agents/{agentId}/bundles/uploads` [`DEVELOPER`] (`backend/src/main/java/com/agentstore/platform/build/BundleController.java`)
  - Accepts: `CreateBundleUploadRequest`: `sha256` (64 hex characters) and `sizeBytes` (> 0)
  - Returns: 201 `BundleUploadResponse(bundleId, uploadUrl, expiresAt)`. `uploadUrl` is a pre-signed PUT valid for 15 minutes.
  - Errors: 404 agent; 403 not owner; 413 larger than 100 MB
- **`BundleController.finalizeBundle`**: `POST /api/v1/agents/{agentId}/bundles/{bundleId}/finalize` [`DEVELOPER`]
  - Accepts: `FinalizeBundleRequest(sha256)`
  - Returns: `FinalizeBundleResponse(bundleId, versionId, versionNumber, buildId, buildStatus)`. A repeat returns the same values.
  - Errors: 404; 403; 409 not uploaded yet, already rejected, or the version is no longer a draft; 422 `{code: INVALID_BUNDLE, message}` (bad sha256 or size, not a zip, missing or invalid manifest, entrypoint not in the bundle, version already submitted)
- **`BuildStatusController.get`**: `GET /api/v1/agents/{agentId}/builds/{buildId}` [`DEVELOPER`, `ADMIN`] (`backend/src/main/java/com/agentstore/platform/build/BuildStatusController.java`)
  - Returns: `BuildResponse(id, versionId, status, backend, imageRef, failureCode, failureReason, logUrl, queuedAt, startedAt, finishedAt)`. `logUrl` is a 15-minute pre-signed GET, or null until a log exists.
  - Errors: 404; 403 other org (unless `ADMIN`)
- `AgentVersionDetailResponse` (from `CatalogController.detail`) also carries `build: BuildSummary(buildId, status, failureCode, failureReason, logUrl)`, the version's latest build.

### Connections & runs
- **`ConnectionController.create`**: `POST /api/v1/connections` [`BUYER_ADMIN`] (`backend/src/main/java/com/agentstore/platform/execution/ConnectionController.java`)
  - Accepts: `CreateConnectionRequest`: `agentId`, `grantedScopes` (non-empty) and `spendCap` (> 0), all required
  - Returns: `ConnectionResponse`
  - Errors: 404 agent; 409 no `LIVE` version; 400 undeclared scope
- **`ConnectionController.storeSecret`**: `POST /api/v1/connections/{id}/secrets` [`BUYER_ADMIN`]
  - Accepts: `name` and `value`. Stores by upsert on `name`.
  - Errors: 404; 403 not owner
- **`ConnectionController.mine`**: `GET /api/v1/connections/mine` [`BUYER_ADMIN`, `BUYER_APPROVER`]
  - Returns: `ConnectionSummaryResponse[]`
- **`RunController.trigger`**: `POST /api/v1/runs` [`BUYER_ADMIN`, `BUYER_APPROVER`] (`backend/src/main/java/com/agentstore/platform/execution/RunController.java`)
  - Accepts: `TriggerRunRequest(connectionId)` (required) (`backend/src/main/java/com/agentstore/platform/execution/TriggerRunRequest.java`)
  - Returns: `RunResponse(id, status)`
  - Errors: 404; 403; 409 (inactive, unpinned or non-`LIVE`); 402 insufficient funds; 503 `CAPACITY_EXHAUSTED` or `BUDGET_EXHAUSTED` (`SandboxCapacityException`, `backend/src/main/java/com/agentstore/platform/execution/SandboxCapacityException.java`); 502 sandbox start failure; 500 launch prep failure. The run is marked `FAILED` in the last three cases.
- **`RunController.approve`**: `POST /api/v1/runs/{runId}/approve` [`BUYER_APPROVER`]
- **`RunController.reject`**: `POST /api/v1/runs/{runId}/reject` [`BUYER_APPROVER`]
  - Returns: `RunResponse`
  - Errors: 404; 403; 409 not awaiting approval
- **`RunDetailController.get`**: `GET /api/v1/runs/{runId}` (`backend/src/main/java/com/agentstore/platform/execution/RunDetailController.java`)
  - Returns: `RunDetailResponse` (with `RunStep`s in time order)
  - Errors: 403 unless owning org or `ADMIN`
- **`RunDetailController.mine`**: `GET /api/v1/runs/mine?status=` [`BUYER_ADMIN`, `BUYER_APPROVER`]
  - Returns: `RunSummaryResponse[]`

### Datasets & bindings
Errors on these endpoints are `{code, message, ...}` (`backend/src/main/java/com/agentstore/platform/data/DataApiErrors.java`).
- **`DatasetController.upload`**: `POST /api/v1/datasets` [`BUYER_ADMIN`] (`backend/src/main/java/com/agentstore/platform/data/DatasetController.java`)
  - Accepts: multipart `file`, `displayName` (required), `sourceLabel`
  - Returns: `DatasetResponse(id, displayName, sourceLabel, format, rowCount, sizeBytes, columns, sha256, uploadedBy, createdAt)`
  - Errors: 400 `VALIDATION_FAILED`; 413 `FILE_TOO_LARGE` or `QUOTA_EXCEEDED`; 415 `UNSUPPORTED_FORMAT`; 422 `INVALID_FILE`
- **`DatasetController.list`**: `GET /api/v1/datasets?include=usage` [`BUYER_ADMIN`, `BUYER_APPROVER`]
  - Returns: `DatasetResponse[]`; with `include=usage` each carries `usage: [{connectionId, resource, agent, version, access}]`
- **`DatasetController.delete`**: `DELETE /api/v1/datasets/{id}` [`BUYER_ADMIN`]: 204. Errors: 404; 409 `DATASET_IN_USE`
- **`DatasetController.check`**: `POST /api/v1/datasets/{id}/check` [`BUYER_ADMIN`, `BUYER_APPROVER`]
  - Accepts: `{agentVersionId, resource}`. Returns: `CheckResult(ok, rowCount, columnsMatch, missingColumns, rowErrorCount, rowErrors[{row, column, message}] (first 50))`. Saves nothing.
  - Errors: 404; 422 `RESOURCE_NOT_BINDABLE`
- **`DatasetController.storage`**: `GET /api/v1/orgs/{orgId}/storage` [`BUYER_ADMIN`, `BUYER_APPROVER`]: `{usedBytes, limitBytes}`. Errors: 403 other org
- **`BindingController`**: `/api/v1/connections/{connectionId}/bindings` (`backend/src/main/java/com/agentstore/platform/data/BindingController.java`)
  - `PUT /{resource}` [`BUYER_ADMIN`] body `{datasetId}`: binds (replacing). Returns `BindingResponse(resource, datasetId, displayName, format, rowCount, boundAt)`. Errors: 403 other org's connection; 404 dataset; 409; 422 `DATASET_CHECK_FAILED` (with `checkResult`), `RESOURCE_NOT_BINDABLE`, `RESOURCE_NOT_GRANTED`
  - `DELETE /{resource}` [`BUYER_ADMIN`]: 204 (unplugs; 404 if nothing bound)
  - `GET` [`BUYER_ADMIN`, `BUYER_APPROVER`]: `BindingResponse[]`
- **`OutputsController`**: `/api/v1/runs/{runId}/outputs` [`BUYER_ADMIN`, `BUYER_APPROVER`] (`backend/src/main/java/com/agentstore/platform/data/OutputsController.java`)
  - `GET ?preview=N`: `[{id, resource, status, recordCount, createdAt, rows?}]`; `rows` (first `min(N, 50)`) only with `preview`, never for `DISCARDED`. Errors: 403 other org; 404
  - `GET /{outputId}/download?format=csv|json`: `COMMITTED` outputs only, streamed through the backend as an attachment. Errors: 400 `INVALID_FORMAT`; 404; 409 `OUTPUT_NOT_COMMITTED`
- `RunController.trigger` additionally returns 422 `MISSING_BINDING` with `resources: [...]`.

### Agent Runtime API v1 (called from inside the sandbox; `/runtime/v1/**`)
The interface agent code uses; contract in `contracts/runtime-v1.yaml`. Authenticated by the run token (`Authorization: Bearer $AGENTSTORE_RUN_TOKEN`) on its own `SecurityFilterChain` (`RuntimeAuthFilter`, no JWT); the token identifies the run, so no run id is in the path. Errors are `application/problem+json` with a stable `code`. Per-run rate limits (bucket4j on Redis) fail open.
* `GET /runtime/v1/context`: run id, mode, agent version, input, granted scopes, limits (`limits.llm` `{mode, models, tokensRemaining}` when the manifest declares `llm`).
* `GET /runtime/v1/resources/{resource}` (format, row count), `/records?cursor=&limit=` (opaque byte-offset cursor, `400 INVALID_CURSOR`; a pdf is `409 NOT_TABULAR`), `/file` (a link to `/file/content`), `GET /runtime/v1/resources/{resource}/file/content` (the bytes, streamed under the run token), `POST /runtime/v1/resources/{resource}/records` (validated against the resource schema, `422 VALIDATION_FAILED` with row `errors`; `413 WRITE_TOO_LARGE`): checked by `ScopeGuard`, then served by `DatasetResourceBackend` from the run's pins (`404 RESOURCE_NOT_BOUND` without one; `503 STORAGE_UNAVAILABLE` when the object store fails). Records `DATA_READ` / `DATA_WRITE` / `WRITE_STAGED`.
* `POST /runtime/v1/gates` (201), `GET /runtime/v1/gates/{gateId}?wait=` (long-poll, max 30 s): open a gate and wait for the buyer's decision. The buyer decides with `POST /api/v1/runs/{id}/gates/{gateId}/decision {decision: APPROVE|REJECT, note}`; the old `/approve` and `/reject` delegate to the run's single pending gate.
* `POST /runtime/v1/llm/chat`: `GatewayLlmBackend` (`backend/src/main/java/com/agentstore/platform/llm/GatewayLlmBackend.java`). Accepts `{model, system?, messages[], tools?[], maxTokens, temperature?}`; returns `{model, content[], stopReason, usage{inputTokens, outputTokens}}`. Errors: 400 `LLM_NOT_DECLARED`, `MODEL_NOT_ALLOWED`, `INVALID_REQUEST`, `USE_DIRECT_PROVIDER`, `LLM_KEY_MISSING`; 402 `BUDGET_EXCEEDED`; 409 `REPLAY_MISS`; 410 `REPLAY_EXPIRED`; 502 `LLM_PROVIDER_ERROR`; 503 `LLM_PROVIDER_UNAVAILABLE`; 504 `LLM_PROVIDER_TIMEOUT`.
* `POST /runtime/v1/usage`: `unit` must equal `costEnvelope.unitLabel`; charges like the legacy COST event; 402 `BUDGET_EXCEEDED` when the cap is hit (the remainder is charged and the run stops).
* `POST /runtime/v1/logs` (`AGENT_LOG`, agent-reported), `/heartbeat`, `/complete`, `/fail`.
* A call outside the granted scopes returns 403 `SCOPE_NOT_GRANTED` and records `SCOPE_VIOLATION`; the third one stops a PRODUCTION run.
The legacy `/internal/agent-callback/**` below stays while `agentstore.runtime.legacy-callback.enabled` is true (default); `RunLauncher` then injects both `AGENTSTORE_*` and `AGENT_*` variables.

### Agent-facing callbacks (called from inside the sandbox)
Legacy. New agents use the Agent Runtime API above. Served only while `agentstore.runtime.legacy-callback.enabled` is true.
- **`AgentCallbackController.ingest`**: `POST /internal/agent-callback/runs/{runId}/events` (`backend/src/main/java/com/agentstore/platform/execution/AgentCallbackController.java`)
  - Accepts: `AgentEventRequest` (`backend/src/main/java/com/agentstore/platform/execution/AgentEventRequest.java`): `kind: RunStepKind` (required), `detail: Map` (optional). `COST` requires `detail.quantity` > 0.
  - Returns: 200 with no body
  - Errors: 401 bad token; 400 platform-only kind or bad quantity; 409 run is terminal; 404 run
- **`AgentCallbackController.gateStatus`**: `GET /internal/agent-callback/runs/{runId}/gate-status`
  - Returns: `GateStatusResponse(decision: [PENDING | APPROVED | REJECTED])`
  - Errors: 401; 404

### Billing
- **`WalletController.wallet`**: `GET /api/v1/orgs/{orgId}/wallet` [`BUYER_ADMIN`, `ADMIN`] (`backend/src/main/java/com/agentstore/platform/ledger/WalletController.java`)
  - Returns: `WalletResponse(balance, cap, reserved)`
  - Errors: 403 other org; 404 no wallet
- **`StatementController.statement`**: `GET /api/v1/orgs/{orgId}/statement` [`BUYER_ADMIN`, `ADMIN`] (`backend/src/main/java/com/agentstore/platform/ledger/StatementController.java`)
  - Returns: `StatementLineResponse[]` (newest first)

### Service-to-service
- **`InternalRunsController.activeIds`**: `GET /internal/runs/active-ids` (header `X-Runner-Secret`)
  - Returns: `{runIds: [...]}`
  - Errors: 401
- **Runner `SandboxController`**: `/sandboxes` (`runner/src/main/java/com/agentstore/runner/api/SandboxController.java`)
  - `POST`: accepts `CreateSandboxRequest` (`runner/src/main/java/com/agentstore/runner/api/CreateSandboxRequest.java`): `requestId`, `imageRef` and `command` (non-empty) are required; `cpu` ≥ 1; `memoryMb` ≥ 128; `timeoutSeconds` ≥ 1; `env` and `allowOut` are optional. Returns 201 `{sandboxId}`.
  - `GET /{id}`: returns `{state, exitCode}`
  - `GET /{id}/logs?offset=`: returns a log chunk of up to 256 KiB plus `finished`
  - `DELETE /{id}`: returns 204
  - Errors: 429 `CAPACITY_EXHAUSTED` or `BUDGET_EXHAUSTED`; 502 `BACKEND_ERROR`; 400 `INVALID_REQUEST`
- **Runner `BuildController`**: `/builds` (`runner/src/main/java/com/agentstore/runner/api/BuildController.java`, header `X-Runner-Secret`)
  - `POST`: accepts `CreateBuildRequest` (`runner/src/main/java/com/agentstore/runner/api/CreateBuildRequest.java`): `buildId`, `runtime`, `bundleUrl`, `bundleSha256`, `entrypoint`, `logUploadUrl`, all required. Returns 202 `{buildId, status, backend, imageRef, failureCode, failureReason}`. Idempotent on `buildId`.
  - `GET /{buildId}`: returns the same shape. An unknown id returns `FAILED` with `RUNNER_RESTARTED`.

## Inconsistencies & Unclear Areas
- **`developer_verified_at` is never set by code:** `org.developer_verified_at` has no writer until WS7; tests set it directly, so BYOK is unusable in a real deployment until then.
- **LLM cost is recorded, not charged:** `LlmCall.cost` is not deducted from the buyer's cost cap or the developer's earnings (WS10).
- **The request hash covers `system`, the spec's list does not:** spec §6 lists model, messages, tools, `maxTokens` and `temperature`; the implementation also hashes `system` (decided during WS3 implementation) so a mock or replay can tell two system prompts apart. The token estimate uses the same canonical bytes.
- **Two sandbox-state types:** The backend has `SandboxStatus.State` (with `stopped()`, `backend/src/main/java/com/agentstore/platform/execution/SandboxStatus.java`). The runner has `SandboxState.Phase` (with `isTerminal()`, `runner/src/main/java/com/agentstore/runner/backend/SandboxState.java`). Both use the values `[RUNNING | EXITED | GONE | UNKNOWN]` and are connected only by string name over HTTP.
- **"Spend limit" appears under three names:**
  - `Connection.spendCap` is the buyer's per-run cap.
  - `Run.costCap` is copied from `spendCap`.
  - `CostEnvelope.maxPerRun` is the developer's declared cap, but nothing in backend code reads it, so no check keeps `spendCap` within it.
- **"Scope" appears in two shapes:** `Manifest.scopes` holds `Scope` records `(type, resource, description)`. `Connection.grantedScopes` holds plain `resource` strings, so a granted scope doesn't record whether it is `READ` or `WRITE`.
- **Manifest wire vs parsed shape:** The raw JSON uses `network.allowlist` and may include `modelRequirements` (`backend/src/main/resources/manifest/manifest-schema.json`, `frontend/src/lib/types.ts` `ManifestDraft`). The parsed `Manifest` flattens the allowlist to `networkAllowlist` and drops `modelRequirements` (ignored and superseded by the `llm` block) (`backend/src/main/java/com/agentstore/platform/manifest/ManifestParser.java`).
- **Declared but not enforced:** Backend logic never reads these values:
  - `Manifest.approvalGates` and `dataClasses`. They are only returned in responses. Gates are driven entirely by agent-reported `GATE` events.
  - `Wallet.cap`. It is only echoed in `WalletResponse`.
  - `LedgerEntryType.REVERSAL` and `FEE`. They are never created.
- **`PricingUnit` is ignored:** Every `COST` event is charged as `quantity × unitPrice`, whatever the `PricingUnit` is. What `OUTCOME` or `SUBSCRIPTION` should mean at run time is unclear.
- **Two reapers:** `RunReaper` (backend, `backend/src/main/java/com/agentstore/platform/execution/RunReaper.java`) terminates `Run`s. `RunnerReaper` (runner) kills sandboxes. Both enforce a roughly 10-minute lifetime independently. The backend's `SANDBOX_TIMEOUT_SECONDS=600` is kept in step with the runner's `max-lifetime` only by a comment.
- **`requestId` is the run id:** In `SandboxSpec` and the runner, `requestId` is the `Run.id` string. The runner never uses the term "run".
- **"Template" only in the build table:** The class and table `TemplateBuild` / `template_build` keep the spec's name, but the rest of the code says build, image and `imageRef`. Only `E2bBackend`, `runner/e2b-shim/` and `agentstore.runner.e2b` config keys mention E2B or templates.
- **Two `AgentRuntime` enums and two failure-code lists:** The backend (`backend/src/main/java/com/agentstore/platform/manifest/AgentRuntime.java`) and the runner (`runner/src/main/java/com/agentstore/runner/backend/AgentRuntime.java`) each hold the runtime ids and are kept in step by hand. `BuildFailureCodes` (backend) has `SUPERSEDED`; `BuildFailedException` (runner) does not.
- **Submitting a built draft is portal-only:** `ApiKeyScope` denies `…/versions/*/submit` to keys, and the CLI has no `submit` command (WS8), so the only way to submit is the "Submit for review" button on the version status page.
- **`PENDING_BUILD` runs are not in the runner's active set:** `InternalRunsController` lists only `[PENDING | RUNNING | AWAITING_APPROVAL]`, which is right because a `PENDING_BUILD` run has no sandbox yet, but `RunReaper` does treat it as active.
- **`SubmissionController` ignores `{agentId}`:** The path variable is not checked against the version's agent, unlike `CatalogController.detail`.
- **`ReviewController` doesn't map decision errors:** It does not map the `IllegalStateException` or `IllegalArgumentException` from `PublishingService.approve` and `reject` to HTTP statuses, so the resulting status for an already-decided or missing item is unclear. No global handler was found in the files read.
- **Frontend `AuthResponse` is incomplete:** `frontend/src/lib/types.ts` declares only `token`. The backend `AuthResponse` also returns `refreshToken`.
- **Raw UUIDs instead of relations:** `LedgerEntry.runId`/`orgId`, `UsageEvent.runId`/`orgId`, `Wallet.orgId`, `ReviewItem.reviewerUserId` and `User.lastOrgId`/`signupOrgId` are raw UUIDs, not JPA relations.
- **Connection revoke does not exist yet:** `ConnectionStatus.REVOKED` is never set by any code path, so `BindingService.unbindAll` (which starts the 30-day clock for a revoked connection's files) is built and tested but not wired to anything.
- **Agent writes lose trailing zeros:** A JSON write body is parsed by Spring's default mapper, which reads `12.50` as the double `12.5` before it is stored. Uploaded files keep exact decimals (`Jsonl.MAPPER`). Fixing it needs `spring.jackson.deserialization.use-big-decimal-for-floats`, a global change.
