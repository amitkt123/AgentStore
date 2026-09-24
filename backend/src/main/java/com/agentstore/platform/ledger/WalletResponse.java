package com.agentstore.platform.ledger;

import java.math.BigDecimal;

public record WalletResponse(BigDecimal balance, BigDecimal cap, BigDecimal reserved) {
    static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getBalance(), wallet.getCap(), wallet.getReserved());
    }
}
