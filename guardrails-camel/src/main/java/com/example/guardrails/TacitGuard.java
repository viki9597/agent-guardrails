package com.example.guardrails;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TACIT: the planner's code only runs if it passes the validator and
 * compiles against the planner facade with capture checking on. Running it
 * doesn't touch Elasticsearch: it only records which calls it wants, for
 * Progent to check next.
 */
@Component
public class TacitGuard implements Processor {

    private static final long COMPILE_TIMEOUT_SECONDS = 180;
    private static final long RUN_TIMEOUT_SECONDS = 30;
    private static final Semaphore SLOTS = new Semaphore(2);

    // Fast pre-compile denylist, same role as in tacit-mcp. Incomplete by
    // nature; the compile step is the real check.
    private static final List<String> BLOCKED_PATTERNS = List.of(
            "java.", "javax.", "scala.sys", "sys.", "scala.io", "scala.reflect", "Runtime",
            "ProcessBuilder", "System", "Thread", "ClassLoader", "Class.forName", "getClass", "classOf",
            "getDeclared", "setAccessible", "caps.unsafe", "asInstanceOf", "import ", "//>", "TOOLCALL"
    );

    private static final Pattern ANSI = Pattern.compile("\\u001B?\\[[;\\d]*m");

    private record Result(boolean timedOut, int exitCode, String output) {}

    @Override
    public void process(Exchange exchange) throws Exception {
        ChatTurn turn = exchange.getMessage().getBody(ChatTurn.class);
        String code = turn.plannerCode;

        for (String blocked : BLOCKED_PATTERNS) {
            if (code.contains(blocked)) {
                turn.block(ChatTurn.TACIT, "validator rejected '" + blocked.strip() + "' before compiling");
                return;
            }
        }

        if (!SLOTS.tryAcquire(60, TimeUnit.SECONDS)) {
            turn.block(ChatTurn.TACIT, "evaluator busy");
            return;
        }
        Path dir = null;
        try {
            String nonce = UUID.randomUUID().toString();
            String facade;
            try (InputStream in = getClass().getResourceAsStream("/planner_facade.scala")) {
                facade = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("RUN_NONCE", nonce);
            }
            dir = Files.createTempDirectory("guardrails-tacit");
            Path file = dir.resolve("plan.scala");
            Files.writeString(file, facade + "\n\n@main def run(): Unit =\n" + indent(code) + "\n");

            Result compiled = exec(dir, COMPILE_TIMEOUT_SECONDS, "scala-cli", "compile", file.toString());
            if (compiled.timedOut()) {
                turn.block(ChatTurn.TACIT, "compilation timed out");
                return;
            }
            if (compiled.exitCode() != 0) {
                turn.block(ChatTurn.TACIT, "compile error: " + compileErrors(compiled.output()));
                return;
            }

            Result ran = exec(dir, RUN_TIMEOUT_SECONDS, "scala-cli", "run", file.toString());
            if (ran.timedOut() || ran.exitCode() != 0) {
                turn.block(ChatTurn.TACIT, ran.timedOut() ? "program timed out" : "program failed at runtime");
                return;
            }

            String prefix = nonce + " TOOLCALL ";
            ran.output().lines().filter(l -> l.startsWith(prefix)).forEach(l -> {
                String[] parts = l.substring(prefix.length()).split(" ", 2);
                turn.toolCalls.add(new ChatTurn.ToolCall(parts[0], parts.length > 1 ? parts[1] : ""));
            });
            turn.pass(ChatTurn.TACIT, "compiled with capture checking; requested " + turn.toolCalls.size() + " call(s)");
        } finally {
            SLOTS.release();
            deleteQuietly(dir);
        }
    }

    /** The compiler's own explanation, minus paths, carets and hints. */
    private static String compileErrors(String output) {
        return output.lines()
                .filter(l -> l.startsWith("[error]"))
                .map(l -> l.substring("[error]".length()).strip())
                .filter(l -> !l.isEmpty() && !l.matches(".*\\.scala:\\d+:\\d+.*") && !l.matches("[\\^ ]+"))
                .limit(4)
                .collect(Collectors.joining(" / "));
    }

    // Output goes to a file, not a pipe, so the timeout is really enforced.
    private static Result exec(Path dir, long timeoutSeconds, String... command) throws IOException, InterruptedException {
        Path log = dir.resolve("out-" + command[1] + ".log");
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return new Result(true, -1, "");
        }
        String output = ANSI.matcher(Files.readString(log, StandardCharsets.UTF_8)).replaceAll("");
        return new Result(false, process.exitValue(), output);
    }

    private static String indent(String code) {
        return code.lines().map(l -> "  " + l).reduce((a, b) -> a + "\n" + b).orElse("  ()");
    }

    static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }
}
