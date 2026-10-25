package com.example.guardrails;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Calls the local `claude` CLI in print mode with every tool disabled, from
 * an empty temp directory, so each call is a plain text-in/text-out model call.
 */
@Component
public class ClaudeClient {

    private static final long TIMEOUT_SECONDS = 120;

    private final String model;

    public ClaudeClient(@Value("${guardrails.claude.model:haiku}") String model) {
        this.model = model;
    }

    public String ask(String systemPrompt, String prompt) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("guardrails-claude");
            Path in = Files.writeString(dir.resolve("prompt.txt"), prompt);
            Path out = dir.resolve("reply.txt");
            Process process = new ProcessBuilder(
                    "claude", "-p",
                    "--model", model,
                    "--system-prompt", systemPrompt,
                    "--strict-mcp-config",
                    "--no-session-persistence",
                    "--disable-slash-commands",
                    "--tools", "")
                    .directory(dir.toFile())
                    .redirectInput(in.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(out.toFile())
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("claude -p timed out after " + TIMEOUT_SECONDS + "s");
            }
            String reply = Files.readString(out, StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("claude -p failed: " + reply);
            }
            return reply;
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        } finally {
            TacitGuard.deleteQuietly(dir);
        }
    }
}
