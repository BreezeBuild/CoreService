package dev.chirag45.breeze_core.outbox;

import dev.chirag45.breeze_core.repository.UserRepository;
import dev.chirag45.breeze_core.temporal.ProjectInitializationStarter;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerBulkheadTests {

    @Test
    void saturatedBulkheadLeavesWorkUnclaimedAndRunsOutsidePollingThread() throws Exception {
        OutboxProperties properties = new OutboxProperties();
        properties.setWorkerThreads(1);
        OutboxStore store = mock(OutboxStore.class);
        ClerkProfileClient clerk = mock(ClerkProfileClient.class);
        when(clerk.isConfigured()).thenReturn(true);

        CountDownLatch claimStarted = new CountDownLatch(1);
        CountDownLatch releaseClaim = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        when(store.claim(anyString(), eq(60), eq(5), eq(true))).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            claimStarted.countDown();
            releaseClaim.await(5, TimeUnit.SECONDS);
            return Optional.empty();
        });

        ThreadPoolBulkhead bulkhead = new OutboxConfig().outboxBulkhead(properties);
        try {
            OutboxWorker worker = new OutboxWorker(
                    store, mock(UserRepository.class), clerk, mock(ProjectInitializationStarter.class),
                    bulkhead, properties, "test-instance");

            worker.poll();
            assertThat(claimStarted.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();

            verify(store).claim(anyString(), eq(60), eq(5), eq(true));
            assertThat(workerThread.get()).isNotEqualTo(Thread.currentThread().getName());
        } finally {
            releaseClaim.countDown();
            bulkhead.close();
        }
    }
}
