package com.example.guardrails;

import org.apache.camel.ProducerTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP entry points. Every request goes into the same Camel route, direct:chat. */
@RestController
@RequestMapping("/api")
public class ChatController {

    public record ChatRequest(String message) {}

    private final ProducerTemplate producer;
    private final TestCaseRunner runner;

    public ChatController(ProducerTemplate producer, TestCaseRunner runner) {
        this.producer = producer;
        this.runner = runner;
    }

    @PostMapping("/chat")
    public ChatTurn chat(@RequestBody ChatRequest request) {
        ChatTurn turn = new ChatTurn();
        turn.message = request.message() == null ? "" : request.message();
        return producer.requestBody("direct:chat", turn, ChatTurn.class);
    }

    @GetMapping("/test-cases")
    public List<TestCaseRunner.TestCase> testCases() {
        return runner.cases();
    }

    @PostMapping("/test-cases/run")
    public List<TestCaseRunner.Result> runTestCases(@RequestParam(required = false) Integer only) {
        return runner.run(only);
    }
}
