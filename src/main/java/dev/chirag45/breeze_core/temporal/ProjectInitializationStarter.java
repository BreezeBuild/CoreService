package dev.chirag45.breeze_core.temporal;

import dev.chirag45.breeze_core.outbox.OutboxStore;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProjectInitializationStarter {

    private static final Logger log = LoggerFactory.getLogger(ProjectInitializationStarter.class);

    private final WorkflowClient client;
    private final String taskQueue;

    public ProjectInitializationStarter(WorkflowClient client,
                                        @Value("${breeze.temporal.project-initialization-task-queue}") String taskQueue) {
        this.client = client;
        this.taskQueue = taskQueue;
    }

    public void start(OutboxStore.Claim claim) {
        String workflowId = "project-initialization-" + claim.aggregateId();
        var options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build();
        var workflow = client.newWorkflowStub(ProjectInitializationWorkflow.class, options);
        log.info("project_initialization_workflow_start_requested projectId={} workflowId={} taskQueue={}",
                claim.aggregateId(), workflowId, taskQueue);
        try {
            var execution = WorkflowClient.start(workflow::initialize, claim.aggregateId().toString(), claim.ownerUserId(),
                    claim.projectName(), claim.projectDescription());
            log.info("project_initialization_workflow_start_accepted projectId={} workflowId={} runId={}",
                    claim.aggregateId(), workflowId, execution.getRunId());
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            log.info("project_initialization_workflow_already_started projectId={} workflowId={}",
                    claim.aggregateId(), workflowId);
        }
    }
}
