package dev.chirag45.breeze_core.services;

import dev.chirag45.breeze_core.dto.request.ProjectUpsertRequest;
import dev.chirag45.breeze_core.dto.response.ProjectResponse;
import dev.chirag45.breeze_core.entities.ProjectEntity;
import dev.chirag45.breeze_core.entities.UserEntity;
import dev.chirag45.breeze_core.exception.CoreUserNotProvisionedException;
import dev.chirag45.breeze_core.exception.ProjectNotFoundException;
import dev.chirag45.breeze_core.repository.ProjectRepository;
import dev.chirag45.breeze_core.repository.UserRepository;
import dev.chirag45.breeze_core.outbox.OutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final OutboxStore outboxStore;

    public ProjectService(ProjectRepository projectRepository, UserRepository userRepository, OutboxStore outboxStore) {
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.outboxStore = outboxStore;
    }

    @Transactional
    public ProjectResponse create(String clerkUserId, ProjectUpsertRequest request) {
        UserEntity owner = userRepository.findByClerkUserId(clerkUserId)
                .orElseThrow(CoreUserNotProvisionedException::new);
        ProjectEntity project = new ProjectEntity(owner, request.name().strip(), request.description());
        ProjectEntity saved = projectRepository.saveAndFlush(project);
        outboxStore.requestProjectInitialization(saved);
        log.debug("project_initialization_outbox_requested projectId={} ownerUserId={}",
                saved.getId(), owner.getId());
        return ProjectResponse.from(saved);
    }

    public List<ProjectResponse> list(String clerkUserId) {
        return projectRepository.findAllByOwner_ClerkUserIdOrderByCreatedAtDesc(clerkUserId)
                .stream()
                .map(ProjectResponse::from)
                .toList();
    }

    public ProjectResponse get(String clerkUserId, UUID projectId) {
        return ProjectResponse.from(findOwnedProject(clerkUserId, projectId));
    }

    @Transactional
    public ProjectResponse update(String clerkUserId, UUID projectId, ProjectUpsertRequest request) {
        ProjectEntity project = findOwnedProject(clerkUserId, projectId);
        project.updateDetails(request.name().strip(), request.description());
        return ProjectResponse.from(projectRepository.saveAndFlush(project));
    }

    @Transactional
    public void delete(String clerkUserId, UUID projectId) {
        projectRepository.delete(findOwnedProject(clerkUserId, projectId));
    }

    private ProjectEntity findOwnedProject(String clerkUserId, UUID projectId) {
        return projectRepository.findByIdAndOwner_ClerkUserId(projectId, clerkUserId)
                .orElseThrow(ProjectNotFoundException::new);
    }
}
