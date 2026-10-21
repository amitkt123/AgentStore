package com.agentstore.platform.ledger;

import java.math.BigDecimal;

/**
 * A buyer org's wallet. {@code held} is money reserved for running runs ({@code reserved} is the same figure, kept
 * for older callers). Prepaid: {@code available = balance - held}. Postpaid: {@code balance = 0} and
 * {@code available = creditLimit - owed - held}; {@code creditLimit} and {@code owed} are null/zero for prepaid.
 * {@code monthlyLimit} is the org-level spend limit, not built yet (WS15c).
 */
public record WalletResponse(BillingMode mode, BigDecimal balance, BigDecimal held, BigDecimal available,
                             BigDecimal spentThisMonth, BigDecimal monthlyLimit, BigDecimal creditLimit,
                             BigDecimal owed, BigDecimal cap, BigDecimal reserved) {
}
