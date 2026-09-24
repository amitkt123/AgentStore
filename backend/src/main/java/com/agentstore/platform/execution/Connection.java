package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.identity.Org;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "connection")
@Getter
@Setter
@NoArgsConstructor
public class Connection {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "buyer_org_id")
    private Org buyerOrg;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id")
    private Agent agent;

    /** The version the buyer consented to. Runs always use this; upgrading is an explicit buyer action (WS11). */
    @ManyToOne
    @JoinColumn(name = "agent_version_id")
    private AgentVersion agentVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "granted_scopes", columnDefinition = "jsonb")
    private String grantedScopesJson;

    private BigDecimal spendCap;

    @Enumerated(EnumType.STRING)
    private ConnectionStatus status;

    private Instant createdAt;

    public Connection(UUID id, Org buyerOrg, Agent agent, List<String> grantedScopes, BigDecimal spendCap,
                       ConnectionStatus status) {
        this.id = id;
        this.buyerOrg = buyerOrg;
        this.agent = agent;
        setGrantedScopes(grantedScopes);
        this.spendCap = spendCap;
        this.status = status;
    }

    public List<String> getGrantedScopes() {
        try {
            return MAPPER.readValue(grantedScopesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void setGrantedScopes(List<String> scopes) {
        try {
            this.grantedScopesJson = MAPPER.writeValueAsString(scopes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
