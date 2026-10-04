package dev.chirag45.breeze_core.controllers;

import dev.chirag45.breeze_core.dto.request.ProjectUpsertRequest;
import dev.chirag45.breeze_core.dto.response.ProjectResponse;
import dev.chirag45.breeze_core.dto.response.wrapper.ApiResponse;
import dev.chirag45.breeze_core.services.ProjectService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectResponse>> create(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProjectUpsertRequest request
    ) {
        log.debug("project_creation_requested");
        ProjectResponse project = projectService.create(authentication.getToken().getSubject(), request);
        log.info("project_created projectId={} ownerUserId={}", project.id(), project.ownerUserId());
        return ResponseEntity.created(URI.create("/api/projects/" + project.id()))
                .body(ApiResponse.success(project, "Project created."));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> list(JwtAuthenticationToken authentication) {
        List<ProjectResponse> projects = projectService.list(authentication.getToken().getSubject());
        log.debug("projects_listed count={}", projects.size());
        return ApiResponse.success(projects);
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> get(
            JwtAuthenticationToken authentication,
            @PathVariable UUID projectId
    ) {
        ProjectResponse project = projectService.get(authentication.getToken().getSubject(), projectId);
        log.debug("project_read projectId={}", projectId);
        return ApiResponse.success(project);
    }

    @PutMapping("/{projectId}")
    public ApiResponse<ProjectResponse> update(
            JwtAuthenticationToken authentication,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectUpsertRequest request
    ) {
        ProjectResponse project = projectService.update(authentication.getToken().getSubject(), projectId, request);
        log.info("project_updated projectId={}", projectId);
        return ApiResponse.success(project);
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            JwtAuthenticationToken authentication,
            @PathVariable UUID projectId
    ) {
        projectService.delete(authentication.getToken().getSubject(), projectId);
        log.info("project_deleted projectId={}", projectId);
        return ResponseEntity.noContent().build();
    }
}
