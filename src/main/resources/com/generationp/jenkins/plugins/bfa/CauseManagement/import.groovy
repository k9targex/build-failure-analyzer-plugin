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
package com.generationp.jenkins.plugins.bfa.CauseManagement
import com.generationp.jenkins.plugins.bfa.CauseManagement
import com.generationp.jenkins.plugins.bfa.PluginImpl
import jenkins.model.Jenkins

def f = namespace(lib.FormTagLib)
def l = namespace(lib.LayoutTagLib)

Jenkins.get().checkPermission(PluginImpl.UPDATE_PERMISSION)

l.layout(norefresh: true) {
    l.header(title: _("Import Failure Causes"))

    def management = CauseManagement.getInstance()

    l.side_panel() {
        if (!management.isUnderTest()) {
            include(management.getOwner(), "sidepanel.jelly")
        }
    }

    l.main_panel() {
        l.app_bar(title: _("Import Failure Causes from JSON"))

        if (management.isError(request2)) {
            div(class: "error", id: "errorMessage") {
                text(management.getErrorMessage(request2))
            }
        }

        div(style: "margin-top: 10px; margin-bottom: 10px;") {
            text(_("Paste a JSON array of cause objects (or a single cause object) below. " +
                    "Format: name, description, comment, categories[], indications[{type,pattern}]. " +
                    "Supported indication types: 'buildLog', 'multilineBuildLog'."))
        }

        form(method: "post", action: "importCauses") {
            div(style: "margin-bottom: 10px;") {
                textarea(
                    name: "json",
                    rows: "20",
                    style: "width: 100%; font-family: monospace; font-size: 12px;",
                    placeholder: '[\n  {\n    "name": "Compilation Error",\n    "description": "...",\n    "comment": "...",\n    "categories": ["DEVELOPER"],\n    "indications": [\n      {"type": "buildLog", "pattern": ".*Cannot find symbol.*"}\n    ]\n  }\n]'
                )
            }
            div(style: "margin-bottom: 15px;") {
                input(type: "checkbox", name: "overwrite", id: "bfa-import-overwrite")
                label(for: "bfa-import-overwrite",
                      style: "margin-left: 5px;",
                      _("Overwrite causes with the same name (otherwise: skip duplicates)"))
            }
            div {
                button(type: "submit", class: "jenkins-button jenkins-button--primary") {
                    text(_("Import"))
                }
                a(class: "jenkins-button", href: ".", style: "margin-left: 8px;") {
                    text(_("Cancel"))
                }
            }
        }
    }
}
