package dev.chirag45.breeze_core.services;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.chirag45.breeze_core.dto.request.ProjectUpsertRequest;
import dev.chirag45.breeze_core.dto.response.ProjectResponse;
import dev.chirag45.breeze_core.entities.UserEntity;
import dev.chirag45.breeze_core.exception.ProjectNotFoundException;
import dev.chirag45.breeze_core.repository.ProjectRepository;
import dev.chirag45.breeze_core.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ProjectCrudTests {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ownerCanCreateReadUpdateAndDeleteProject() {
        UserEntity owner = userRepository.saveAndFlush(new UserEntity("project_crud_owner"));

        ProjectResponse created = projectService.create(
                owner.getClerkUserId(),
                new ProjectUpsertRequest("  Sample API  ", "Initial description")
        );

        assertThat(created.id().version()).isEqualTo(7);
        assertThat(created.ownerUserId()).isEqualTo(owner.getId());
        assertThat(created.name()).isEqualTo("Sample API");
        assertThat(created.description()).isEqualTo("Initial description");
        assertThat(created.workSpaceInitializedOn()).isNull();
        assertThat(created.createdAt()).isNotNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE event_type = 'PROJECT_WORKSPACE_INITIALIZATION_REQUESTED'
                  AND aggregate_id = ?
                  AND payload ->> 'name' = 'Sample API'
                  AND payload ->> 'description' = 'Initial description'
                  AND payload ->> 'ownerUserId' = ?
                """, Integer.class, created.id(), owner.getId().toString())).isEqualTo(1);
        assertThat(projectService.list(owner.getClerkUserId())).containsExactly(created);
        assertThat(projectService.get(owner.getClerkUserId(), created.id())).isEqualTo(created);

        ProjectResponse updated = projectService.update(
                owner.getClerkUserId(),
                created.id(),
                new ProjectUpsertRequest("Renamed API", null)
        );

        assertThat(updated.name()).isEqualTo("Renamed API");
        assertThat(updated.description()).isNull();
        assertThat(updated.workSpaceInitializedOn()).isNull();
        assertThat(projectService.get(owner.getClerkUserId(), created.id()).name()).isEqualTo("Renamed API");

        projectService.delete(owner.getClerkUserId(), created.id());
        projectRepository.flush();
        assertThat(projectService.list(owner.getClerkUserId())).isEmpty();
        assertThatThrownBy(() -> projectService.get(owner.getClerkUserId(), created.id()))
                .isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void otherUserCannotReadUpdateOrDeleteProject() {
        UserEntity owner = userRepository.saveAndFlush(new UserEntity("project_access_owner"));
        UserEntity otherUser = userRepository.saveAndFlush(new UserEntity("project_access_other"));
        UUID projectId = projectService.create(
                owner.getClerkUserId(),
                new ProjectUpsertRequest("Private API", null)
        ).id();

        assertThat(projectService.list(otherUser.getClerkUserId())).isEmpty();
        assertThatThrownBy(() -> projectService.get(otherUser.getClerkUserId(), projectId))
                .isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> projectService.update(
                otherUser.getClerkUserId(), projectId, new ProjectUpsertRequest("Changed", null)
        )).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> projectService.delete(otherUser.getClerkUserId(), projectId))
                .isInstanceOf(ProjectNotFoundException.class);
        assertThat(projectService.get(owner.getClerkUserId(), projectId).name()).isEqualTo("Private API");
        assertThatThrownBy(() -> projectService.get(owner.getClerkUserId(), UuidCreator.getTimeOrderedEpoch()))
                .isInstanceOf(ProjectNotFoundException.class);
    }
}
