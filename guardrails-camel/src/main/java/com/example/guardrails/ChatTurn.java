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

    // Input. The overrides are only set by test cases, to simulate a
    // compromised classifier or planner without calling the LLM.
    public String message;
    public String taskOverride;
    public String plannerCodeOverride;

    // Filled in by the route.
    public String task;
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

    /** One-line verdict used by the test table, e.g. "ALLOW support-tickets" or "BLOCK TACIT". */
    public String outcome() {
        if (isBlocked()) return "BLOCK " + blockedBy;
        if (toolCalls.isEmpty()) return "ALLOW (no search)";
        return "ALLOW " + toolCalls.get(0).index();
    }
}
