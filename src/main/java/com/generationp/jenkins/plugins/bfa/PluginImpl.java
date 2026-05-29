/*
 * The MIT License
 *
 * Copyright 2012 Sony Ericsson Mobile Communications. All rights reserved.
 * Copyright 2012 Sony Mobile Communications AB. All rights reserved.
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

package com.generationp.jenkins.plugins.bfa;

import com.generationp.jenkins.plugins.bfa.db.KnowledgeBase;
import com.generationp.jenkins.plugins.bfa.db.LocalFileKnowledgeBase;
import com.generationp.jenkins.plugins.bfa.model.FailureCause;
import com.generationp.jenkins.plugins.bfa.model.ScannerJobProperty;
import com.generationp.jenkins.plugins.bfa.sod.ScanOnDemandQueue;
import com.generationp.jenkins.plugins.bfa.sod.ScanOnDemandVariables;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.init.Terminator;
import hudson.model.AutoCompletionCandidates;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.util.CopyOnWriteList;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The main thing.
 *
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 */
@Extension
@Symbol("buildFailureAnalyzerCustom")
public class PluginImpl extends GlobalConfiguration {

    private static final Logger logger = Logger.getLogger(PluginImpl.class.getName());

    /**
     * Convenience constant for the 24x24 icon size. used for {@link #getImageUrl(String, String)}.
     * @deprecated plugin now uses icons.
     */
    @Deprecated
    public static final String DEFAULT_ICON_SIZE = "24x24";

    /**
     * Convenience constant for the default icon size. used for {@link #getDefaultIcon()}.
     */
    public static final String DEFAULT_ICON_NAME = "symbol-bulb-outline plugin-ionicons-api";

    /**
     * Default number of concurrent scan threads.
     */
    public static final int DEFAULT_NR_OF_SCAN_THREADS = 3;

    /**
     * Default max size of log to be scanned ('0' disables check).
     */
    public static final int DEFAULT_MAX_LOG_SIZE = 0;

    /**
     * Default per-pattern timeout for scanning a whole file (seconds).
     * Raised from the upstream 10s so big console logs don't abort early.
     */
    public static final int DEFAULT_SCAN_TIMEOUT_FILE_SECONDS = 120;

    /**
     * Default timeout for matching a single line against a pattern (seconds).
     */
    public static final int DEFAULT_SCAN_TIMEOUT_LINE_SECONDS = 10;

    /**
     * Default timeout for scanning a single multi-line block (seconds).
     */
    public static final int DEFAULT_SCAN_TIMEOUT_BLOCK_SECONDS = 30;

    /**
     * Default maximum number of megabytes of a single failed pipeline step's log to include in the narrowed
     * scan log. Long gradle/maven step logs in heavy multi-module projects can exceed 30 MB; BFA patterns
     * almost always match the build summary at the very end of the log, so taking only the tail is enough
     * and avoids hitting the per-pattern scan timeout. Two megabytes covers thousands of lines of summary.
     */
    public static final int DEFAULT_MAX_STEP_LOG_SIZE_MB = 2;

    /**
     * Default maximum number of failed pipeline atom-step nodes whose logs are included in the narrowed
     * scan input. On heavy parallel builds (e.g. dozens of serenity test batches sharing one infra failure)
     * findFailedNodes can return 50+ atoms with the same root-cause text; including all of them inflates the
     * narrow log to hundreds of MB and pushes the scan past its time budget. Ten atoms is more than enough
     * for BFA pattern matching — the same indication regex will fire on any of them — while keeping the
     * worst-case narrow log size bounded to {@code maxFailedSteps * maxStepLogSizeMb} MB.
     */
    public static final int DEFAULT_MAX_FAILED_STEPS = 10;

    private static final int BYTES_IN_MEGABYTE = 1024 * 1024;

    /**
     * Default slack channel to use.
     */
    public static final String DEFAULT_SLACK_CHANNEL =  "";

    /**
     * Default value for which failure categories to notify slack.
     */
    public static final String DEFAULT_SLACK_FAILURE_CATEGORIES = "ALL";

    /**
     * The permission group for all permissions related to this plugin.
     */
    public static final PermissionGroup PERMISSION_GROUP =
            new PermissionGroup(PluginImpl.class, Messages._PermissionGroup_Title());

    /**
     * Permission to update the causes. E.e. Access {@link CauseManagement}.
     */
    public static final Permission UPDATE_PERMISSION =
            new Permission(PERMISSION_GROUP, "UpdateCauses",
                    Messages._PermissionUpdate_Description(), Hudson.ADMINISTER);

    /**
     * Permission to view the causes. E.e. Access {@link CauseManagement}.
     */
    public static final Permission VIEW_PERMISSION =
            new Permission(PERMISSION_GROUP, "ViewCauses",
                    Messages._PermissionView_Description(), UPDATE_PERMISSION);

    /**
     * Permission to remove causes.
     */
    public static final Permission REMOVE_PERMISSION =
            new Permission(PERMISSION_GROUP, "RemoveCause",
                    Messages._PermissionRemove_Description(), Hudson.ADMINISTER);

    private static final String DEFAULT_NO_CAUSES_MESSAGE = "No problems were identified. "
            + "If you know why this problem occurred, please add a suitable Cause for it.";

    /**
     * Minimum allowed value for {@link #nrOfScanThreads}.
     */
    protected static final int MINIMUM_NR_OF_SCAN_THREADS = 1;

    private Boolean noCausesEnabled;
    private String noCausesMessage;

    private Boolean globalEnabled;
    private boolean doNotAnalyzeAbortedJob;

    private Boolean gerritTriggerEnabled;
    private Boolean slackNotifEnabled;
    private String slackChannelName;
    private String slackFailureCategories;

    private String fallbackCategoriesAsString;
    private transient List<String> fallbackCategories;

    private transient CopyOnWriteList<FailureCause> causes;

    private KnowledgeBase knowledgeBase;

    private int nrOfScanThreads;
    private int maxLogSize;
    private int maxStepLogSizeMb;
    private int maxFailedSteps;
    private int scanTimeoutFileSeconds;
    private int scanTimeoutLineSeconds;
    private int scanTimeoutBlockSeconds;
    private Boolean pipelineStepBasedScanEnabled;

    private Boolean graphsEnabled;

    private Boolean testResultParsingEnabled;
    private String testResultCategories;

    private Boolean metricSquashingEnabled;

    /**
     * ScanOnDemandVariable instance.
     */
    private ScanOnDemandVariables sodVariables = new ScanOnDemandVariables();

    /**
     * Default constructor.
     */
    @DataBoundConstructor
    public PluginImpl() {
        load();
    }

    protected Object readResolve() {
        if (sodVariables == null) {
            this.sodVariables = new ScanOnDemandVariables();
        }

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public XmlFile getConfigFile() {
        return new XmlFile(
                Jenkins.XSTREAM,
                new File(Jenkins.getInstance().getRootDir(), "build-failure-analyzer-custom.xml")
        );
    }

    /**
     * Starts the knowledge base.
     */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public void start() {
        logger.finer("[BFA] Starting...");
        if (noCausesMessage == null) {
            noCausesMessage = DEFAULT_NO_CAUSES_MESSAGE;
        }
        if (testResultCategories == null) {
            testResultCategories = "";
        }
        if (nrOfScanThreads < 1) {
            nrOfScanThreads = DEFAULT_NR_OF_SCAN_THREADS;
        }

        if (knowledgeBase == null) {
            if (causes == null) {
                knowledgeBase = new LocalFileKnowledgeBase();
            } else {
                //Migrate old data.
                knowledgeBase = new LocalFileKnowledgeBase(causes);
                //No reason to keep it in memory right?
                causes = null;
            }
        }

        try {
            knowledgeBase.start();
            logger.fine("[BFA] Started!");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not initialize the knowledge base: ", e);
        }
    }

    /**
     * Run on Jenkins shutdown.
     */
    @Terminator
    public void stop() {
        ScanOnDemandQueue.shutdown();
        knowledgeBase.stop();
    }

    /**
     * The URL path under which Jenkins serves this plugin's static resources.
     * Equals {@code /plugin/<artifactId>}.
     */
    public static final String PLUGIN_URL = "/plugin/build-failure-analyzer-custom";

    /**
     * Returns the base relative URI for static resources packaged in webapp.
     *
     * @return the base URI.
     */
    public static String getStaticResourcesBase() {
        return PLUGIN_URL;
    }

    /**
     * Getter sodVariable.
     *
     * @return the message.
     */
    public ScanOnDemandVariables getSodVariables() {
        return sodVariables;
    }

    /**
     * Returns the base relative URI for static images packaged in webapp.
     *
     * @return the images directory.
     *
     * @see #getStaticResourcesBase()
     * @deprecated plugin now uses icons.
     */
    @Deprecated
    public static String getStaticImagesBase() {
        return getStaticResourcesBase() + "/images";
    }

    /**
     * Provides a Jenkins relative url to a plugin internal image.
     *
     * @param size the size of the image (the sub directory of images).
     * @param name the name of the image file.
     * @return a URL to the image.
     * @deprecated plugin now uses icons.
     */
    @Deprecated
    public static String getImageUrl(String size, String name) {
        return "";
    }

    /**
     * Get the full url to an image, including rootUrl and context path.
     *
     * @param size the size of the image (the sub directory of images).
     * @param name the name of the image file.
     * @return a URL to the image.
     * @deprecated plugin now uses icons.
     */
    @Deprecated
    public static String getFullImageUrl(String size, String name) {
        return "";
    }

    /**
     * Provides a Jenkins relative url to a plugin internal image of {@link #DEFAULT_ICON_SIZE} size.
     *
     * @param name the name of the image.
     * @return a URL to the image.
     *
     * @see #getImageUrl(String, String)
     * @deprecated plugin now uses icons.
     */
    @Deprecated
    public static String getImageUrl(String name) {
        return "";
    }

    /**
     *
     * Returns the default icon to use for this plugin.
     *
     * @return the default icon.
     */
    public static String getDefaultIcon() {
        return DEFAULT_ICON_NAME;
    }

    /**
     * Returns the singleton instance.
     *
     * @return the one.
     */
    @NonNull
    public static PluginImpl getInstance() {
        return ExtensionList.lookup(PluginImpl.class).get(0);
    }

    /**
     * Getter for the no causes message.
     *
     * @return the message.
     */
    public String getNoCausesMessage() {
        return noCausesMessage;
    }

    /**
     * Whether to display in the build page when no causes are identified.
     *
     * @return true if on.
     */
    public boolean isNoCausesEnabled() {
        if (noCausesEnabled == null) {
            return true;
        } else {
            return noCausesEnabled;
        }
    }

    /**
     * Sets whether the "no indications found" message should be shown in the job page when no causes are found.
     * Default value is true.
     *
     * @param noCausesEnabled on or off.
     */
    @DataBoundSetter
    public void setNoCausesEnabled(boolean noCausesEnabled) {
        this.noCausesEnabled = noCausesEnabled;
    }

    /**
     * If this feature is enabled or not. When on all unsuccessful builds will be scanned. None when off.
     *
     * @return true if on.
     */
    public boolean isGlobalEnabled() {
        if (globalEnabled == null) {
            return true;
        } else {
            return globalEnabled;
        }
    }

    /**
     * If this feature is enabled or not. When on all aborted builds will be ignored.
     *
     * @return true if on.
     */
    public boolean isDoNotAnalyzeAbortedJob() {
        return doNotAnalyzeAbortedJob;
    }

    /**
     * If graphs are enabled or not. Links to graphs and graphs will not be displayed when disabled.
     * It can be enabled only if the knowledgeBase has support for it.
     * @return True if enabled.
     */
    public boolean isGraphsEnabled() {
        if (graphsEnabled == null || knowledgeBase == null) {
            return false;
        } else {
            return knowledgeBase.isEnableStatistics() && graphsEnabled;
        }
    }

    /**
     * Sets if graphs are enabled.
     * Default value is false.
     *
     * @param graphsEnabled the graph flag
     */
    @DataBoundSetter
    public void setGraphsEnabled(boolean graphsEnabled) {
        this.graphsEnabled = graphsEnabled;
    }

    /**
     * Sets the no causes message.
     * @param noCausesMessage the no causes message
     */
    @DataBoundSetter
    public void setNoCausesMessage(String noCausesMessage) {
        this.noCausesMessage = noCausesMessage;
    }

    /**
     * Sets the knowledge base.
     * @param knowledgeBase the knowledge base
     */
    @DataBoundSetter
    public void setKnowledgeBase(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * Sets the scan on demand variables.
     * @param sodVariables the variables
     */
    @DataBoundSetter
    public void setSodVariables(ScanOnDemandVariables sodVariables) {
        this.sodVariables = sodVariables;
    }

    /**
     * Sets the categories to be considered as generic. Causes with generic categories will only be found if
     * there are no other, non-generic causes.
     * @param categories The space separated list of generic categories
     */
    @DataBoundSetter
    public void setFallbackCategoriesAsString(String categories) {
        this.fallbackCategoriesAsString = categories;
        if (categories == null) {
            fallbackCategories = Collections.emptyList();
        } else {
            fallbackCategories = Arrays.asList(Util.tokenize(categories));
        }
    }

    /**
     * Fallback categories.
     * @return Fallback categories
     */
    public String getFallbackCategoriesAsString() {
        return Util.join(getFallbackCategories(), " ");
    }

    /**
     * If failed test cases should be represented as failure causes.
     *
     * @return True if enabled.
     */
    public boolean isTestResultParsingEnabled() {
        if (testResultParsingEnabled == null) {
            return false;
        } else {
            return testResultParsingEnabled;
        }
    }

    /**
     * Get categories to be assigned to failure causes representing failed test cases.
     *
     * @return the categories.
     */
    public String getTestResultCategories() {
        return testResultCategories;
    }

    /**
     * Get the categories that are considered generic.
     * @return a list of generic categories, never null.
     */
    public List<String> getFallbackCategories() {
        if (fallbackCategories == null) {
            if (fallbackCategoriesAsString == null) {
                fallbackCategories = Collections.emptyList();
            } else {
                fallbackCategories = Arrays.asList(Util.tokenize(fallbackCategoriesAsString));
            }
        }
        return fallbackCategories;
    }

    /**
     * If metrics should be squashed to only unique categories per build.
     *
     * @return True if enabled.
     */
    public boolean isMetricSquashingEnabled() {
        if (metricSquashingEnabled == null) {
            return false;
        } else {
            return metricSquashingEnabled;
        }
    }

    /**
     * Sets if this feature is enabled or not. When on all aborted builds will be ignored.
     *
     * @param doNotAnalyzeAbortedJob on or off.
     */
    @DataBoundSetter
    public void setDoNotAnalyzeAbortedJob(boolean doNotAnalyzeAbortedJob) {
        this.doNotAnalyzeAbortedJob = doNotAnalyzeAbortedJob;
    }

    /**
     * Sets if this feature is enabled or not. When on all unsuccessful builds will be scanned. None when off.
     * Default value is true.
     *
     * @param globalEnabled on or off.
     */
    @DataBoundSetter
    public void setGlobalEnabled(boolean globalEnabled) {
        this.globalEnabled = globalEnabled;
    }

    /**
     * Sets if failed test cases should be represented as failure causes or not.
     * Default value is false.
     *
     * @param testResultParsingEnabled on or off.
     */
    @DataBoundSetter
    public void setTestResultParsingEnabled(boolean testResultParsingEnabled) {
        this.testResultParsingEnabled = testResultParsingEnabled;
    }

    /**
     * Set categories to be assigned to failure causes representing failed test cases.
     *
     * @param testResultCategories Space-separated string with categories
     */
    @DataBoundSetter
    public void setTestResultCategories(String testResultCategories) {
        this.testResultCategories = testResultCategories;
    }

    /**
     * Sets if metrics should be squashed to only unique categories per build.
     * Default value is false.
     *
     * @param metricSquashingEnabled on or off.
     */
    @DataBoundSetter
    public void setMetricSquashingEnabled(boolean metricSquashingEnabled) {
        this.metricSquashingEnabled = metricSquashingEnabled;
    }

    /**
     * Send notifications to Gerrit-Trigger-plugin.
     *
     * @return true if on.
     */
    public boolean isGerritTriggerEnabled() {
        if (gerritTriggerEnabled == null) {
            return true;
        } else {
            return gerritTriggerEnabled;
        }
    }

    /**
     * Send notifications to Slack.
     *
     * @return true if on.
     */
    public boolean isSlackNotifEnabled() {
        if (slackNotifEnabled == null) {
            return false;
        } else {
            return slackNotifEnabled;
        }
    }

    /**
     * Get configured slack channel.
     *
     * @return String - Slack Channel.
     */
    public String getSlackChannelName() {
        if (slackChannelName == null) {
            return DEFAULT_SLACK_CHANNEL;
        } else {
            return slackChannelName;
        }
    }

    /**
     * Get configured slack failure cause categories.
     *
     * @return String - Space separated list of failure cause categories.
     */
    public String getSlackFailureCategories() {
        if (slackFailureCategories == null) {
            return DEFAULT_SLACK_FAILURE_CATEGORIES;
        } else {
            return slackFailureCategories;
        }
    }

    /**
     * Sets if this feature is enabled or not. When on, cause descriptions will be forwarded to Gerrit-Trigger-Plugin.
     * Default value is true.
     *
     * @param gerritTriggerEnabled on or off.
     */
    @DataBoundSetter
    public void setGerritTriggerEnabled(boolean gerritTriggerEnabled) {
        this.gerritTriggerEnabled = gerritTriggerEnabled;
    }

    /**
     * Sets if this feature is enabled or not. When on, selected failures will be sent to Slack channel.
     *
     * @param slackNotifEnabled on or off. null == off.
     */
    @DataBoundSetter
    public void setSlackNotifEnabled(boolean slackNotifEnabled) {
        this.slackNotifEnabled = slackNotifEnabled;
    }

    /**
     * Set configured slack channel.
     *
     * @param slackChannelName null = DEFAULT_SLACK_CHANNEL
     */
    @DataBoundSetter
    public void setSlackChannelName(String slackChannelName) {
        this.slackChannelName = slackChannelName;
    }

    /**
     * Set configured slack failure cause categories.
     *
     * @param slackFailureCategories - Space seperated list of failure cause categories.
     */
    @DataBoundSetter
    public void setSlackFailureCategories(String slackFailureCategories) {
        this.slackFailureCategories = slackFailureCategories;
    }

    /**
     * The number of threads to have in the pool for each build. Used by the {@link BuildFailureScanner}.
     * Will return nothing less than {@link #MINIMUM_NR_OF_SCAN_THREADS}.
     *
     * @return the number of scan threads.
     */
    public int getNrOfScanThreads() {
        if (nrOfScanThreads < MINIMUM_NR_OF_SCAN_THREADS) {
            nrOfScanThreads = DEFAULT_NR_OF_SCAN_THREADS;
        }
        return nrOfScanThreads;
    }

    /**
     * The number of threads to have in the pool for each build. Used by the {@link BuildFailureScanner}.
     * Will throw an {@link IllegalArgumentException} if the parameter is less than {@link #MINIMUM_NR_OF_SCAN_THREADS}.
     *
     * @param nrOfScanThreads the number of scan threads.
     */
    @DataBoundSetter
    public void setNrOfScanThreads(int nrOfScanThreads) {
        if (nrOfScanThreads < MINIMUM_NR_OF_SCAN_THREADS) {
            throw new IllegalArgumentException("Minimum nrOfScanThreads is " + MINIMUM_NR_OF_SCAN_THREADS);
        }
        this.nrOfScanThreads = nrOfScanThreads;
    }

    /**
     * Set the maximum log size that should be scanned.
     *
     * @param maxLogSize value
     */
    @DataBoundSetter
    public void setMaxLogSize(int maxLogSize) {
        this.maxLogSize = maxLogSize;
    }

    /**
     * Returns the maximum log size that should be scanned.
     *
     * @return value
     */
    public int getMaxLogSize() {
        if (maxLogSize < 0) {
            return DEFAULT_MAX_LOG_SIZE;
        }

        return maxLogSize;
    }

    /**
     * Maximum number of megabytes of a single failed pipeline step's log that should be included in the
     * narrowed scan log produced for BFA. Only the tail of that size is kept. If &lt;= 0, the default
     * {@link #DEFAULT_MAX_STEP_LOG_SIZE_MB} applies.
     *
     * @return value in megabytes.
     */
    public int getMaxStepLogSizeMb() {
        if (maxStepLogSizeMb <= 0) {
            return DEFAULT_MAX_STEP_LOG_SIZE_MB;
        }
        return maxStepLogSizeMb;
    }

    /**
     * @param maxStepLogSizeMb per-step log tail cap in megabytes (&lt;= 0 = default).
     */
    @DataBoundSetter
    public void setMaxStepLogSizeMb(int maxStepLogSizeMb) {
        this.maxStepLogSizeMb = maxStepLogSizeMb;
    }

    /**
     * Maximum number of failed pipeline atom-step nodes whose logs are included in the narrowed scan input.
     * Failed atoms are sorted by their {@link org.jenkinsci.plugins.workflow.graph.FlowNode#getId()}
     * (roughly time-of-creation order) and only the first N are kept; remaining ones are skipped with a
     * visible marker on the build's "Failure Scan Log" page. If &lt;= 0, the default
     * {@link #DEFAULT_MAX_FAILED_STEPS} applies.
     *
     * @return maximum number of failed steps to include in the narrowed log.
     */
    public int getMaxFailedSteps() {
        if (maxFailedSteps <= 0) {
            return DEFAULT_MAX_FAILED_STEPS;
        }
        return maxFailedSteps;
    }

    /**
     * @param maxFailedSteps cap on failed-step count in the narrowed log (&lt;= 0 = default).
     */
    @DataBoundSetter
    public void setMaxFailedSteps(int maxFailedSteps) {
        this.maxFailedSteps = maxFailedSteps;
    }

    /**
     * Per-pattern timeout for scanning a whole log file, in seconds.
     * If &lt;= 0, the default {@link #DEFAULT_SCAN_TIMEOUT_FILE_SECONDS} applies.
     *
     * @return value in seconds.
     */
    public int getScanTimeoutFileSeconds() {
        if (scanTimeoutFileSeconds <= 0) {
            return DEFAULT_SCAN_TIMEOUT_FILE_SECONDS;
        }
        return scanTimeoutFileSeconds;
    }

    /**
     * @param scanTimeoutFileSeconds per-pattern file scan timeout in seconds (&lt;= 0 = default).
     */
    @DataBoundSetter
    public void setScanTimeoutFileSeconds(int scanTimeoutFileSeconds) {
        this.scanTimeoutFileSeconds = scanTimeoutFileSeconds;
    }

    /**
     * Timeout for matching a single line against a pattern, in seconds.
     * If &lt;= 0, the default {@link #DEFAULT_SCAN_TIMEOUT_LINE_SECONDS} applies.
     *
     * @return value in seconds.
     */
    public int getScanTimeoutLineSeconds() {
        if (scanTimeoutLineSeconds <= 0) {
            return DEFAULT_SCAN_TIMEOUT_LINE_SECONDS;
        }
        return scanTimeoutLineSeconds;
    }

    /**
     * @param scanTimeoutLineSeconds single-line scan timeout in seconds (&lt;= 0 = default).
     */
    @DataBoundSetter
    public void setScanTimeoutLineSeconds(int scanTimeoutLineSeconds) {
        this.scanTimeoutLineSeconds = scanTimeoutLineSeconds;
    }

    /**
     * Timeout for scanning a single multi-line block, in seconds.
     * If &lt;= 0, the default {@link #DEFAULT_SCAN_TIMEOUT_BLOCK_SECONDS} applies.
     *
     * @return value in seconds.
     */
    public int getScanTimeoutBlockSeconds() {
        if (scanTimeoutBlockSeconds <= 0) {
            return DEFAULT_SCAN_TIMEOUT_BLOCK_SECONDS;
        }
        return scanTimeoutBlockSeconds;
    }

    /**
     * @param scanTimeoutBlockSeconds multi-line block scan timeout in seconds (&lt;= 0 = default).
     */
    @DataBoundSetter
    public void setScanTimeoutBlockSeconds(int scanTimeoutBlockSeconds) {
        this.scanTimeoutBlockSeconds = scanTimeoutBlockSeconds;
    }

    /**
     * When enabled, BFA scans only the logs of failed Pipeline steps instead of the
     * entire console log. Falls back to the full log if the build is not a Pipeline,
     * if no failed step is found, or if the Pipeline plugin is not installed.
     * Defaults to {@code true}.
     *
     * @return whether Pipeline step-based scanning is enabled.
     */
    public boolean isPipelineStepBasedScanEnabled() {
        return pipelineStepBasedScanEnabled == null || pipelineStepBasedScanEnabled;
    }

    /**
     * @param pipelineStepBasedScanEnabled whether Pipeline step-based scanning is enabled.
     */
    @DataBoundSetter
    public void setPipelineStepBasedScanEnabled(boolean pipelineStepBasedScanEnabled) {
        this.pipelineStepBasedScanEnabled = pipelineStepBasedScanEnabled;
    }

    /**
     * Checks if the build with certain result should be analyzed or not.
     *
     * @param result the result
     * @return true if it should be analyzed.
     */
    public static boolean needToAnalyze(Result result)  {
        if (getInstance().isDoNotAnalyzeAbortedJob()) {
            return result != Result.SUCCESS && result != Result.ABORTED;
        }   else {
            return result != Result.SUCCESS;
        }
    }

    /**
     * Checks if the specified build should be scanned or not.
     *
     * @param build the build
     * @return true if it should be scanned.
     * @see #shouldScan(Job)
     */
    public static boolean shouldScan(Run build) {
        return shouldScan(build.getParent());
    }

    /**
     * Checks that log size is in limits.
     *
     * @param build the build
     * @return true if size is in limit.
     */
    public static boolean isSizeInLimit(Run build) {
        return getInstance().getMaxLogSize() == 0
                || getInstance().getMaxLogSize() > (build.getLogText().length() / BYTES_IN_MEGABYTE);
    }

    /**
     * Checks if the specified project should be scanned or not. Determined by {@link #isGlobalEnabled()} and if the
     * project has {@link com.generationp.jenkins.plugins.bfa.model.ScannerJobProperty#isDoNotScan()}.
     *
     * @param project the project
     * @return true if it should be scanned.
     */
    public static boolean shouldScan(Job project) {
        if (getInstance().isGlobalEnabled()) {
            ScannerJobProperty property = (ScannerJobProperty)project.getProperty(ScannerJobProperty.class);
            if (property != null) {
                return !property.isDoNotScan();
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    /**
     * The knowledge base containing all causes.
     *
     * @return all the base.
     */
    public KnowledgeBase getKnowledgeBase() {
        return knowledgeBase;
    }

    /**
     * Convenience method to reach the list from jelly.
     *
     * @return the list of registered KnowledgeBaseDescriptors
     */
    public ExtensionList<KnowledgeBase.KnowledgeBaseDescriptor> getKnowledgeBaseDescriptors() {
        return KnowledgeBase.KnowledgeBaseDescriptor.all();
    }

    /**
     * Gets the KnowledgeBaseDescriptor that matches the name descString.
     *
     * @param descString either name of a KnowledgeBaseDescriptor or the fully qualified name.
     * @return The matching KnowledgeBaseDescriptor or null if none is found.
     */
    public KnowledgeBase.KnowledgeBaseDescriptor getKnowledgeBaseDescriptor(String descString) {
        for (KnowledgeBase.KnowledgeBaseDescriptor desc : getKnowledgeBaseDescriptors()) {
            if (desc.getClass().toString().contains(descString)) {
                return desc;
            }
        }
        return null;
    }


    @Override
    public boolean configure(StaplerRequest2 req, JSONObject o) {
        KnowledgeBase existingKb = knowledgeBase;
        req.bindJSON(this, o);

        if (knowledgeBase != null && !existingKb.equals(knowledgeBase)) {
            try {
                knowledgeBase.start();
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Could not start new knowledge base, reverting ", e);
                // since the knowledgebase is already overwritten via req.bindJSON, we need to
                // restore the old if the new one can't be started
                knowledgeBase = existingKb;
                save();
                return true;
            }
            if (o.getBoolean("convertOldKb")) {
                try {
                    knowledgeBase.convertFrom(existingKb);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Could not convert knowledge base ", e);
                }
            }
            existingKb.stop();
        } else {
            // Since we now overwrite the existing knowledgebase immediately, we need to put it
            // back if it was equal to the old one, or if the new one is null.
            knowledgeBase = existingKb;
        }
        save();
        return true;
    }

    /**
     * Does the auto completion for categories, matching with any category already present in the knowledge base.
     *
     * @param prefix the input prefix.
     * @return the AutoCompletionCandidates.
     */
    public AutoCompletionCandidates getCategoryAutoCompletionCandidates(String prefix) {
        Jenkins.getInstance().checkPermission(UPDATE_PERMISSION);
        List<String> categories;
        try {
            categories = getKnowledgeBase().getCategories();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not get the categories for autocompletion", e);
            return null;
        }
        AutoCompletionCandidates candidates = new AutoCompletionCandidates();
        if (categories != null) {
            for (String category : categories) {
                if (category.toLowerCase().startsWith(prefix.toLowerCase())) {
                    candidates.add(category);
                }
            }
        }
        for (String category : getFallbackCategories()) {
            if (category.toLowerCase().startsWith(prefix.toLowerCase()) && !candidates.getValues().contains(category)) {
                candidates.add(category);
            }
        }
        return candidates;
    }

    /**
     * Does the auto completion for categories, matching with any category already present in the knowledge base.
     *
     * @param value the input value.
     * @return the AutoCompletionCandidates.
     */
    public AutoCompletionCandidates doAutoCompleteFallbackCategoriesAsString(@QueryParameter String value) {
        return getCategoryAutoCompletionCandidates(value);
    }


}
