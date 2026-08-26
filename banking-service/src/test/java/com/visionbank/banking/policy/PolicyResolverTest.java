package com.visionbank.banking.policy;

import com.visionbank.banking.approval.ApprovalEngineClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyResolverTest {

    @Mock
    private ApprovalEngineClient client;

    @Test
    void delegatesResolutionToTheApprovalEngine() {
        when(client.resolvePolicy(100000L)).thenReturn(new WorkflowSelection("transfer-auto-release", 1));
        PolicyResolver resolver = new PolicyResolver(client);

        WorkflowSelection selection = resolver.resolve(100000L);

        assertThat(selection.workflowId()).isEqualTo("transfer-auto-release");
        assertThat(selection.workflowVersion()).isEqualTo(1);
    }
}
