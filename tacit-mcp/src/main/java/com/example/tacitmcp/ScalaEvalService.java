package com.example.tacitmcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The entire capability surface exposed to the agent is this one method.
 * Submitted code is wrapped around a fixed capability library (BankApi,
 * Classified) and compiled fresh, with Scala 3 capture checking enabled,
 * via the real scala-cli / Scala 3 compiler. Only code that passes
 * compilation ever runs.
 */
@Service
public class ScalaEvalService {

    private static final String BANK_HOST = "http://localhost:8081";

    // Defense in depth, same role as TACIT's code validator: a fast,
    // pre-compile rejection of obviously unsafe escape hatches. The real
    // guarantee is capture checking in the compile step below.
    private static final List<String> BLOCKED_PATTERNS = List.of(
            "java.io", "scala.sys.process", "sys.process", "Runtime.getRuntime",
            "scala.reflect", "ProcessBuilder", "caps.unsafe", "asInstanceOf"
    );

    private static final Pattern ANSI = Pattern.compile("\\[[;\\d]*m");

    @Tool(description = "The ONLY tool available. Compiles and runs a Scala 3 snippet with capture checking " +
            "enabled, against a fixed capability library. Available in scope: withBankApi(op: BankApi^ => T): T, " +
            "which hands your code a BankApi^ capability for one fixed, pre-approved bank host. BankApi exposes " +
            "getUserDetails(userId: String): Classified[User] and listRecentTransactions(userId: String): " +
            "Classified[List[Transaction]]. Classified values can only be transformed with .map(pure fn) and " +
            "previewed with .preview — there is no method anywhere in scope that returns a raw value out of " +
            "Classified. No other imports, capabilities, or hosts are available. Submit the BODY of a program " +
            "(no @main, no package) — it will be wrapped and compiled for you.")
    public String evaluateScala(
            @ToolParam(description = "Scala 3 statements to run, e.g.: withBankApi { api => println(api.getUserDetails(\"u-1001\").preview) }")
            String code) {

        for (String blocked : BLOCKED_PATTERNS) {
            if (code.contains(blocked)) {
                return "error: use of '" + blocked + "' is not allowed (rejected by the code validator before compilation)";
            }
        }

        try {
            String prelude;
            try (InputStream in = getClass().getResourceAsStream("/facade_prelude.scala")) {
                prelude = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("BANK_HOST", BANK_HOST);
            }

            String fullSource = prelude + "\n\n@main def run(): Unit =\n" + indent(code) + "\n";

            Path tempDir = Files.createTempDirectory("tacit-eval");
            Path scriptFile = tempDir.resolve("snippet.scala");
            Files.writeString(scriptFile, fullSource);

            ProcessBuilder pb = new ProcessBuilder("scala-cli", "run", scriptFile.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "error: evaluation timed out after 90s";
            }

            return ANSI.matcher(output).replaceAll("").strip();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private static String indent(String code) {
        return code.lines().map(l -> "  " + l).reduce((a, b) -> a + "\n" + b).orElse("  ()");
    }
}
