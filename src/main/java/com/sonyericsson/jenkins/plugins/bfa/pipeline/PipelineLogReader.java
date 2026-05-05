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
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ExtensionList;
import hudson.model.Run;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.accmod.restrictions.suppressions.SuppressRestrictedWarnings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
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
        if (isStepBasedScanEnabled()) {
            Reader narrow = tryNarrowToFailedSteps(build);
            if (narrow != null) {
                return narrow;
            }
        }
        return build.getLogReader();
    }

    /**
     * @return {@code true} if Pipeline step-based scanning is enabled in plugin config
     * (or no plugin instance is available, in which case we default to enabled).
     */
    private static boolean isStepBasedScanEnabled() {
        try {
            ExtensionList<PluginImpl> list = ExtensionList.lookup(PluginImpl.class);
            if (list == null || list.isEmpty()) {
                return true;
            }
            return list.get(0).isPipelineStepBasedScanEnabled();
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Tries to produce a narrow reader containing only failed-step logs. Returns
     * {@code null} when narrowing is not applicable or fails for any reason
     * (no Pipeline plugin, no failed steps, IO error, etc.) — caller should
     * fall back to the full build log.
     *
     * @param build a build that may or may not be a {@link WorkflowRun}.
     * @return narrow reader, or {@code null} to indicate fallback.
     */
    @CheckForNull
    private static Reader tryNarrowToFailedSteps(@NonNull Run build) {
        try {
            if (!(build instanceof WorkflowRun)) {
                return null;
            }
            return narrowForWorkflowRun((WorkflowRun)build);
        } catch (LinkageError e) {
            // workflow-api / workflow-job not on the runtime classpath
            logger.log(Level.FINE, "Pipeline classes not available; falling back to full log", e);
            return null;
        } catch (RuntimeException | IOException e) {
            logger.log(Level.FINE, "Pipeline narrow scan failed; falling back to full log", e);
            return null;
        }
    }

    @CheckForNull
    private static Reader narrowForWorkflowRun(@NonNull WorkflowRun run) throws IOException {
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
        StringBuilder sb = new StringBuilder();
        for (FlowNode node : failedNodes) {
            sb.append("--- Step: ").append(node.getDisplayName())
                    .append(" (id=").append(node.getId()).append(") ---\n");
            // Some steps (e.g. error '...') throw without writing to their step log;
            // the message lives in ErrorAction. Include it so regex matchers see it.
            ErrorAction errAction = node.getError();
            if (errAction != null) {
                Throwable t = errAction.getError();
                if (t != null) {
                    String msg = t.getMessage();
                    if (msg != null && !msg.isEmpty()) {
                        sb.append(msg).append('\n');
                    }
                }
            }
            String body = readNodeLog(storage, node);
            sb.append(body);
            if (!body.isEmpty() && body.charAt(body.length() - 1) != '\n') {
                sb.append('\n');
            }
        }
        return new StringReader(sb.toString());
    }

    /**
     * Walks the flow graph and returns nodes that recorded an error. {@link FlowNode#getError()}
     * is non-null on nodes that failed. The walker can yield duplicates for parallel branches;
     * we de-duplicate by node id.
     *
     * @param execution the flow execution.
     * @return list of failed nodes (may be empty).
     */
    private static List<FlowNode> findFailedNodes(@NonNull FlowExecution execution) {
        List<FlowNode> failed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        FlowGraphWalker walker = new FlowGraphWalker(execution);
        for (FlowNode node : walker) {
            if (seen.add(node.getId()) && node.getError() != null) {
                failed.add(node);
            }
        }
        return failed;
    }

    /**
     * Reads the raw text log for a single FlowNode via {@link LogStorage#stepLog}.
     *
     * @param storage the log storage.
     * @param node the FlowNode whose log to read.
     * @return UTF-8 decoded log text (may be empty).
     * @throws IOException on read failure.
     */
    private static String readNodeLog(@NonNull LogStorage storage, @NonNull FlowNode node) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        storage.stepLog(node, true).writeRawLogTo(0, baos);
        return baos.toString(StandardCharsets.UTF_8);
    }
}
