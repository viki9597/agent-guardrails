package com.example.guardrails;

import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * The whole pipeline as one Camel route. Read top to bottom: each step
 * runs one guardrail, and the route stops at the first one that blocks.
 */
@Component
public class GuardrailRoute extends RouteBuilder {

    private static final Predicate BLOCKED = ex -> ex.getMessage().getBody(ChatTurn.class).isBlocked();

    private final MultiCamelGuard multiCamel;
    private final SpecialistPlanner planner;
    private final TacitGuard tacit;
    private final ProgentGuard progent;
    private final MockElasticsearch elasticsearch;

    public GuardrailRoute(MultiCamelGuard multiCamel, SpecialistPlanner planner, TacitGuard tacit,
                          ProgentGuard progent, MockElasticsearch elasticsearch) {
        this.multiCamel = multiCamel;
        this.planner = planner;
        this.tacit = tacit;
        this.progent = progent;
        this.elasticsearch = elasticsearch;
    }

    @Override
    public void configure() {
        from("direct:chat").routeId("guardrails")
                .process(multiCamel)    // quarantined LLM: message -> one allowed task label
                .choice().when(BLOCKED).stop().end()
                .process(planner)       // privileged LLM: task -> Scala code (never sees the message)
                .choice().when(BLOCKED).stop().end()
                .process(tacit)         // validate + compile with capture checking, run, collect calls
                .choice().when(BLOCKED).stop().end()
                .process(progent)       // policy check on every requested call
                .choice().when(BLOCKED).stop().end()
                .process(elasticsearch); // only now does anything get searched
    }
}
