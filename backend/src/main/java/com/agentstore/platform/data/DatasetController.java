package com.agentstore.platform.data;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DatasetController {

    private final DatasetService datasetService;

    public DatasetController(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public DatasetResponse upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "displayName", required = false) String displayName,
                                  @RequestParam(value = "sourceLabel", required = false) String sourceLabel)
            throws IOException {
        var claims = Caller.current();
        try (InputStream in = file.getInputStream()) {
            Dataset dataset = datasetService.upload(claims.orgId(), claims.userId(), file.getOriginalFilename(), in,
                    displayName, sourceLabel);
            return DatasetResponse.from(dataset, null, null);
        }
    }

    @GetMapping("/datasets")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public List<DatasetResponse> list(@RequestParam(value = "include", required = false) String include) {
        return datasetService.list(Caller.current().orgId(), "usage".equals(include));
    }

    @DeleteMapping("/datasets/{id}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        datasetService.delete(Caller.current().orgId(), id);
    }

    @GetMapping("/orgs/{orgId}/storage")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public DatasetService.StorageResponse storage(@PathVariable UUID orgId) {
        if (!Caller.current().orgId().equals(orgId)) {
            throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your workspace");
        }
        return datasetService.storage(orgId);
    }
}
