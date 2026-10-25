package com.example.guardrails;

import java.util.ArrayList;
import java.util.List;

/**
 * One chat message on its way through the Camel route. Each guardrail
 * appends a step to the trace; the first one that blocks sets blockedBy
 * and the route stops.
 */
public class ChatTurn {

    public static final String MULTI_CAMEL = "Multi-CaMeL";
    public static final String PLANNER = "Planner";
    public static final String TACIT = "TACIT";
    public static final String PROGENT = "Progent";
    public static final String ELASTICSEARCH = "Elasticsearch";

    public record Step(String layer, String verdict, String detail) {}

    public record ToolCall(String operation, String index) {}

    /** One request found inside the message, and whether it was answered or dropped. */
    public record Part(String text, String label, boolean answered) {}

    // Input. The overrides are only set by test cases, to simulate a
    // compromised classifier or planner without calling the LLM.
    public String message;
    public String taskOverride;
    public String plannerCodeOverride;

    // Filled in by the route. parts holds untrusted text and is only ever
    // shown to the user or used as search content; the planner gets tasks.
    public List<Part> parts = new ArrayList<>();
    public List<String> tasks = new ArrayList<>();
    public String plannerCode;
    public List<ToolCall> toolCalls = new ArrayList<>();
    public List<Step> trace = new ArrayList<>();
    public String answer;
    public String blockedBy;

    public void pass(String layer, String detail) {
        trace.add(new Step(layer, "PASS", detail));
    }

    public void block(String layer, String detail) {
        trace.add(new Step(layer, "BLOCK", detail));
        blockedBy = layer;
        answer = "Sorry, I can't help with that request.";
    }

    public boolean isBlocked() {
        return blockedBy != null;
    }

    public boolean anyDropped() {
        return parts.stream().anyMatch(p -> !p.answered());
    }

    /**
     * One-line verdict used by the test table, e.g. "ALLOW support-tickets",
     * "ALLOW product-docs (part dropped)" or "BLOCK TACIT".
     */
    public String outcome() {
        if (isBlocked()) return "BLOCK " + blockedBy;
        String indices = toolCalls.isEmpty() ? "(no search)"
                : String.join(" + ", toolCalls.stream().map(ToolCall::index).distinct().sorted().toList());
        return "ALLOW " + indices + (anyDropped() ? " (part dropped)" : "");
    }
}
