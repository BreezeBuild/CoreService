package dev.chirag45.breeze_core.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ProjectInitializationWorkflow {

    @WorkflowMethod(name = "ProjectInitialization")
    void initialize(String projectId, String ownerUserId, String name, String description);
}
