package com.agentstore.platform.execution;

import com.agentstore.platform.build.*;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembership;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared setup for execution tests: a LIVE Tier A agent, a buyer with a wallet, and an ACTIVE connection. */
@Component
public class ExecutionFixtures {

    private final OrgRepository orgRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final ConnectionRepository connectionRepository;
    private final WalletRepository walletRepository;
    private final RunRepository runRepository;
    private final UserRepository userRepository;
    private final AgentBundleRepository agentBundleRepository;
    private final TemplateBuildRepository templateBuildRepository;
    private final OrgMembershipRepository membershipRepository;

    public ExecutionFixtures(OrgRepository orgRepository, AgentRepository agentRepository,
                             AgentVersionRepository agentVersionRepository, ConnectionRepository connectionRepository,
                             WalletRepository walletRepository, RunRepository runRepository,
                             UserRepository userRepository, AgentBundleRepository agentBundleRepository,
                             TemplateBuildRepository templateBuildRepository,
                             OrgMembershipRepository membershipRepository) {
        this.orgRepository = orgRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.connectionRepository = connectionRepository;
        this.walletRepository = walletRepository;
        this.runRepository = runRepository;
        this.userRepository = userRepository;
        this.agentBundleRepository = agentBundleRepository;
        this.templateBuildRepository = templateBuildRepository;
        this.membershipRepository = membershipRepository;
    }

    /** A new user who is a BUYER_MEMBER member of the scenario's buyer org. */
    public User approver(Scenario scenario) {
        User user = userRepository.save(new User(null, "approver-" + UUID.randomUUID() + "@example.com", null, "Approver"));
        membershipRepository.save(new OrgMembership(user, scenario.buyer(), UserRole.BUYER_MEMBER, "Approver"));
        return user;
    }

    public record Scenario(Org buyer, AgentVersion version, Connection connection) {}

    public Scenario liveTierA(BigDecimal spendCap, BigDecimal walletBalance) {
        return liveWithManifest("valid-tier-a.json", List.of("bank_statement"), spendCap, walletBalance);
    }

    /** As {@link #liveTierA} but with any manifest from src/test/resources/manifest and the buyer's granted resources. */
    public Scenario liveWithManifest(String manifestFile, List<String> grantedScopes, BigDecimal spendCap,
                                     BigDecimal walletBalance) {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", readManifest(manifestFile),
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        version = agentVersionRepository.save(version);
        Connection connection = new Connection(null, buyer, agent, grantedScopes, spendCap,
                ConnectionStatus.ACTIVE);
        connection.setAgentVersion(version);
        connection = connectionRepository.save(connection);
        Wallet wallet = new Wallet(null, buyer.getId(), walletBalance, null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return new Scenario(buyer, version, connection);
    }

    /**
     * A LIVE Tier A version built by the platform from a bundle (runtime python-3.12, entrypoint agent/main.py), whose
     * image was built on {@code builtOnBackend}; buyer, wallet and ACTIVE connection as in {@link #liveTierA}.
     */
    public Scenario liveBuiltTierA(BigDecimal spendCap, BigDecimal walletBalance, String builtOnBackend) {
        Scenario scenario = liveTierA(spendCap, walletBalance);
        AgentVersion version = scenario.version();
        User user = userRepository.save(new User(null, "builder-" + UUID.randomUUID() + "@example.com", null, "Builder"));
        UUID bundleId = UUID.randomUUID();
        AgentBundle bundle = agentBundleRepository.save(new AgentBundle(bundleId, version.getAgent(), user.getId(),
                "bundles/" + version.getAgent().getId() + "/" + bundleId + ".zip", "c".repeat(64), 100,
                BundleStatus.UPLOADED));
        version.setManifestJson(readManifest("valid-bundle-python.json"));
        version.setBundle(bundle);
        version.setBuildStatus(BuildStatus.SUCCEEDED);
        version.setImageRef("img-" + builtOnBackend);
        version = agentVersionRepository.save(version);
        TemplateBuild build = new TemplateBuild(version, bundle, builtOnBackend, "python-3.12", "agent/main.py");
        build.setStatus(BuildStatus.SUCCEEDED);
        build.setImageRef("img-" + builtOnBackend);
        build.setFinishedAt(Instant.now());
        build = templateBuildRepository.save(build);
        build.setLogStorageKey("builds/" + build.getId() + ".log");
        templateBuildRepository.save(build);
        return new Scenario(scenario.buyer(), version, scenario.connection());
    }

    public Run run(Scenario scenario, RunStatus status, String sandboxId, String rawToken) {
        Run run = new Run(null, scenario.connection(), scenario.version(), status,
                scenario.connection().getSpendCap(), BigDecimal.ZERO, sandboxId, RunTokenService.hash(rawToken));
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    private static String readManifest(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/manifest/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
