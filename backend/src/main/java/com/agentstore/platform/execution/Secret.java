package com.agentstore.platform.execution;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "secret")
@Getter
@Setter
@NoArgsConstructor
public class Secret {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "connection_id")
    private Connection connection;

    private String name;

    @Column(name = "encrypted_value")
    private byte[] encryptedValue;

    private byte[] nonce;
    private Instant createdAt;

    public Secret(UUID id, Connection connection, String name, byte[] encryptedValue, byte[] nonce) {
        this.id = id;
        this.connection = connection;
        this.name = name;
        this.encryptedValue = encryptedValue;
        this.nonce = nonce;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
