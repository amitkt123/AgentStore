package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "org_membership")
@Getter
@Setter
@NoArgsConstructor
public class OrgMembership {

    @EmbeddedId
    private OrgMembershipId id;

    @MapsId("userId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @MapsId("orgId")
    @ManyToOne(optional = false)
    @JoinColumn(name = "org_id")
    private Org org;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    private String title;
    private Instant createdAt;

    public OrgMembership(User user, Org org, UserRole role, String title) {
        this.id = new OrgMembershipId(user.getId(), org.getId());
        this.user = user;
        this.org = org;
        this.role = role;
        this.title = title;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
