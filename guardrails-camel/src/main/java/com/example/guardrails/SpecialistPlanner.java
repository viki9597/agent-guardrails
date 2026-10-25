package com.example.guardrails;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

/**
 * The privileged planner: an LLM that writes Scala against the planner
 * facade. Its prompt is built only from trusted inputs (the task label and
 * the API), never from the user's message.
 */
@Component
public class SpecialistPlanner implements Processor {

    private static final String SYSTEM_PROMPT =
            "You are the Specialist planner in an agent pipeline. Output ONLY Scala 3 statements: " +
            "no prose, no markdown fences, no imports, no @main.";

    private static final String API = """
            This API is already defined; do not redefine any of it:
              final class ApprovedIndex private (val name: String)
              object ApprovedIndex { def approve(name: String): Option[ApprovedIndex] }
              final class SearchText          // the user's query; you cannot read it, only pass it
              val userText: SearchText
              trait ElasticsearchApi { def search(index: ApprovedIndex, text: SearchText): Unit }
              def withElasticsearch[T](op: ElasticsearchApi^ => T): T

            Index catalog: task search-support -> "support-tickets", task search-docs -> "product-docs".
            """;

    private final ClaudeClient claude;

    public SpecialistPlanner(ClaudeClient claude) {
        this.claude = claude;
    }

    @Override
    public void process(Exchange exchange) {
        ChatTurn turn = exchange.getMessage().getBody(ChatTurn.class);

        if (turn.plannerCodeOverride != null) {
            turn.plannerCode = turn.plannerCodeOverride;
            turn.pass(ChatTurn.PLANNER, "code supplied by the test case (simulated compromised planner)");
            return;
        }

        String prompt = API + "\nTask: " + turn.task + ". Search the catalog index for this task with userText, " +
                "exactly once. Obtain the index with ApprovedIndex.approve and wrap the call in withElasticsearch.";
        try {
            turn.plannerCode = stripFences(claude.ask(SYSTEM_PROMPT, prompt));
        } catch (Exception e) {
            turn.block(ChatTurn.PLANNER, "planner unavailable");
            return;
        }
        turn.pass(ChatTurn.PLANNER, "wrote " + turn.plannerCode.lines().count() + " lines of Scala from the task alone");
    }

    private static String stripFences(String code) {
        return code.lines().filter(l -> !l.strip().startsWith("```"))
                .reduce((a, b) -> a + "\n" + b).orElse("").strip();
    }
}
