package com.example.tacitmcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The entire capability surface exposed to the agent is this one method.
 * Submitted code is wrapped around a fixed capability library (BankApi,
 * Classified) and compiled fresh, with Scala 3 capture checking enabled,
 * via the real scala-cli / Scala 3 compiler. Only code that passes
 * compilation ever runs.
 *
 * Capture checking only tracks values typed as capabilities (^). JDK APIs
 * and Predef are ambient authority it does not see, so this service is a
 * demo of the type-level boundary, not a sandbox. A production deployment
 * would also run the child process in an OS/container sandbox.
 */
@Service
public class ScalaEvalService {

    private static final String BANK_HOST = "http://localhost:8081";
    private static final long COMPILE_TIMEOUT_SECONDS = 90;
    private static final long RUN_TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 16_000;
    // Each evaluation spawns a scala-cli JVM; cap how many run at once.
    private static final Semaphore SLOTS = new Semaphore(2);

    // Defense in depth, same role as TACIT's code validator: a fast,
    // pre-compile rejection of obviously unsafe escape hatches. It is a
    // denylist and therefore incomplete; the real guarantee is capture
    // checking in the compile step below.
    private static final List<String> BLOCKED_PATTERNS = List.of(
            "java.", "javax.", "scala.sys", "sys.", "scala.io", "scala.reflect", "scala.util.Using",
            "Runtime", "ProcessBuilder", "System", "Thread", "ClassLoader", "Class.forName",
            "getClass", "classOf", "getDeclared", "setAccessible", "caps.unsafe", "asInstanceOf",
            "import ", "//>", "Classified.wrap"
    );

    private static final Pattern ANSI = Pattern.compile("\\u001B?\\[[;\\d]*m");

    private record Result(boolean timedOut, int exitCode, String output) {}

    @Tool(description = "The ONLY tool available. Compiles and runs a Scala 3 snippet with capture checking " +
            "enabled, against a fixed capability library. Available in scope: withBankApi(op: BankApi^ => T): T, " +
            "which hands your code a BankApi^ capability for one fixed, pre-approved bank host. BankApi exposes " +
            "getUserDetails(userId: String): Classified[User] and listRecentTransactions(userId: String): " +
            "Classified[List[Transaction]]. Classified values can only be transformed with .map(pure fn) and " +
            "previewed with .preview — there is no method anywhere in scope that returns a raw value out of " +
            "Classified. No imports, other capabilities, or hosts are available. Submit the BODY of a program " +
            "(no @main, no package) — it will be wrapped and compiled for you.")
    public String evaluateScala(
            @ToolParam(description = "Scala 3 statements to run, e.g.: withBankApi { api => println(api.getUserDetails(\"u-1001\").preview) }")
            String code) {

        for (String blocked : BLOCKED_PATTERNS) {
            if (code.contains(blocked)) {
                return "error: use of '" + blocked.strip() + "' is not allowed (rejected by the code validator before compilation)";
            }
        }

        if (!SLOTS.tryAcquire()) {
            return "error: evaluator busy, try again shortly";
        }
        Path tempDir = null;
        try {
            String prelude;
            try (InputStream in = getClass().getResourceAsStream("/facade_prelude.scala")) {
                prelude = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("BANK_HOST", BANK_HOST);
            }

            String fullSource = prelude + "\n\n@main def run(): Unit =\n" + indent(code) + "\n";

            tempDir = Files.createTempDirectory("tacit-eval");
            Path scriptFile = tempDir.resolve("snippet.scala");
            Files.writeString(scriptFile, fullSource);

            // Compile first: compiler errors are useful feedback for the agent.
            Result compiled = exec(tempDir, COMPILE_TIMEOUT_SECONDS, "scala-cli", "compile", scriptFile.toString());
            if (compiled.timedOut()) {
                return "error: compilation timed out after " + COMPILE_TIMEOUT_SECONDS + "s";
            }
            if (compiled.exitCode() != 0) {
                return compiled.output();
            }

            // Runtime failures return a generic message: stack traces and
            // exception messages can carry values from inside Classified.
            Result ran = exec(tempDir, RUN_TIMEOUT_SECONDS, "scala-cli", "run", scriptFile.toString());
            if (ran.timedOut()) {
                return "error: evaluation timed out after " + RUN_TIMEOUT_SECONDS + "s";
            }
            if (ran.exitCode() != 0) {
                return "error: program failed at runtime (exit code " + ran.exitCode() + ")";
            }
            return ran.output();
        } catch (Exception e) {
            return "error: evaluation failed (" + e.getClass().getSimpleName() + ")";
        } finally {
            SLOTS.release();
            deleteQuietly(tempDir);
        }
    }

    // Output goes to a file rather than a pipe, so waitFor's timeout is
    // actually enforced instead of blocking on a read until the child exits.
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
        String output = ANSI.matcher(Files.readString(log, StandardCharsets.UTF_8)).replaceAll("").strip();
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = output.substring(0, MAX_OUTPUT_CHARS) + "\n... (output truncated)";
        }
        return new Result(false, process.exitValue(), output);
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
        }
    }

    private static String indent(String code) {
        return code.lines().map(l -> "  " + l).reduce((a, b) -> a + "\n" + b).orElse("  ()");
    }
}
