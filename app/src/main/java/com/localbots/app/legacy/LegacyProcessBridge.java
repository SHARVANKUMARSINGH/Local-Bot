package com.localbots.app.legacy;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Legacy Android Bridge providing backward-compatible ProcessBuilder execution,
 * environment sanitation, and stream management.
 */
public class LegacyProcessBridge {

    public static class ProcessExecutionResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final long executionTimeMs;

        public ProcessExecutionResult(int exitCode, String stdout, String stderr, long executionTimeMs) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.executionTimeMs = executionTimeMs;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getStdout() {
            return stdout;
        }

        public String getStderr() {
            return stderr;
        }

        public long getExecutionTimeMs() {
            return executionTimeMs;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public static ProcessExecutionResult executeShell(String command, File workingDir, Map<String, String> extraEnv, long timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        List<String> commandList = new ArrayList<>();
        commandList.add("/system/bin/sh");
        commandList.add("-c");
        commandList.add(command);

        ProcessBuilder pb = new ProcessBuilder(commandList);
        if (workingDir != null && workingDir.exists()) {
            pb.directory(workingDir);
        }

        Map<String, String> env = pb.environment();
        env.put("LANG", "en_US.UTF-8");
        env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" + env.get("PATH"));
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        int exitCode = -1;

        try {
            Process process = pb.start();

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });

            stdoutReader.start();
            stderrReader.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stderr.append("\n[Process execution timed out after ").append(timeoutSeconds).append(" seconds]");
                exitCode = 124;
            } else {
                exitCode = process.exitValue();
            }

            stdoutReader.join(1000);
            stderrReader.join(1000);

        } catch (Exception e) {
            stderr.append("Process execution failed: ").append(e.getMessage());
            exitCode = -1;
        }

        long executionTime = System.currentTimeMillis() - startTime;
        return new ProcessExecutionResult(exitCode, stdout.toString().trim(), stderr.toString().trim(), executionTime);
    }
}
