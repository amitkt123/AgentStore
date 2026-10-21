package com.agentstore.platform.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ledger_account_balance")
@Getter
@Setter
@NoArgsConstructor
public class LedgerAccountBalance {

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    private BigDecimal balance = BigDecimal.ZERO;

    private long version;

    public LedgerAccountBalance(UUID accountId) {
        this.accountId = accountId;
    }
}
