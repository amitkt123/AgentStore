package com.agentstore.platform.ledger;

import java.util.List;

/** Renders a developer's monthly statement as CSV (RFC 4180: CRLF line ends, quoted where needed). */
final class EarningsStatementCsv {

    static final String HEADER = "date,run,agent,type,buyer_paid,platform_fee,ai_usage,tax,earned";

    private EarningsStatementCsv() {
    }

    static String render(List<LedgerTransactionRepository.DeveloperTransactionRow> rows) {
        StringBuilder csv = new StringBuilder(HEADER).append("\r\n");
        for (var row : rows) {
            csv.append(String.join(",",
                            field(row.getAt().toString()),
                            field(row.getRunDisplayId()),
                            field(neutralise(row.getAgentName())),
                            field(row.getType()),
                            row.getBuyerPaid().setScale(2).toPlainString(),
                            row.getPlatformFee().setScale(2).toPlainString(),
                            row.getAiUsage().setScale(2).toPlainString(),
                            row.getTax().setScale(2).toPlainString(),
                            row.getEarned().setScale(2).toPlainString()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    /** Stops spreadsheets treating a leading =, +, - or @ as a formula. */
    static String neutralise(String value) {
        if (value != null && !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) return "'" + value;
        return value;
    }

    static String field(String value) {
        if (value == null) return "";
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
