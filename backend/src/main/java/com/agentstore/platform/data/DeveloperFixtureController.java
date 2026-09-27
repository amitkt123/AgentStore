package com.agentstore.platform.data;

import com.agentstore.platform.sandbox.SandboxQuotaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** A developer's fixture library: same ingestion path as buyer files, purpose FIXTURE, under the sandbox storage quota. */
@RestController
@RequestMapping("/api/v1/developer/fixtures")
@PreAuthorize("hasRole('DEVELOPER')")
public class DeveloperFixtureController {

    private final DatasetService datasetService;
    private final SandboxQuotaService quota;

    public DeveloperFixtureController(DatasetService datasetService, SandboxQuotaService quota) {
        this.datasetService = datasetService;
        this.quota = quota;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatasetResponse upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "displayName", required = false) String displayName,
                                  @RequestParam(value = "sourceLabel", required = false) String sourceLabel)
            throws IOException {
        var claims = Caller.current();
        String name = displayName == null || displayName.isBlank() ? file.getOriginalFilename() : displayName;
        try (InputStream in = file.getInputStream()) {
            Dataset dataset = datasetService.upload(claims.orgId(), claims.userId(), file.getOriginalFilename(), in,
                    name, sourceLabel, DatasetPurpose.FIXTURE, quota::checkFixtureStorage);
            return DatasetResponse.from(dataset, null, null);
        }
    }

    @GetMapping
    public List<DatasetResponse> list() {
        return datasetService.listFixtures(Caller.current().orgId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        datasetService.deleteFixture(Caller.current().orgId(), id);
    }
}
