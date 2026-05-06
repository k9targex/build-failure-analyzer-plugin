/*
 * The MIT License
 *
 * Copyright 2026 Generation P. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.sonyericsson.jenkins.plugins.bfa.pipeline;

import com.sonyericsson.jenkins.plugins.bfa.PluginImpl;
import com.sonyericsson.jenkins.plugins.bfa.utils.BfaUtils;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.accmod.restrictions.suppressions.SuppressRestrictedWarnings;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Returns a {@link Reader} for scanning a build log.
 *
 * <p>For Pipeline ({@link WorkflowRun}) builds, when step-based analysis is enabled,
 * the reader contains only the logs of {@link FlowNode}s that recorded an error,
 * not the entire console log. This avoids the per-file scan timeout firing on
 * very large console logs (hundreds of MB) where only a small portion is relevant
 * to the failure.
 *
 * <p>When the build is not a Pipeline run, when the pipeline plugin is not installed,
 * when no failed step is found, or when narrowing fails for any reason, this class
 * silently falls back to {@link Run#getLogReader()}, which reproduces the upstream
 * behaviour.
 */
@Restricted(NoExternalUse.class)
@SuppressRestrictedWarnings(LogStorage.class)
public final class PipelineLogReader {

    private static final Logger logger = Logger.getLogger(PipelineLogReader.class.getName());

    private PipelineLogReader() {
    }

    /**
     * Returns a reader for the log content that should be scanned by BFA.
     *
     * @param build the build to scan.
     * @return a reader; never {@code null}. Caller is responsible for closing it.
     * @throws IOException if even the fallback {@link Run#getLogReader()} fails.
     */
    @NonNull
    public static Reader openForScan(@NonNull Run build) throws IOException {
        return openForScan(build, null);
    }

    /**
     * Same as {@link #openForScan(Run)}, but additionally writes a one-line summary
     * of which scan source was selected (Pipeline narrow vs full log) to {@code scanLog}
     * so that the choice is visible in the BFA "Identified Problems" build action page.
     *
     * @param build the build to scan.
     * @param scanLog optional sink for a human-readable summary; may be {@code null}.
     * @return a reader; never {@code null}. Caller is responsible for closing it.
     * @throws IOException if even the fallback {@link Run#getLogReader()} fails.
     */
    @NonNull
    public static Reader openForScan(@NonNull Run build, @CheckForNull PrintStream scanLog) throws IOException {
        if (isStepBasedScanEnabled()) {
            NarrowResult narrow = tryNarrowToFailedSteps(build);
            if (narrow != null) {
                if (scanLog != null) {
                    scanLog.printf("[BFA] Pipeline narrow scan: %d failed step(s), %d bytes%n",
                            narrow.stepCount, narrow.bytes);
                }
                return narrow.reader;
            }
        }
        if (scanLog != null) {
            scanLog.println("[BFA] Full-log scan");
        }
        return build.getLogReader();
    }

    /**
     * @return {@code true} if Pipeline step-based scanning is enabled in plugin config
     * (or no plugin instance is available, in which case we default to enabled).
     */
    private static boolean isStepBasedScanEnabled() {
        PluginImpl plugin = BfaUtils.tryGetPluginInstance();
        if (plugin == null) {
            return true;
        }
        return plugin.isPipelineStepBasedScanEnabled();
    }

    /**
     * Holder for the result of a pipeline narrow scan attempt. Carries the
     * {@link Reader} plus diagnostic counters that callers can write to the BFA scanLog.
     */
    private static final class NarrowResult {
        final Reader reader;
        final int stepCount;
        final long bytes;

        NarrowResult(Reader reader, int stepCount, long bytes) {
            this.reader = reader;
            this.stepCount = stepCount;
            this.bytes = bytes;
        }
    }

    /**
     * Tries to produce a narrow reader containing only failed-step logs. Returns
     * {@code null} when narrowing is not applicable or fails for any reason
     * (no Pipeline plugin, no failed steps, IO error, etc.) — caller should
     * fall back to the full build log.
     *
     * <p>Distinguishes two failure modes by log level:
     * <ul>
     *   <li>{@link Level#FINE}: expected non-applicability — build is not a Pipeline,
     *       Pipeline plugin not on classpath, no failed step recorded.</li>
     *   <li>{@link Level#WARNING}: an actual error occurred while attempting to narrow
     *       (IO, unexpected runtime exception). Admins should notice this in jenkins.log.</li>
     * </ul>
     *
     * @param build a build that may or may not be a {@link WorkflowRun}.
     * @return narrow result, or {@code null} to indicate fallback.
     */
    @CheckForNull
    private static NarrowResult tryNarrowToFailedSteps(@NonNull Run build) {
        try {
            if (!(build instanceof WorkflowRun)) {
                return null;
            }
            return narrowForWorkflowRun((WorkflowRun)build);
        } catch (LinkageError e) {
            // workflow-api / workflow-job not on the runtime classpath — expected on
            // installations without the Pipeline plugin, log quietly.
            logger.log(Level.FINE, "Pipeline classes not available; falling back to full log", e);
            return null;
        } catch (RuntimeException | IOException e) {
            logger.log(Level.WARNING,
                    "Pipeline narrow scan failed for " + build.getFullDisplayName()
                            + "; falling back to full log",
                    e);
            return null;
        }
    }

    @CheckForNull
    private static NarrowResult narrowForWorkflowRun(@NonNull WorkflowRun run) throws IOException {
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            return null;
        }
        FlowExecutionOwner owner = execution.getOwner();
        if (owner == null) {
            return null;
        }
        List<FlowNode> failedNodes = findFailedNodes(execution);
        if (failedNodes.isEmpty()) {
            return null;
        }
        LogStorage storage = LogStorage.of(owner);

        // Stream into a temp file so a worst-case (parallel branches with huge step
        // logs each) does not blow up the Jenkins heap. The reader returned to the
        // caller deletes the temp file on close.
        Path tempFile = Files.createTempFile("bfa-narrow-", ".log");
        try {
            try (OutputStream raw = Files.newOutputStream(tempFile);
                 BufferedOutputStream out = new BufferedOutputStream(raw)) {
                for (FlowNode node : failedNodes) {
                    String header = "--- Step: " + node.getDisplayName()
                            + " (id=" + node.getId() + ") ---\n";
                    out.write(header.getBytes(StandardCharsets.UTF_8));
                    // Some steps (e.g. error '...') throw without writing to their step log;
                    // the message lives in ErrorAction. Include it so regex matchers see it.
                    ErrorAction errAction = node.getError();
                    if (errAction != null) {
                        Throwable t = errAction.getError();
                        if (t != null) {
                            String msg = t.getMessage();
                            if (msg != null && !msg.isEmpty()) {
                                out.write(msg.getBytes(StandardCharsets.UTF_8));
                                out.write('\n');
                            }
                        }
                    }
                    storage.stepLog(node, true).writeRawLogTo(0, out);
                    // Defensive trailing newline; double-newlines are harmless for matching.
                    out.write('\n');
                }
            }
            long bytes = Files.size(tempFile);
            return new NarrowResult(new SelfDeletingReader(tempFile), failedNodes.size(), bytes);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException suppressed) {
                logger.log(Level.FINE, "Could not delete temp narrow log " + tempFile, suppressed);
            }
            throw e;
        }
    }

    /**
     * UTF-8 file reader that deletes the underlying file on {@link #close()}.
     * Used for the temp file written by {@link #narrowForWorkflowRun}.
     */
    private static final class SelfDeletingReader extends Reader {
        private final Path path;
        private final Reader delegate;

        SelfDeletingReader(Path path) throws IOException {
            this.path = path;
            this.delegate = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            return delegate.read(cbuf, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    logger.log(Level.FINE, "Could not delete temp narrow log " + path, e);
                }
            }
        }
    }

    /**
     * Walks the flow graph and returns atomic-step nodes that recorded an error.
     * {@link FlowNode#getError()} is non-null on nodes that failed; we additionally
     * skip {@link BlockEndNode}s because they are zero-content terminators of
     * surrounding blocks (stage / node / parallel) that just propagate the error
     * upwards — including them in the narrowed log only adds noise headers.
     *
     * <p>The walker can yield duplicates for parallel branches; we de-duplicate by node id.
     *
     * @param execution the flow execution.
     * @return list of failed atomic-step nodes (may be empty).
     */
    private static List<FlowNode> findFailedNodes(@NonNull FlowExecution execution) {
        List<FlowNode> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        FlowGraphWalker walker = new FlowGraphWalker(execution);
        for (FlowNode node : walker) {
            if (!seen.add(node.getId())) {
                continue;
            }
            if (node instanceof BlockEndNode) {
                continue;
            }
            if (node.getError() != null) {
                failed.add(node);
            }
        }
        return failed;
    }

}
