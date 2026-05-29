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

package com.generationp.jenkins.plugins.bfa.pipeline;

import com.generationp.jenkins.plugins.bfa.PluginImpl;
import com.generationp.jenkins.plugins.bfa.utils.BfaUtils;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.console.AnnotatedLargeText;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    private static final long BYTES_IN_MEGABYTE = 1024L * 1024L;

    /**
     * Fallback for the per-step log tail cap when {@link PluginImpl} is not available (e.g. during early
     * plugin lifecycle or in tests). Configured at runtime via {@link PluginImpl#getMaxStepLogSizeMb()}.
     */
    private static final long FALLBACK_MAX_STEP_LOG_BYTES =
            (long)PluginImpl.DEFAULT_MAX_STEP_LOG_SIZE_MB * BYTES_IN_MEGABYTE;

    /**
     * Error message prefix that Jenkins workflow uses when one parallel branch fails and another sibling branch
     * is cancelled as a result. Atoms with this propagated error did not themselves fail; including their logs
     * just adds large blobs of unrelated content (the cancelled sibling's in-flight work) to the narrowed log.
     */
    private static final String PARALLEL_PROPAGATION_PREFIX = "Failed in branch ";

    /**
     * Resolves the current per-step log tail cap in bytes, reading from the live plugin config when available
     * and falling back to {@link #FALLBACK_MAX_STEP_LOG_BYTES} otherwise.
     *
     * @return cap in bytes (always &gt; 0).
     */
    private static long maxStepLogBytes() {
        PluginImpl plugin = BfaUtils.tryGetPluginInstance();
        if (plugin == null) {
            return FALLBACK_MAX_STEP_LOG_BYTES;
        }
        return (long)plugin.getMaxStepLogSizeMb() * BYTES_IN_MEGABYTE;
    }

    /**
     * Active scan sessions keyed by {@link Run#getExternalizableId()}. Populated by
     * {@link #beginScanSession} so that subsequent {@link #openForScan} calls for the same
     * build during one scan share a single pre-computed narrowed log instead of each
     * indication-scanning task re-running {@link #narrowForWorkflowRun} against the same
     * flow log (which on a large multibranch build with many causes can multiply the
     * underlying I/O by 100× and push the scan past its time budget).
     *
     * <p>The value is {@link #FALLBACK_SENTINEL} when a session was opened but narrowing
     * isn't applicable (non-Pipeline build, no failed atoms, IO error) — this lets
     * {@link #openForScan} recognise "session active, marker already emitted" and skip
     * its own legacy marker logic, avoiding duplicate {@code "[BFA] Full-log scan"}
     * lines on the build's Failure Scan Log page.
     */
    private static final ConcurrentMap<String, NarrowCache> SESSIONS = new ConcurrentHashMap<>();

    /**
     * Marker put into {@link #SESSIONS} when a scan session is active but narrowing didn't
     * produce a cache (non-Pipeline build, no failed atoms, etc.). Identity-compared.
     */
    private static final NarrowCache FALLBACK_SENTINEL = new NarrowCache(null, 0, 0L);

    private PipelineLogReader() {
    }

    /**
     * Begins a per-build scan session: pre-computes the narrowed log once and registers it so
     * subsequent {@link #openForScan} calls on the same build share it via fresh readers
     * over the same temp file, instead of each indication-scanning task re-doing
     * {@link #narrowForWorkflowRun} from scratch.
     *
     * <p>The session must be closed (try-with-resources) to delete the temp file and unregister
     * the entry. If narrowing isn't applicable (non-Pipeline build, no failed steps, Pipeline
     * plugin missing, IO error), the session falls back cleanly: subsequent {@link #openForScan}
     * calls return the full build log reader, as before.
     *
     * <p>Diagnostic markers are emitted to {@code scanLog}:
     * <ul>
     *   <li>{@code "[BFA] Pipeline narrow scan: starting…"} <em>before</em> the heavy I/O so the
     *       stage is visible even if the scan is interrupted mid-narrowing;</li>
     *   <li>{@code "[BFA] Pipeline narrow scan: N step(s), M bytes"} on success;</li>
     *   <li>{@code "[BFA] Full-log scan"} on fallback.</li>
     * </ul>
     *
     * @param build the build being scanned.
     * @param scanLog optional sink for human-readable markers; may be {@code null}.
     * @return a session token; never {@code null}.
     */
    @NonNull
    public static ScanSession beginScanSession(@NonNull Run build, @CheckForNull PrintStream scanLog) {
        if (scanLog != null) {
            scanLog.println("[BFA] Pipeline narrow scan: starting…");
            scanLog.flush();
        }
        NarrowCache cache = tryBuildNarrowCache(build);
        String key = build.getExternalizableId();
        boolean owner = false;
        if (key != null) {
            NarrowCache toPut;
            if (cache != null) {
                toPut = cache;
            } else {
                toPut = FALLBACK_SENTINEL;
            }
            NarrowCache existing = SESSIONS.putIfAbsent(key, toPut);
            if (existing == null) {
                owner = true;
            } else {
                // Concurrent scan of same build (rare): drop our copy and share the existing one.
                if (cache != null && cache.path != null) {
                    try {
                        Files.deleteIfExists(cache.path);
                    } catch (IOException e) {
                        logger.log(Level.FINE, "Could not delete redundant temp narrow log " + cache.path, e);
                    }
                }
                if (existing == FALLBACK_SENTINEL) {
                    cache = null;
                } else {
                    cache = existing;
                }
            }
        }
        if (scanLog != null) {
            if (cache != null) {
                scanLog.printf("[BFA] Pipeline narrow scan: %d step(s), %d bytes%n",
                        cache.stepCount, cache.bytes);
            } else {
                scanLog.println("[BFA] Full-log scan");
            }
            scanLog.flush();
        }
        return new ScanSession(key, cache, owner);
    }

    /**
     * Wrapper around {@link #buildNarrowCache} that translates the expected non-applicability and
     * unexpected failure paths into a {@code null} cache (so the caller falls back to the full
     * build log), distinguishing them only by log level — same policy as
     * {@link #tryNarrowToFailedSteps}.
     *
     * @param build the build being scanned.
     * @return the built cache, or {@code null} to indicate fallback.
     */
    @CheckForNull
    private static NarrowCache tryBuildNarrowCache(@NonNull Run build) {
        if (!isStepBasedScanEnabled()) {
            return null;
        }
        try {
            if (!(build instanceof WorkflowRun)) {
                return null;
            }
            return buildNarrowCache((WorkflowRun)build);
        } catch (LinkageError e) {
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
        // Active scan session: markers were already emitted by beginScanSession, so scanLog
        // is intentionally ignored here to avoid duplicate "Full-log scan" / step-count lines.
        String key = build.getExternalizableId();
        if (key != null) {
            NarrowCache cached = SESSIONS.get(key);
            if (cached == FALLBACK_SENTINEL) {
                return build.getLogReader();
            }
            if (cached != null) {
                return Files.newBufferedReader(cached.path, StandardCharsets.UTF_8);
            }
        }
        // Standalone path: no session active (direct external caller, e.g. tests).
        // Preserve legacy one-shot behavior including inline markers.
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
        NarrowCache cache = buildNarrowCache(run);
        if (cache == null) {
            return null;
        }
        try {
            return new NarrowResult(new SelfDeletingReader(cache.path), cache.stepCount, cache.bytes);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(cache.path);
            } catch (IOException suppressed) {
                logger.log(Level.FINE, "Could not delete temp narrow log " + cache.path, suppressed);
            }
            throw e;
        }
    }

    /**
     * Writes a narrowed temp file containing the {@link ErrorAction} messages and
     * (capped) tail logs of failed atom-step nodes. Returns the file path plus
     * step count and total byte size as a {@link NarrowCache}, or {@code null} when
     * narrowing isn't applicable (no execution / no owner / no failed atoms).
     *
     * <p>Caller owns the temp file and is responsible for deletion: either via
     * {@link ScanSession#close()} (when called from {@link #beginScanSession}) or via
     * {@link SelfDeletingReader#close()} (when wrapped by {@link #narrowForWorkflowRun}).
     *
     * @param run the WorkflowRun to narrow.
     * @return a fresh cache, or {@code null} when narrowing isn't applicable.
     * @throws IOException if writing the temp file fails.
     */
    @CheckForNull
    private static NarrowCache buildNarrowCache(@NonNull WorkflowRun run) throws IOException {
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
        // logs each) does not blow up the Jenkins heap.
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
                    AnnotatedLargeText<?> logText = storage.stepLog(node, true);
                    long len = logText.length();
                    long cap = maxStepLogBytes();
                    long start = Math.max(0L, len - cap);
                    if (start > 0L) {
                        String truncMarker = "[BFA] (step log truncated to last " + cap
                                + " bytes; original size " + len + " bytes, " + start + " bytes skipped)\n";
                        out.write(truncMarker.getBytes(StandardCharsets.UTF_8));
                    }
                    logText.writeRawLogTo(start, out);
                    // Defensive trailing newline; double-newlines are harmless for matching.
                    out.write('\n');
                }
            }
            long bytes = Files.size(tempFile);
            return new NarrowCache(tempFile, failedNodes.size(), bytes);
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
     * Path-based holder for a built narrowed log file, shared across scan tasks via
     * {@link #SESSIONS}. Unlike {@link NarrowResult}, this holds a {@link Path} (not a
     * {@link Reader}), so multiple independent readers can be opened over the same file.
     */
    private static final class NarrowCache {
        final Path path;
        final int stepCount;
        final long bytes;

        NarrowCache(Path path, int stepCount, long bytes) {
            this.path = path;
            this.stepCount = stepCount;
            this.bytes = bytes;
        }
    }

    /**
     * Closeable handle for an active scan session. Closing removes the session from
     * {@link #SESSIONS} and deletes the underlying temp file (if this token owns it).
     */
    public static final class ScanSession implements AutoCloseable {
        private final String key;
        private final NarrowCache cache;
        private final boolean owner;

        ScanSession(@CheckForNull String key, @CheckForNull NarrowCache cache, boolean owner) {
            this.key = key;
            this.cache = cache;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (!owner || key == null) {
                return;
            }
            // Remove the registration (real cache OR FALLBACK_SENTINEL stored when cache was null).
            NarrowCache registered;
            if (cache != null) {
                registered = cache;
            } else {
                registered = FALLBACK_SENTINEL;
            }
            SESSIONS.remove(key, registered);
            if (cache != null && cache.path != null) {
                try {
                    Files.deleteIfExists(cache.path);
                } catch (IOException e) {
                    logger.log(Level.FINE, "Could not delete temp narrow log " + cache.path, e);
                }
            }
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
            ErrorAction err = node.getError();
            if (err == null) {
                continue;
            }
            // Skip atoms that only received a propagated "Failed in branch X" error from a parallel block.
            // Such atoms did not themselves fail — they were cancelled because a sibling parallel branch failed.
            // Their step log contains the cancelled branch's in-flight output (often very large), which is
            // unrelated to the actual root cause that BFA is trying to identify.
            Throwable cause = err.getError();
            String message = null;
            if (cause != null) {
                message = cause.getMessage();
            }
            if (message != null && message.startsWith(PARALLEL_PROPAGATION_PREFIX)) {
                continue;
            }
            failed.add(node);
        }
        return failed;
    }

}
