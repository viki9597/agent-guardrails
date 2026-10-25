package com.example.guardrails;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Progent policy gate: an independent check on every call the program
 * asked for, before any of them run. Stricter than ApprovedIndex: each
 * task may only use its own index, and only a limited number of times.
 */
@Component
public class ProgentGuard implements Processor {

    private static final Map<String, Set<String>> ALLOWED_INDICES = Map.of(
            "search-support", Set.of("support-tickets"),
            "search-docs", Set.of("product-docs")
    );
    private static final Set<String> ALLOWED_OPERATIONS = Set.of("search");
    private static final int MAX_CALLS = 2;

    @Override
    public void process(Exchange exchange) {
        ChatTurn turn = exchange.getMessage().getBody(ChatTurn.class);

        if (turn.toolCalls.size() > MAX_CALLS) {
            turn.block(ChatTurn.PROGENT, turn.toolCalls.size() + " calls requested; policy allows at most " + MAX_CALLS);
            return;
        }
        Set<String> allowed = ALLOWED_INDICES.getOrDefault(turn.task, Set.of());
        for (ChatTurn.ToolCall call : turn.toolCalls) {
            if (!ALLOWED_OPERATIONS.contains(call.operation())) {
                turn.block(ChatTurn.PROGENT, "operation '" + call.operation() + "' is not allowed");
                return;
            }
            if (!allowed.contains(call.index())) {
                turn.block(ChatTurn.PROGENT, "index '" + call.index() + "' is not allowed for task '" + turn.task + "'");
                return;
            }
        }
        turn.pass(ChatTurn.PROGENT, turn.toolCalls.size() + " call(s) within policy for task '" + turn.task + "'");
    }
}
