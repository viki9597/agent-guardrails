package com.example.guardrails;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * In-memory stand-in for Elasticsearch. Runs only the calls Progent
 * approved. This is also the first place the user's actual text is used,
 * and only as search content.
 */
@Component
public class MockElasticsearch implements Processor {

    private static final Map<String, List<String>> INDICES = Map.of(
            "support-tickets", List.of(
                    "To reset your password, open Settings > Security > Reset password.",
                    "Locked out? Use 'Forgot password' on the sign-in page to get a reset email.",
                    "Billing questions: invoices are under Settings > Billing."),
            "product-docs", List.of(
                    "API rate limit: 100 requests per minute per API key.",
                    "List endpoints paginate with the 'cursor' parameter.",
                    "Webhooks are retried up to 5 times with exponential backoff."),
            // Never reachable through the guardrails; here so a bypass would be obvious.
            "admin-secrets", List.of("ADMIN_API_KEY=mock-value-not-real")
    );

    @Override
    public void process(Exchange exchange) {
        ChatTurn turn = exchange.getMessage().getBody(ChatTurn.class);

        if (turn.toolCalls.isEmpty()) {
            turn.answer = "I didn't search anything for that request.";
            turn.pass(ChatTurn.ELASTICSEARCH, "no calls to run");
            return;
        }
        StringBuilder answer = new StringBuilder();
        for (ChatTurn.ToolCall call : turn.toolCalls) {
            List<String> hits = search(call.index(), turn.message);
            hits.forEach(h -> answer.append(h).append("\n"));
            turn.pass(ChatTurn.ELASTICSEARCH, "searched '" + call.index() + "': " + hits.size() + " hit(s)");
        }
        turn.answer = answer.toString().strip();
    }

    // Ranks documents by how many longer words they share with the query.
    private static List<String> search(String index, String query) {
        Set<String> words = tokens(query);
        List<String> docs = INDICES.getOrDefault(index, List.of());
        return docs.stream()
                .sorted(Comparator.comparingLong((String d) -> tokens(d).stream().filter(words::contains).count()).reversed())
                .limit(2)
                .toList();
    }

    private static Set<String> tokens(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z]+"))
                .filter(w -> w.length() > 3)
                .collect(Collectors.toSet());
    }
}
