package dev.chirag45.breeze_core.outbox;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.chirag45.breeze_core.repository.UserRepository;
import dev.chirag45.breeze_core.enums.outbox.OutboxEventType;
import dev.chirag45.breeze_core.temporal.ProjectInitializationStarter;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectInitializationDispatchTests {

    @Test
    void startsProjectWorkflowEvenWithoutClerkKey() throws Exception {
        OutboxProperties properties = new OutboxProperties();
        OutboxStore store = mock(OutboxStore.class);
        ClerkProfileClient clerk = mock(ClerkProfileClient.class);
        ProjectInitializationStarter starter = mock(ProjectInitializationStarter.class);
        var claim = new OutboxStore.Claim(
                UuidCreator.getTimeOrderedEpoch(), OutboxEventType.PROJECT_WORKSPACE_INITIALIZATION_REQUESTED,
                UuidCreator.getTimeOrderedEpoch(), 1, "test-worker", null,
                UuidCreator.getTimeOrderedEpoch().toString(), "New API", "Generate a REST API");
        when(store.claim(anyString(), eq(60), eq(5), eq(false))).thenReturn(Optional.of(claim));
        when(store.completeProjectInitializationStart(claim)).thenReturn(true);

        ThreadPoolBulkhead bulkhead = new OutboxConfig().outboxBulkhead(properties);
        try {
            var worker = new OutboxWorker(store, mock(UserRepository.class), clerk, starter,
                    bulkhead, properties, "test-instance");
            worker.poll();

            verify(starter, org.mockito.Mockito.timeout(5000)).start(claim);
            verify(store, org.mockito.Mockito.timeout(5000)).completeProjectInitializationStart(claim);
        } finally {
            bulkhead.close();
        }
    }
}
