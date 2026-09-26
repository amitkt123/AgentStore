package com.agentstore.platform.manifest;

import java.util.Locale;
import java.util.Optional;

/** The file formats a dataset or a manifest resource can have. */
public enum DataFormat {
    CSV, JSON, PDF;

    /** CSV and JSON are rows of records; PDF is a single document read through the file endpoint. */
    public boolean tabular() {
        return this != PDF;
    }

    /** The lowercase name used in manifests and on the runtime API. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DataFormat> fromWire(String value) {
        for (DataFormat format : values()) {
            if (format.wire().equals(value)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }
}
