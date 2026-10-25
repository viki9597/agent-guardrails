package com.example.guardrails;

import java.util.Map;

/** The trusted task catalog: every task the chatbot supports, and the one index it may search. */
public final class Catalog {

    public static final Map<String, String> INDEX_FOR_TASK = Map.of(
            "search-support", "support-tickets",
            "search-docs", "product-docs"
    );

    private Catalog() {}
}
