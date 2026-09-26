package com.agentstore.platform;

import com.agentstore.platform.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/** Adds a real SeaweedFS (S3 API) to the shared Postgres test setup. Started once per JVM. */
public abstract class AbstractStorageIntegrationTest extends AbstractIntegrationTest {

    static final String ACCESS_KEY = "test-access";
    static final String SECRET_KEY = "test-secret-0123456789";
    static final String S3_CONFIG = """
            {"identities":[{"name":"test","credentials":[{"accessKey":"%s","secretKey":"%s"}],
              "actions":["Admin","Read","List","Tagging","Write"]}]}
            """.formatted(ACCESS_KEY, SECRET_KEY);

    static final GenericContainer<?> SEAWEEDFS = new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
            .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data", "-ip.bind=0.0.0.0")
            .withExposedPorts(8333)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    private static volatile boolean bucketReady;

    static {
        SEAWEEDFS.start();
    }

    @Autowired
    protected ObjectStorage storage;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("agentstore.storage.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
        registry.add("agentstore.storage.access-key", () -> ACCESS_KEY);
        registry.add("agentstore.storage.secret-key", () -> SECRET_KEY);
        registry.add("agentstore.storage.bucket", () -> "agentstore-test");
    }

    /** SeaweedFS opens its port before the filer is ready, so the first bucket call is retried. */
    @BeforeEach
    void bucketReady() throws InterruptedException {
        if (bucketReady) {
            return;
        }
        long until = System.currentTimeMillis() + 60_000;
        while (true) {
            try {
                storage.ensureBucket();
                bucketReady = true;
                return;
            } catch (RuntimeException e) {
                if (System.currentTimeMillis() > until) {
                    throw e;
                }
                Thread.sleep(500);
            }
        }
    }
}
