package com.agentstore.platform.data;

import com.agentstore.platform.execution.RunMode;

/** The mode guard: buyer data only ever reaches a PRODUCTION run, and a PRODUCTION run only ever sees buyer data. */
public final class DataAccessRules {

    private DataAccessRules() {
    }

    public static boolean allows(RunMode mode, DatasetPurpose purpose) {
        return mode == RunMode.PRODUCTION ? purpose == DatasetPurpose.BUYER_DATA : purpose != DatasetPurpose.BUYER_DATA;
    }
}
