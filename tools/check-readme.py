#!/usr/bin/env python3
"""What README.md has to be true of, for the claims a renderer cannot check.

- `marketplace` - whether every JetBrains Marketplace reference in the README names the same plugin.

The README carries the Marketplace numeric plugin id in several places at once: two shields badges and
the links around them. The repository's own identifier is the `xmlId` in gradle.properties and plugin.xml,
and nothing connects the two, so a wrong number renders as a badge reading "plugin not found" and links to
somebody else's plugin with nothing going red.

This check does not know which number is right - answering that means asking the Marketplace, and a check
that talks to somebody else's server goes red on their outage and stops being read. It knows only that the
references have to agree with each other, which is what catches the half-finished edit: the case where one
occurrence was updated and the others were not.

All of it sits on plain functions over text, so that the rules can be exercised without a README to check.
The tests beside this file are what exercises them.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path


def repository_root() -> Path:
    """The repository being checked, which is not the same question as where this file lives.

    The same reasoning as in check-release.py: shared as a composite action, this script is checked out
    beside the action rather than beside the project it answers about, and a root taken from its own path
    would quietly name the wrong repository.
    """
    found = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    if found.returncode != 0:
        raise SystemExit("check-readme.py has to be run inside the repository it is checking")
    return Path(found.stdout.strip())


# Both patterns take more than they need and hand the sorting out to the code below, rather than trying to
# spell out every shape a Marketplace URL comes in. A pattern that has to anticipate them all is a pattern
# that silently passes the one it did not think of, and silently passing is the failure this file exists to
# prevent. The badge path carries the metric - `v` for version, `d` for downloads - which is why it is
# matched as "some segment" and not enumerated.
BADGE = re.compile(r"img\.shields\.io/jetbrains/plugin/[^/\s]+/([^\s)\"'<>]+)")
LISTING = re.compile(r"plugins\.jetbrains\.com/plugin/([^\s)\"'<>/]+)")

# What the id and the slug look like once the surrounding URL has been stripped: `34153-maven-lens`, or
# `34153.svg` in a badge, or a bare `34153`.
REFERENCE = re.compile(r"^(\d+)(?:-([a-z0-9-]+))?(?:\.svg)?$")


def marketplace_references(text: str) -> list[tuple[str, str | None, str]]:
    """Every Marketplace plugin reference in the text, as (id, slug or None, the matched text).

    The matched text is carried along so that a disagreement can name where it is, rather than only saying
    that one exists.
    """
    found = []
    for pattern in (BADGE, LISTING):
        for match in pattern.finditer(text):
            reference = match.group(1)
            parsed = REFERENCE.match(reference)
            if parsed:
                found.append((parsed.group(1), parsed.group(2), match.group(0)))
    return found


def marketplace_problems(text: str) -> list[str]:
    """Whether the Marketplace references in the text agree about which plugin they name."""
    references = marketplace_references(text)

    # A check that passes because it found nothing is worse than no check: it reports success over a README
    # whose badges were all renamed or deleted. The absence is the finding.
    if not references:
        return ["README.md names no JetBrains Marketplace plugin: the badges and the listing links are gone"]

    problems = []

    ids = {identifier for identifier, _, _ in references}
    if len(ids) > 1:
        problems.append(
            "README.md names more than one Marketplace plugin id - "
            + ", ".join(
                f"{identifier} in {where}"
                for identifier, where in sorted({(identifier, where) for identifier, _, where in references})
            )
        )

    slugs = {slug for _, slug, _ in references if slug is not None}
    if len(slugs) > 1:
        problems.append(
            "README.md spells the Marketplace plugin slug more than one way - "
            + ", ".join(sorted(slugs))
        )

    return problems


def marketplace_command(_arguments) -> list[str]:
    readme = repository_root() / "README.md"
    if not readme.is_file():
        return ["there is no README.md to check"]

    problems = marketplace_problems(readme.read_text(encoding="utf-8"))
    if not problems:
        references = marketplace_references(readme.read_text(encoding="utf-8"))
        print(f"Checked {len(references)} Marketplace reference(s), all naming plugin {references[0][0]}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)

    marketplace = commands.add_parser("marketplace", help="whether the README's Marketplace references agree")
    marketplace.set_defaults(run=marketplace_command)

    arguments = parser.parse_args()
    problems = arguments.run(arguments)

    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
