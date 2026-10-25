package com.example.guardrails;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Progent policy gate: an independent check on every call the program
 * asked for, before any of them run. Stricter than ApprovedIndex: only the
 * indices of the tasks in this message are allowed, a limited number of times.
 */
@Component
public class ProgentGuard implements Processor {

    private static final Set<String> ALLOWED_OPERATIONS = Set.of("search");
    private static final int MAX_CALLS = 2;

    @Override
    public void process(Exchange exchange) {
        ChatTurn turn = exchange.getMessage().getBody(ChatTurn.class);

        if (turn.toolCalls.size() > MAX_CALLS) {
            turn.block(ChatTurn.PROGENT, turn.toolCalls.size() + " calls requested; policy allows at most " + MAX_CALLS);
            return;
        }
        Set<String> allowed = turn.tasks.stream().map(Catalog.INDEX_FOR_TASK::get).collect(Collectors.toSet());
        String tasks = String.join(", ", turn.tasks);
        for (ChatTurn.ToolCall call : turn.toolCalls) {
            if (!ALLOWED_OPERATIONS.contains(call.operation())) {
                turn.block(ChatTurn.PROGENT, "operation '" + call.operation() + "' is not allowed");
                return;
            }
            if (!allowed.contains(call.index())) {
                turn.block(ChatTurn.PROGENT, "index '" + call.index() + "' is not allowed for task(s) '" + tasks + "'");
                return;
            }
        }
        turn.pass(ChatTurn.PROGENT, turn.toolCalls.size() + " call(s) within policy for task(s) '" + tasks + "'");
    }
}
