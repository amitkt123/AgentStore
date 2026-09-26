package com.agentstore.runner.api;

import com.agentstore.runner.build.BuildService;
import com.agentstore.runner.build.BuildService.BuildCommand;
import com.agentstore.runner.build.BuildService.BuildView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/builds")
public class BuildController {

    private final BuildService buildService;

    public BuildController(BuildService buildService) {
        this.buildService = buildService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BuildView submit(@Valid @RequestBody CreateBuildRequest r) {
        return buildService.submit(new BuildCommand(r.buildId(), r.runtime(), r.bundleUrl(), r.bundleSha256(),
                r.entrypoint(), r.logUploadUrl()));
    }

    @GetMapping("/{buildId}")
    public BuildView get(@PathVariable String buildId) {
        return buildService.get(buildId);
    }
}
