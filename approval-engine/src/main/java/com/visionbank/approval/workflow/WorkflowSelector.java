package com.visionbank.approval.workflow;

import com.visionbank.approval.service.InvalidRequestException;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class WorkflowSelector {

    private final Map<String, String> workflowIdByRequestType;
    private final WorkflowRegistry registry;

    public WorkflowSelector(String selectionClasspathResource, WorkflowRegistry registry) {
        this.registry = registry;
        this.workflowIdByRequestType = load(selectionClasspathResource);
        // Fail fast at startup if selection.yaml points at a workflow that was never loaded.
        workflowIdByRequestType.values().forEach(registry::get);
    }

    public WorkflowDefinition resolve(String requestType) {
        String workflowId = workflowIdByRequestType.get(requestType);
        if (workflowId == null) {
            throw new InvalidRequestException("No workflow selector configured for requestType: " + requestType);
        }
        return registry.get(workflowId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load(String classpathResource) {
        try (InputStream in = new ClassPathResource(classpathResource).getInputStream()) {
            Map<String, Object> raw = new Yaml().load(in);
            List<Map<String, String>> selectors = (List<Map<String, String>>) raw.get("selectors");
            Map<String, String> result = new java.util.HashMap<>();
            for (Map<String, String> s : selectors) {
                result.put(s.get("requestType"), s.get("workflowId"));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load workflow selection: " + classpathResource, e);
        }
    }
}
