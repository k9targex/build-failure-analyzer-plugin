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
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.tasks.Shell;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.BufferedReader;
import java.io.Reader;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link PipelineLogReader#openForScan(hudson.model.Run)} narrows down
 * to failed-step logs for Pipeline builds and falls back to the full log otherwise.
 */
@WithJenkins
class PipelineLogReaderTest {

    /**
     * On a non-Pipeline FreeStyle build the helper must return the full log
     * unchanged — the legacy upstream behaviour.
     *
     * @param j the JenkinsRule.
     * @throws Exception if so.
     */
    @Test
    void freeStyleBuildReturnsFullLog(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("freestyle-fallback");
        p.getBuildersList().add(new Shell("echo MARKER_LINE_FREESTYLE && exit 1"));
        FreeStyleBuild b = j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        try (Reader r = PipelineLogReader.openForScan(b)) {
            String log = readAll(r);
            assertTrue(log.contains("MARKER_LINE_FREESTYLE"),
                    "Expected the full FreeStyle log to be returned, got: " + log);
        }
    }

    /**
     * On a Pipeline build with a failed step, the helper must return only the
     * failed step's log — not the entire console log. Successful step output
     * (in particular a noisy preamble) must be excluded.
     *
     * @param j the JenkinsRule.
     * @throws Exception if so.
     */
    @Test
    void pipelineBuildReturnsOnlyFailedStepLog(JenkinsRule j) throws Exception {
        WorkflowJob proj = j.jenkins.createProject(WorkflowJob.class, "pipeline-narrow");
        // First step succeeds and prints a unique marker; second step fails with another marker.
        // We expect the narrowed reader to contain the failure marker but NOT the success marker.
        proj.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  echo 'PRE_NOISE_SUCCESS_MARKER'\n"
                        + "  error 'BOOM_FAILURE_MARKER'\n"
                        + "}\n",
                true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, proj.scheduleBuild2(0));
        try (Reader r = PipelineLogReader.openForScan(run)) {
            String log = readAll(r);
            assertTrue(log.contains("BOOM_FAILURE_MARKER"),
                    "Failed step's marker must be present, got: " + log);
            assertFalse(log.contains("PRE_NOISE_SUCCESS_MARKER"),
                    "Successful step's marker must NOT be present in narrowed log, got: " + log);
        }
    }

    /**
     * When step-based scanning is disabled in the plugin config, the helper must
     * return the full Pipeline log including successful step output.
     *
     * @param j the JenkinsRule.
     * @throws Exception if so.
     */
    @Test
    void disabledFlagReturnsFullPipelineLog(JenkinsRule j) throws Exception {
        PluginImpl.getInstance().setPipelineStepBasedScanEnabled(false);
        try {
            WorkflowJob proj = j.jenkins.createProject(WorkflowJob.class, "pipeline-disabled");
            proj.setDefinition(new CpsFlowDefinition(
                    "node {\n"
                            + "  echo 'PRE_NOISE_FULL_LOG'\n"
                            + "  error 'BOOM_FULL_LOG'\n"
                            + "}\n",
                    true));
            WorkflowRun run = j.assertBuildStatus(Result.FAILURE, proj.scheduleBuild2(0));
            try (Reader r = PipelineLogReader.openForScan(run)) {
                String log = readAll(r);
                assertTrue(log.contains("PRE_NOISE_FULL_LOG"),
                        "When step-based scanning is disabled the full log must be used, got: " + log);
                assertTrue(log.contains("BOOM_FULL_LOG"));
            }
        } finally {
            PluginImpl.getInstance().setPipelineStepBasedScanEnabled(true);
        }
    }

    /**
     * On a successful Pipeline build (no failed FlowNode) the helper must fall back
     * to the full log. BFA may scan successful builds via on-demand scanning.
     *
     * @param j the JenkinsRule.
     * @throws Exception if so.
     */
    @Test
    void successfulPipelineFallsBackToFullLog(JenkinsRule j) throws Exception {
        WorkflowJob proj = j.jenkins.createProject(WorkflowJob.class, "pipeline-success");
        proj.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  echo 'GREEN_BUILD_MARKER'\n"
                        + "}\n",
                true));
        WorkflowRun run = j.assertBuildStatusSuccess(proj.scheduleBuild2(0));
        try (Reader r = PipelineLogReader.openForScan(run)) {
            String log = readAll(r);
            assertTrue(log.contains("GREEN_BUILD_MARKER"),
                    "Successful pipeline must fall back to full log, got: " + log);
        }
    }

    /**
     * On a Pipeline with parallel branches, both branches' failures must be present
     * in the narrowed reader, regardless of which branch failed first. We deliberately
     * don't pin order — BFA scans the whole reader, so as long as both markers appear,
     * regex matching works correctly.
     *
     * @param j the JenkinsRule.
     * @throws Exception if so.
     */
    @Test
    void parallelBranchesIncludeAllFailures(JenkinsRule j) throws Exception {
        WorkflowJob proj = j.jenkins.createProject(WorkflowJob.class, "pipeline-parallel");
        proj.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  parallel(\n"
                        + "    branchA: { error 'ALPHA_FAILURE_MARKER' },\n"
                        + "    branchB: { error 'BETA_FAILURE_MARKER' }\n"
                        + "  )\n"
                        + "}\n",
                true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, proj.scheduleBuild2(0));
        try (Reader r = PipelineLogReader.openForScan(run)) {
            String log = readAll(r);
            assertTrue(log.contains("ALPHA_FAILURE_MARKER"),
                    "Parallel branchA marker must be present, got: " + log);
            assertTrue(log.contains("BETA_FAILURE_MARKER"),
                    "Parallel branchB marker must be present, got: " + log);
        }
    }

    /**
     * The 2-arg overload writes a one-line summary into the supplied PrintStream so
     * admins can see in the BFA scan log which mode was used.
     *
     * @param j the JenkinsRule.
     * @throws Exception if so.
     */
    @Test
    void scanLogReceivesSourceSummary(JenkinsRule j) throws Exception {
        WorkflowJob proj = j.jenkins.createProject(WorkflowJob.class, "pipeline-scanlog");
        proj.setDefinition(new CpsFlowDefinition(
                "node { error 'TRIGGER' }\n",
                true));
        WorkflowRun run = j.assertBuildStatus(Result.FAILURE, proj.scheduleBuild2(0));
        java.io.ByteArrayOutputStream sink = new java.io.ByteArrayOutputStream();
        try (java.io.PrintStream scanLog = new java.io.PrintStream(sink, true, "UTF-8");
             Reader r = PipelineLogReader.openForScan(run, scanLog)) {
            // drain
            readAll(r);
            String summary = sink.toString("UTF-8");
            assertTrue(summary.contains("Pipeline narrow scan"),
                    "Expected scanLog to mention narrow mode, got: " + summary);
        }
    }

    private static String readAll(Reader r) throws java.io.IOException {
        try (BufferedReader br = new BufferedReader(r)) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }
}
