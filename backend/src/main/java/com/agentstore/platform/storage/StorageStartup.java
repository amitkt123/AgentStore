package com.agentstore.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Creates the bucket at startup. Only warns when the store is down, so the rest of the app (and tests) still start. */
@Component
public class StorageStartup {

    private static final Logger log = LoggerFactory.getLogger(StorageStartup.class);

    private final ObjectStorage storage;

    public StorageStartup(ObjectStorage storage) {
        this.storage = storage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            storage.ensureBucket();
        } catch (RuntimeException e) {
            log.warn("Object store not ready at startup ({}); bundle uploads will fail until it is",
                    e.getClass().getSimpleName());
        }
    }
}
