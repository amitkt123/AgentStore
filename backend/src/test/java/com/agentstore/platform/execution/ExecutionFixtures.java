package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
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

/** Shared setup for execution tests: a LIVE Tier A agent, a buyer with a wallet, and an ACTIVE connection. */
@Component
public class ExecutionFixtures {

    private final OrgRepository orgRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final ConnectionRepository connectionRepository;
    private final WalletRepository walletRepository;
    private final RunRepository runRepository;

    public ExecutionFixtures(OrgRepository orgRepository, AgentRepository agentRepository,
                             AgentVersionRepository agentVersionRepository, ConnectionRepository connectionRepository,
                             WalletRepository walletRepository, RunRepository runRepository) {
        this.orgRepository = orgRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.connectionRepository = connectionRepository;
        this.walletRepository = walletRepository;
        this.runRepository = runRepository;
    }

    public record Scenario(Org buyer, AgentVersion version, Connection connection) {}

    public Scenario liveTierA(BigDecimal spendCap, BigDecimal walletBalance) {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", readManifest("valid-tier-a.json"),
                "template-v1", VersionStatus.LIVE, Tier.TIER_A, "cl");
        version.setApprovedAt(Instant.now());
        version = agentVersionRepository.save(version);
        Connection connection = new Connection(null, buyer, agent, List.of("bank_statement"), spendCap,
                ConnectionStatus.ACTIVE);
        connection = connectionRepository.save(connection);
        Wallet wallet = new Wallet(null, buyer.getId(), walletBalance, null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return new Scenario(buyer, version, connection);
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
