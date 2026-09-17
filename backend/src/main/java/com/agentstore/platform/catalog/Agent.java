package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.Org;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent")
@Getter
@Setter
@NoArgsConstructor
public class Agent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "developer_org_id")
    private Org developerOrg;

    private String name;
    private String category;
    private String description;
    private Instant createdAt;

    public Agent(UUID id, Org developerOrg, String name, String category, String description) {
        this.id = id;
        this.developerOrg = developerOrg;
        this.name = name;
        this.category = category;
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
