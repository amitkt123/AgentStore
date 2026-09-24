package com.agentstore.platform.identity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    /** Null for users who only ever signed in with Google. */
    @Column(name = "password_hash")
    private String passwordHash;

    private String fullName;
    private Instant emailVerifiedAt;

    /** The org a login lands in. Updated on signup, switch-org and accepting an invitation. */
    private UUID lastOrgId;

    /**
     * The org this user created at signup (password or Google new-user provisioning). Null for a user
     * created by accepting someone else's invitation, or for a pre-migration user whose earliest
     * membership could not be attributed to their own signup. Never updated after signup; used to gate
     * the admin-emails ADMIN promotion to the org the user actually created.
     */
    private UUID signupOrgId;

    private Instant createdAt;

    public User(UUID id, String email, String passwordHash, String fullName) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
