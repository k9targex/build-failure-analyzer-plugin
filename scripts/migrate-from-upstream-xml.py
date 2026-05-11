#!/usr/bin/env python3
"""
Convert an upstream build-failure-analyzer plugin XML config
($JENKINS_HOME/build-failure-analyzer.xml) into the JSON format consumed by
our forked plugin's "Import from JSON" feature.

Usage:
    ./migrate-from-upstream-xml.py /path/to/build-failure-analyzer.xml > causes.json

The resulting causes.json can be pasted into:
    Manage Jenkins -> Failure Cause Management (Custom) -> Import from JSON

Limitations:
- Recognizes BuildLogIndication and MultilineBuildLogIndication. Unknown
  indication classes are downgraded to buildLog with a warning on stderr.
- Statistics, modifications history and lastOccurred are intentionally dropped
  (the import API does not accept them; they will be regenerated as the new
  plugin sees failures).
"""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

INDICATION_TAG_TO_TYPE = {
    "BuildLogIndication": "buildLog",
    "MultilineBuildLogIndication": "multilineBuildLog",
}


def parse_categories(elem):
    if elem is None:
        return []
    return [s.text for s in elem.findall("string") if s.text]


def parse_indications(elem):
    if elem is None:
        return []
    result = []
    for child in elem:
        # XStream tags look like com.sonyericsson...indication.BuildLogIndication
        short = child.tag.split(".")[-1]
        ind_type = INDICATION_TAG_TO_TYPE.get(short)
        if ind_type is None:
            print(
                f"WARN: unknown indication type '{short}', treating as buildLog",
                file=sys.stderr,
            )
            ind_type = "buildLog"
        pattern = child.findtext("pattern", default="")
        result.append({"type": ind_type, "pattern": pattern})
    return result


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <build-failure-analyzer.xml>", file=sys.stderr)
        sys.exit(1)
    xml_path = Path(sys.argv[1])
    if not xml_path.is_file():
        print(f"ERROR: file not found: {xml_path}", file=sys.stderr)
        sys.exit(1)

    tree = ET.parse(xml_path)
    root = tree.getroot()

    # Causes live under the active KnowledgeBase. For LocalFileKnowledgeBase the
    # path is /PluginImpl/knowledgeBase/causes/FailureCause...
    causes_elem = root.find(".//causes")
    if causes_elem is None:
        print(
            "ERROR: <causes> element not found. Is this a LocalFileKnowledgeBase XML?",
            file=sys.stderr,
        )
        sys.exit(2)

    # The on-disk format is `<causes>` containing either:
    #   - direct <com.sonyericsson...FailureCause> children (collection serialization), or
    #   - <entry><string>id</string><com.sonyericsson...FailureCause>...</...></entry>
    #     (map serialization, used by current versions).
    cause_nodes = []
    for child in causes_elem:
        if child.tag == "entry":
            for sub in child:
                if sub.tag != "string":
                    cause_nodes.append(sub)
        else:
            cause_nodes.append(child)

    out = []
    for cause in cause_nodes:
        name = cause.findtext("name", default="")
        if name is None:
            name = ""
        name = name.strip()
        if not name:
            print("WARN: skipping cause with empty name", file=sys.stderr)
            continue
        out.append({
            "name": name,
            "description": cause.findtext("description", default="") or "",
            "comment": cause.findtext("comment", default="") or "",
            "categories": parse_categories(cause.find("categories")),
            "indications": parse_indications(cause.find("indications")),
        })

    json.dump(out, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
    print(f"\nConverted {len(out)} cause(s)", file=sys.stderr)


if __name__ == "__main__":
    main()
