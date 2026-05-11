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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.generationp.jenkins.plugins.bfa.model.FailureCause;
import com.generationp.jenkins.plugins.bfa.model.indication.BuildLogIndication;
import com.generationp.jenkins.plugins.bfa.model.indication.Indication;
import com.generationp.jenkins.plugins.bfa.model.indication.MultilineBuildLogIndication;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Failure;
import hudson.model.Hudson;
import hudson.model.ModelObject;
import hudson.model.RootAction;
import hudson.security.Permission;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;
import java.io.IOException;

/**
 * Page for managing the failure causes.
 *
 * @author Robert Sandell &lt;robert.sandell@sonyericsson.com&gt;
 */
@Extension
public class CauseManagement implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(CauseManagement.class.getName());

    /**
     * Where in the Jenkins name space this action will be.
     *
     * @see #getUrlName()
     */
    public static final String URL_NAME = "failure-cause-management-custom";
    /**
     * The reserved id for getting a new {@link FailureCause} from {@link #getDynamic(String,
     * org.kohsuke.stapler.StaplerRequest2, org.kohsuke.stapler.StaplerResponse2)}.
     */
    public static final String NEW_CAUSE_DYNAMIC_ID = "new";
    /**
     * The pre-filled name that a new cause gets.
     */
    public static final String NEW_CAUSE_NAME = "New...";
    /**
     * The pre-filled description that a new cause gets.
     */
    public static final String NEW_CAUSE_DESCRIPTION = "Description...";
    /**
     * The request attribute key where error messages are added.
     */
    public static final String REQUEST_CAUSE_MANAGEMENT_ERROR = "CauseManagementError-custom";

    /**
     * Session key for the last removed {@link FailureCause} by the user. Will be removed by the index page when it
     * displays it.
     */
    public static final String SESSION_REMOVED_FAILURE_CAUSE = "removed-failureCause-custom";

    /**
     * Title for the page displaying the graphs.
     */
    public static final String GRAPH_PAGE_TITLE = "Global statistics";

    /**
     * Title for graphs with failure causes.
     */
    private static final String GRAPH_TITLE_CAUSES = "Failure causes for all nodes";

    /**
     * Title for graphs with categories.
     */
    private static final String GRAPH_TITLE_CATEGORIES = "Failures causes for all nodes grouped by categories";

    private static final String GRAPH_TITLE_UNKNOWN_PERCENTAGE = "Unknown failure causes";
    private static final String OWNER_URL = "/";
    @Override
    public String getIconFileName() {
        if (Hudson.getInstance().hasPermission(PluginImpl.UPDATE_PERMISSION)
                || Hudson.getInstance().hasPermission(PluginImpl.VIEW_PERMISSION)) {
            return PluginImpl.getDefaultIcon();
        } else {
            return null;
        }
    }

    @Override
    public String getDisplayName() {
        if (Hudson.getInstance().hasPermission(PluginImpl.UPDATE_PERMISSION)) {
            return Messages.CauseManagement_DisplayName();
        } else if (Hudson.getInstance().hasPermission(PluginImpl.VIEW_PERMISSION)) {
            return Messages.CauseList_DisplayName();
        } else {
            return null;
        }
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    /**
     * Convenience method for calling {@link PluginImpl#getImageUrl(String, String)} from jelly.
     *
     * @param size the size
     * @param name the name
     * @return the url.
     *
     * @see PluginImpl#getImageUrl(String, String)
     * @deprecated plugin now uses icons.
     */
    @Deprecated
    public String getImageUrl(String size, String name) {
        return PluginImpl.getImageUrl(size, name);
    }

    /**
     * Convenience method for {@link com.generationp.jenkins.plugins.bfa.db.KnowledgeBase#getShallowCauses()}.
     *
     * @return the collection of causes.
     *
     * @throws Exception if communication fails.
     */
    public Iterable<FailureCause> getShallowCauses() throws Exception {
        Iterable<FailureCause> returnValue = null;
        try {
            returnValue = PluginImpl.getInstance().getKnowledgeBase().getShallowCauses();
        } catch (Exception e) {
            String message = "Could not fetch causes: " + e.getMessage();

            setErrorMessage(message);
            LOGGER.log(Level.SEVERE, message, e);
        }
        return returnValue;
    }

    /**
     * Sets an error message as an attribute to the current request.
     *
     * @param message the message to set.
     * @see #getErrorMessage(org.kohsuke.stapler.StaplerRequest2)
     * @see #REQUEST_CAUSE_MANAGEMENT_ERROR
     */
    private void setErrorMessage(String message) {
        Stapler.getCurrentRequest2().setAttribute(REQUEST_CAUSE_MANAGEMENT_ERROR, message);
    }

    /**
     * Convenience method for jelly.
     *
     * @param request the request where the message might be.
     * @return true if there is an error message to display.
     */
    public boolean isError(StaplerRequest2 request) {
        return Util.fixEmpty((String)request.getAttribute(REQUEST_CAUSE_MANAGEMENT_ERROR)) != null;
    }

    /**
     * Used for getting the error message to show on the page.
     *
     * @param request the request where the message might be.
     * @return the error message to show.
     */
    public String getErrorMessage(StaplerRequest2 request) {
        return (String)request.getAttribute(REQUEST_CAUSE_MANAGEMENT_ERROR);
    }

    /**
     * Dynamic Stapler URL binding. Provides the ability to navigate to a cause via for example:
     * <code>/jenkins/failure-cause-management/abf123</code>
     *
     * @param id       the id of the cause of "new" to create a new cause.
     * @param request  the request
     * @param response the response
     * @return the cause if found or null.
     *
     * @throws Exception if communication with the knowledge base failed.
     */
    public FailureCause getDynamic(String id, StaplerRequest2 request, StaplerResponse2 response) throws Exception {
        if (NEW_CAUSE_DYNAMIC_ID.equalsIgnoreCase(id)) {
            return new FailureCause(NEW_CAUSE_NAME, NEW_CAUSE_DESCRIPTION);
        } else {
            return PluginImpl.getInstance().getKnowledgeBase().getCause(id);
        }
    }

    /**
     * Web call to remove a {@link FailureCause}. Does a permission check for {@link PluginImpl#REMOVE_PERMISSION}.
     *
     * @param id       the id of the cause to remove.
     * @param request  the stapler request.
     * @param response the stapler response.
     * @throws IOException if so during redirect.
     */
    @POST
    public void doRemoveConfirm(@QueryParameter String id, StaplerRequest2 request, StaplerResponse2 response)
            throws IOException {
        Jenkins.getInstance().checkPermission(PluginImpl.REMOVE_PERMISSION);
        id = Util.fixEmpty(id);
        if (id != null) {
            try {
                FailureCause cause = PluginImpl.getInstance().getKnowledgeBase().removeCause(id);
                if (cause != null) {
                    request.getSession(true).setAttribute(SESSION_REMOVED_FAILURE_CAUSE, cause);
                }
            } catch (Exception e) {
                //Should we use errorMessage here as well?
                throw (Failure)(new Failure(e.getMessage()).initCause(e));
            }
        }
        response.sendRedirect2("./");
    }

    /**
     * Indication type tag used in the import/export JSON format. Stable wire name independent
     * of the Java class FQN, so that JSON files survive class refactors.
     */
    private static final String INDICATION_TYPE_BUILD_LOG = "buildLog";
    /** @see #INDICATION_TYPE_BUILD_LOG */
    private static final String INDICATION_TYPE_MULTILINE = "multilineBuildLog";

    /**
     * Returns the import/export JSON for the supplied list of causes.
     *
     * <p>Format:
     * <pre>
     * [
     *   {
     *     "name":        "...",
     *     "description": "...",
     *     "comment":     "...",
     *     "categories":  ["A","B"],
     *     "indications": [
     *        {"type": "buildLog",          "pattern": ".*foo.*"},
     *        {"type": "multilineBuildLog", "pattern": ".*bar.*"}
     *     ]
     *   }, ...
     * ]
     * </pre>
     *
     * @param causes causes to serialize.
     * @return pretty-printed JSON.
     * @throws IOException on serialization failure.
     */
    static String causesToJson(Collection<FailureCause> causes) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>(causes.size());
        for (FailureCause cause : causes) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", cause.getName());
            entry.put("description", cause.getDescription());
            entry.put("comment", cause.getComment());
            List<String> categoryList;
            if (cause.getCategories() == null) {
                categoryList = new ArrayList<>();
            } else {
                categoryList = cause.getCategories();
            }
            entry.put("categories", categoryList);
            List<Map<String, String>> indOut = new ArrayList<>();
            if (cause.getIndications() != null) {
                for (Indication ind : cause.getIndications()) {
                    Map<String, String> indMap = new LinkedHashMap<>();
                    if (ind instanceof MultilineBuildLogIndication) {
                        indMap.put("type", INDICATION_TYPE_MULTILINE);
                    } else {
                        indMap.put("type", INDICATION_TYPE_BUILD_LOG);
                    }
                    indMap.put("pattern", ind.getUserProvidedExpression());
                    indOut.add(indMap);
                }
            }
            entry.put("indications", indOut);
            out.add(entry);
        }
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(out);
    }

    /**
     * Parses the import JSON. Accepts both a single object and an array of objects.
     *
     * @param json input JSON.
     * @return list of fully constructed FailureCauses (not yet saved).
     * @throws IOException on parse failure.
     */
    static List<FailureCause> jsonToCauses(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);
        List<JsonNode> entries = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(entries::add);
        } else if (root.isObject()) {
            entries.add(root);
        } else {
            throw new IOException("Expected JSON object or array, got: " + root.getNodeType());
        }
        List<FailureCause> result = new ArrayList<>(entries.size());
        for (JsonNode node : entries) {
            String name = textOrNull(node, "name");
            if (name == null || name.isEmpty()) {
                throw new IOException("Cause is missing the required 'name' field");
            }
            String description = textOr(node, "description", "");
            String comment = textOr(node, "comment", "");
            FailureCause cause = new FailureCause(name, description, comment);
            JsonNode cats = node.get("categories");
            if (cats != null && cats.isArray()) {
                List<String> categoryList = new ArrayList<>();
                cats.forEach(c -> categoryList.add(c.asText()));
                cause.setCategories(categoryList);
            }
            JsonNode inds = node.get("indications");
            if (inds == null || !inds.isArray() || inds.size() == 0) {
                throw new IOException("Cause '" + name + "' has no indications");
            }
            for (JsonNode ind : inds) {
                String type = textOr(ind, "type", INDICATION_TYPE_BUILD_LOG);
                String pattern = textOrNull(ind, "pattern");
                if (pattern == null || pattern.isEmpty()) {
                    throw new IOException("Cause '" + name + "' has an indication without 'pattern'");
                }
                if (INDICATION_TYPE_MULTILINE.equals(type)) {
                    cause.addIndication(new MultilineBuildLogIndication(pattern));
                } else if (INDICATION_TYPE_BUILD_LOG.equals(type)) {
                    cause.addIndication(new BuildLogIndication(pattern));
                } else {
                    throw new IOException("Cause '" + name + "' has unknown indication type '" + type + "'");
                }
            }
            result.add(cause);
        }
        return result;
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static String textOr(JsonNode parent, String field, String defaultValue) {
        String v = textOrNull(parent, field);
        if (v == null) {
            return defaultValue;
        }
        return v;
    }

    /**
     * Web call: download all causes as a JSON file.
     *
     * @param request the stapler request.
     * @param response the stapler response.
     * @throws Exception on knowledge base or IO failure.
     */
    public void doExportCauses(StaplerRequest2 request, StaplerResponse2 response) throws Exception {
        Jenkins.getInstance().checkPermission(PluginImpl.VIEW_PERMISSION);
        Collection<FailureCause> causes = PluginImpl.getInstance().getKnowledgeBase().getCauses();
        String json = causesToJson(causes);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String fileName = "bfa-causes-" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".json";
        response.setContentType("application/json; charset=utf-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /**
     * Web call: import causes from a JSON payload (file upload or pasted text).
     *
     * @param json the pasted JSON text (from textarea).
     * @param overwrite whether to overwrite causes with the same name.
     * @param request the stapler request.
     * @param response the stapler response.
     * @throws IOException on redirect.
     */
    @POST
    public void doImportCauses(@QueryParameter("json") String json,
                               @QueryParameter("overwrite") boolean overwrite,
                               StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        Jenkins.getInstance().checkPermission(PluginImpl.UPDATE_PERMISSION);
        try {
            String payload = Util.fixEmptyAndTrim(json);
            if (payload == null) {
                throw new IOException("No JSON content was supplied");
            }
            List<FailureCause> parsed = jsonToCauses(payload);
            // Index existing causes by name once (case-sensitive) for overwrite mode.
            Map<String, FailureCause> existingByName = new LinkedHashMap<>();
            if (overwrite) {
                for (FailureCause c : PluginImpl.getInstance().getKnowledgeBase().getCauses()) {
                    existingByName.put(c.getName(), c);
                }
            }
            int added = 0;
            int updated = 0;
            int skipped = 0;
            for (FailureCause cause : parsed) {
                FailureCause existing = existingByName.get(cause.getName());
                if (existing != null) {
                    if (overwrite) {
                        // Replace by name: drop the old record, insert the parsed one.
                        PluginImpl.getInstance().getKnowledgeBase().removeCause(existing.getId());
                        PluginImpl.getInstance().getKnowledgeBase().addCause(cause);
                        updated++;
                    } else {
                        skipped++;
                    }
                } else {
                    PluginImpl.getInstance().getKnowledgeBase().addCause(cause);
                    added++;
                }
            }
            request.getSession(true).setAttribute(SESSION_IMPORT_RESULT,
                    "Imported: " + added + " added, " + updated + " updated, " + skipped + " skipped");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to import causes from JSON", e);
            setErrorMessage("Import failed: " + e.getMessage());
        }
        response.sendRedirect2("./");
    }

    /**
     * Session attribute key for the result message of the last import.
     */
    public static final String SESSION_IMPORT_RESULT = "bfa-import-result-custom";

    /**
     * The "owner" of this Action. Default this would be {@link hudson.model.Hudson#getInstance()} but if the class is
     * included in some build or something we might want to be able to easier change the side panel for example.
     *
     * @return the holder of the beer.
     */
    public ModelObject getOwner() {
        return Hudson.getInstance();
    }

    /**
     * Where to redirect after the form has been saved, probably to the owner.
     *
     * @return the owner's URL or some place else to redirect the user after save.
     */
    protected String getOwnerUrl() {
        return OWNER_URL;
    }

    /**
     * Provides a list of all IndicationDescriptors. For Jelly convenience.
     *
     * @return a list of descriptors.
     *
     * @see com.generationp.jenkins.plugins.bfa.model.indication.Indication.IndicationDescriptor#getAll()
     */
    public ExtensionList<Indication.IndicationDescriptor> getIndicationDescriptors() {
        return Indication.IndicationDescriptor.getAll();
    }

    /**
     * The permission related to this action. For Jelly convenience.
     *
     * @return the permission.
     *
     * @see PluginImpl#UPDATE_PERMISSION
     */
    public Permission getPermission() {
        return PluginImpl.UPDATE_PERMISSION;
    }

    /**
     * The permission related to this action. For Jelly convenience.
     *
     * @return the permission.
     *
     * @see PluginImpl#UPDATE_PERMISSION
     */
    public Permission getRemovePermission() {
        return PluginImpl.REMOVE_PERMISSION;
    }

    /**
     * Checks if Jenkins is run from inside a HudsonTestCase. For some reason the buildQueue fails to render when run
     * under test but works fine when run with hpi:run. So the jelly file skips the inclusion of the sidepanel if we are
     * running under test to work around this problem. The check is done via looking at the class name of {@link
     * hudson.model.Hudson#getPluginManager()}.
     *
     * @return true if we are running under test.
     */
    public boolean isUnderTest() {
        return "org.jvnet.hudson.test.TestPluginManager".
                equals(Hudson.getInstance().getPluginManager().getClass().getName());
    }

    /**
     * Provides the singleton instance of this class that Jenkins has loaded. Throws an IllegalStateException if for
     * some reason the action can't be found.
     *
     * @return the instance.
     */
    public static CauseManagement getInstance() {
        for (Action action : Hudson.getInstance().getActions()) {
            if (action instanceof CauseManagement) {
                return (CauseManagement)action;
            }
        }
        throw new IllegalStateException("We seem to not have been initialized!");
    }
}
