package com.example.tacitdemo;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class BankMockService {

    public record User(String id, String name, String email, String ssn) {}

    public record Transaction(String id, String description, double amount, String date) {}

    private static final Map<String, User> USERS = Map.of(
            "u-1001", new User("u-1001", "Jordan Lee", "jordan.lee@acmebank.test", "521-04-9981"),
            "u-1002", new User("u-1002", "Priya Nair", "priya.nair@acmebank.test", "487-22-1150")
    );

    private static final Map<String, List<Transaction>> TRANSACTIONS = Map.of(
            "u-1001", List.of(
                    new Transaction("t-1", "Grocery - Whole Foods", -84.20, "2026-10-14"),
                    new Transaction("t-2", "Payroll deposit", 3250.00, "2026-10-10"),
                    new Transaction("t-3", "Wire transfer - acct-42", -1500.00, "2026-10-08")
            ),
            "u-1002", List.of(
                    new Transaction("t-4", "Coffee shop", -6.50, "2026-10-15"),
                    new Transaction("t-5", "Payroll deposit", 4100.00, "2026-10-10")
            )
    );

    @Tool(description = "Look up a bank customer's details by user id. Returns name, email, and SSN (mock data).")
    public User getUserDetails(@ToolParam(description = "The user id, e.g. u-1001") String userId) {
        User user = USERS.get(userId);
        if (user == null) {
            throw new IllegalArgumentException("No such user: " + userId);
        }
        return user;
    }

    @Tool(description = "List recent transactions for a bank customer by user id (mock data).")
    public List<Transaction> listRecentTransactions(@ToolParam(description = "The user id, e.g. u-1001") String userId) {
        return TRANSACTIONS.getOrDefault(userId, List.of());
    }
}
