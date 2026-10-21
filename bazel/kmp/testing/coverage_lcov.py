"""Turns the coverage reporter's JaCoCo XML into LCOV with repository paths, for coverage.sh.

JaCoCo XML names a source file by its package directory and file name only. One package lives in one module, so
the file is that path under one of the source roots; a name that two source sets of the module share (an expect's
platform files) goes to the first root that has it. A file under no root was generated at build time (the
`kotlin_constants` data), which has no repository path for Codecov to show, so it is left out.

Usage: coverage_lcov.py <xml-report> <lcov-file> <source root>...
"""

import os
import sys
import xml.etree.ElementTree as ElementTree


def main(xml_report, lcov_file, roots):
    records = []
    unmatched = []
    for package in ElementTree.parse(xml_report).getroot().iter("package"):
        for source in package.iter("sourcefile"):
            relative = package.get("name") + "/" + source.get("name")
            path = next((root + "/" + relative for root in roots if os.path.isfile(root + "/" + relative)), None)
            if path is None:
                unmatched.append(relative)
            else:
                records.append(_record(path, source.iter("line")))
    if unmatched:
        print("Left out, generated (no source file): " + ", ".join(sorted(unmatched)), file=sys.stderr)
    with open(lcov_file, "w", encoding="utf-8") as out:
        out.writelines(sorted(records))


def _record(path, lines):
    """One file's LCOV record: a line counts as run when any of its instructions ran."""
    rows = ["SF:" + path + "\n"]
    found = hit = branches = taken = 0
    for line in lines:
        number = line.get("nr")
        run = int(line.get("ci")) > 0
        covered_branches = int(line.get("cb"))
        missed_branches = int(line.get("mb"))
        for branch in range(covered_branches + missed_branches):
            rows.append("BRDA:%s,0,%d,%d\n" % (number, branch, 1 if branch < covered_branches else 0))
        rows.append("DA:%s,%d\n" % (number, 1 if run else 0))
        found += 1
        hit += run
        branches += covered_branches + missed_branches
        taken += covered_branches
    rows += ["BRF:%d\n" % branches, "BRH:%d\n" % taken, "LF:%d\n" % found, "LH:%d\n" % hit, "end_of_record\n"]
    return "".join(rows)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
