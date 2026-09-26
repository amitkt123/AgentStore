package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The 50 MB cap is the multipart limit; shrinking it here keeps the test small. */
@TestPropertySource(properties = {"agentstore.data.max-file-size=2KB", "agentstore.data.max-request-size=4KB"})
class DatasetUploadCapTest extends AbstractStorageIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    DataFixtures fx;
    @Autowired
    OrgRepository orgRepository;

    @Test
    void aFileOverTheCapIs413() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
        byte[] big = ("a,b\n" + "1,2\n".repeat(1500)).getBytes();

        var response = rest.exchange("http://localhost:" + port + "/api/v1/datasets", HttpMethod.POST,
                new HttpEntity<>(DataFixtures.file("big.csv", big, "Big"), fx.multipart(org, UserRole.BUYER_ADMIN)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().get("code")).isEqualTo("FILE_TOO_LARGE");
    }
}
