package com.example.guardrails;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.ProducerTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs test-cases.json through the same Camel route the chatbot uses and
 * compares each outcome with what the case expects.
 */
@Component
public class TestCaseRunner {

    /** One entry in test-cases.json. task/plannerCode are optional overrides. */
    public record TestCase(String name, String message, String task, String plannerCode, String expect, String why) {}

    public record Result(int number, String name, String expect, String actual, boolean pass, String why, ChatTurn turn) {}

    private final ProducerTemplate producer;
    private final List<TestCase> cases;

    public TestCaseRunner(ProducerTemplate producer, ObjectMapper mapper) throws IOException {
        this.producer = producer;
        try (InputStream in = getClass().getResourceAsStream("/test-cases.json")) {
            this.cases = mapper.readValue(in, new TypeReference<List<TestCase>>() {});
        }
    }

    public List<TestCase> cases() {
        return cases;
    }

    /** Runs every case, or only case number {@code only} (1-based) when it's given. */
    public List<Result> run(Integer only) {
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            if (only != null && only != i + 1) continue;
            TestCase tc = cases.get(i);
            ChatTurn turn = new ChatTurn();
            turn.message = tc.message() != null ? tc.message() : "How do I reset my password?";
            turn.taskOverride = tc.task();
            turn.plannerCodeOverride = tc.plannerCode();
            ChatTurn done = producer.requestBody("direct:chat", turn, ChatTurn.class);
            String actual = done.outcome();
            results.add(new Result(i + 1, tc.name(), tc.expect(), actual, actual.equals(tc.expect()), tc.why(), done));
        }
        return results;
    }
}
