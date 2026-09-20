#!/usr/bin/env python3
"""What a release has to be true of, checked before and after it is one.

- `version` - whether what `gradle.properties` says may be released next, given the tags that already exist.
- `next` - the version to be worked on once one is released.
- `changelog` - whether every section already released still reads the way it was released.
- `ancestry` - whether every released tag is still reachable, a rewrite being able to take one off the history.
- `prefix` - what release tags are called here, so that the workflows do not have to say it a second time.
- `channel` - the Marketplace channel a version goes to, read off its pre-release suffix.
- `set-version` - write the version gradle.properties names, which a release is what does.

All of it sits on plain functions over text, tags and booleans, so that the rules can be exercised without a
repository to release. The tests beside this file are what exercises them.
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

def repository_root() -> Path:
    """The repository being checked, which is not the same question as where this file lives.

    Shared between projects as a composite action, this script is checked out beside the action rather than
    beside the project it answers about, and a root taken from its own path would then name the wrong
    repository - quietly, because that one has a gradle.properties and a CHANGELOG.md of its own.
    """
    found = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    if found.returncode != 0:
        raise SystemExit("check-release.py has to be run inside the repository it is checking")
    return Path(found.stdout.strip())


REPO = repository_root()

# What this project releases: three numbers, optionally a pre-release suffix. The suffix is not decoration - it
# names the Marketplace channel the version is published to (see channel_of), so `0.2.0-beta.1` is offered only
# to whoever subscribed to `beta`, and it marks the GitHub release as a pre-release.
VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?$")

# The marker a version carries while it is being worked on. It is the one suffix that is not a channel: it says
# the version has not been released, so a release is precisely what it cannot be.
SNAPSHOT = "-SNAPSHOT"

# The one line of gradle.properties a release rewrites. Gradle allows space around the `=` and projects write
# it both ways, so the separator is captured and put back rather than chosen here: turning one spelling into
# the other would show up in the diff as a change nobody made.
VERSION_LINE = re.compile(r"^(version[ \t]*=[ \t]*)(.*)$", re.MULTILINE)

SECTION = re.compile(r"^## \[([^\]]+)\]", re.MULTILINE)
LINK_DEFINITION = re.compile(r"^\[[^\]]+\]:\s.*$", re.MULTILINE)


def precedence(version: str) -> tuple:
    """Orders versions the way SemVer does, so that `<` and `sorted` mean what the spec means.

    Two rules are easy to get wrong by comparing strings: a release outranks every pre-release of the same three
    numbers (`0.2.0` > `0.2.0-rc.1`), and a numeric identifier inside a suffix is compared as a number rather
    than as text (`beta.9` < `beta.10`).
    """
    major, minor, patch, suffix = VERSION.match(version).groups()
    if suffix is None:
        return (int(major), int(minor), int(patch), 1, ())

    identifiers = tuple(
        (0, int(part), "") if part.isdigit() else (1, 0, part) for part in suffix.split(".")
    )
    return (int(major), int(minor), int(patch), 0, identifiers)


def core(version: str) -> tuple[int, int, int]:
    major, minor, patch, _ = VERSION.match(version).groups()
    return (int(major), int(minor), int(patch))


def successors(released: tuple[int, int, int]) -> list[tuple[int, int, int]]:
    """The three cores that may follow one, which is what SemVer permits and no more: one part goes up by one and
    everything to its right goes to zero."""
    major, minor, patch = released
    return [(major, minor, patch + 1), (major, minor + 1, 0), (major + 1, 0, 0)]


def as_text(version: tuple[int, int, int]) -> str:
    return "{}.{}.{}".format(*version)


def check_version(candidate: str, tags: list[str]) -> list[str]:
    """Whether `candidate` may be released, given every version already tagged."""

    if not VERSION.match(candidate):
        return [f"'{candidate}' is not a version this project releases"]

    released = sorted((tag for tag in tags if VERSION.match(tag)), key=precedence)
    if not released:
        return []  # Nothing to be consistent with; the first release may name itself anything valid.

    problems = []
    finals = [tag for tag in released if VERSION.match(tag).group(4) is None]
    is_final = VERSION.match(candidate).group(4) is None

    # The base invariant, and the one the rest rests on. An IDE offers an update by comparing versions, so a
    # release that does not outrank the last one is a release nobody is offered. "The last one" is the last one
    # offered to the same people: a final release goes to everyone and has to outrank the last final release,
    # not the pre-releases, which only their channel's subscribers ever see - so a stable hotfix 0.2.1 may go
    # out while 0.3.0-beta.1 is open, and nobody on either channel is offered a downgrade. A pre-release goes
    # to subscribers, who see everything, so it has to outrank the highest tag of all. The other side of that
    # coin: while a train is open, a hotfix can go out but not be tried on a channel first - 0.2.1-rc.1 sorts
    # below 0.3.0-beta.1 and is refused, where 0.2.1 itself passes.
    against = finals[-1] if is_final and finals else released[-1]
    if precedence(candidate) <= precedence(against):
        problems.append(f"{candidate} does not come after {against}, which is released already")

    # The step is measured against the last *final* release rather than against the highest tag, so that a
    # pre-release train - 0.2.0-rc.1, 0.2.0-rc.2, 0.2.0 - stays inside one permitted core instead of every step
    # having to advance it. Going backwards inside that train is what the check above is for. It also means an
    # open train cannot be abandoned for a higher core: 0.4.0 does not follow 0.2.0, whatever 0.3.0-beta.1 says.
    if finals:
        allowed = successors(core(finals[-1]))
        if core(candidate) not in allowed:
            names = ", ".join(as_text(step) for step in allowed)
            problems.append(f"{candidate} does not follow {finals[-1]}: the next version is one of {names}")

    return problems


def next_worked_on(released: str) -> str:
    """The version to be worked on once `released` is out.

    A pre-release does not advance anything: `0.2.0-rc.1` was a step towards `0.2.0`, so work goes on heading
    for it. A final release is followed by the smallest claim that can be made about what comes next, a patch;
    whoever lands a feature raises it to a minor in the same pull request, where a reviewer can see the line.
    """
    major, minor, patch, suffix = VERSION.match(released).groups()
    if suffix is not None:
        return f"{major}.{minor}.{patch}{SNAPSHOT}"
    return f"{major}.{minor}.{int(patch) + 1}{SNAPSHOT}"


def channel_of(version: str) -> str:
    """The Marketplace channel a version is published to: the first identifier of its pre-release suffix, or
    `default` for a final release. `0.3.0-beta.1` goes to `beta`, where only whoever subscribed to that channel
    is offered it; `0.3.0` goes to everyone.

    This is what the workflows mark the GitHub release with, upload to the Marketplace under, and ask the
    Marketplace for afterwards. The same rule, in Kotlin, sets `publishing.channels` in build.gradle.kts for a
    publishPlugin run by hand. One rule spelled twice, each beside a comment naming the other, because Gradle
    cannot be asked cheaply from a workflow and this module cannot configure Gradle. A change to one is a change
    to both.
    """
    suffix = VERSION.match(version).group(4)
    if suffix is None:
        return "default"
    return suffix.split(".")[0]


def sections(changelog: str) -> dict[str, str]:
    """Each section of a changelog, by the version it names. The link definitions at the foot of the file are
    left out: they are rewritten on every release and belong to no one section."""

    text = LINK_DEFINITION.sub("", changelog)
    found = {}
    marks = list(SECTION.finditer(text))
    for index, mark in enumerate(marks):
        end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
        found[mark.group(1)] = text[mark.end() : end].strip()
    return found


def check_changelog(head: str, released: dict[str, str], off_history: set[str] | None = None) -> list[str]:
    """Whether every section already released still reads the way the tag that released it says it does.

    `released` maps a version to CHANGELOG.md as it stood at that version's tag. A released section is a text
    that has been published - it is the release notes on GitHub and the change notes on the Marketplace both -
    so changing it afterwards makes the repository disagree with what readers were handed.

    `off_history` holds the versions whose tag this history does not reach, which `ancestry` is the check for.
    A section that is missing is read against it: between a release being published and its branch reaching the
    default branch, the section exists only where the tag does, and saying it is gone accuses somebody of
    deleting what nobody has written down here yet. Failing either way is right - the two are the same mess
    from two sides - but only one of them is a section somebody removed.
    """

    problems = []
    current = sections(head)
    off_history = off_history or set()

    for version, changelog in sorted(released.items(), key=lambda item: precedence(item[0])):
        was = sections(changelog).get(version)
        if was is None:
            continue  # Tagged before the section existed; there is nothing to have changed.
        if version not in current:
            if version in off_history:
                problems.append(
                    f"[{version}] is released but its section is not on this history: {version} is not on it "
                    f"either, so the release has yet to reach here"
                )
            else:
                problems.append(f"[{version}] is released but its section is gone")
        elif current[version] != was:
            problems.append(f"[{version}] is released but its section no longer reads as the tag has it")

    return problems


def unprotected(released: dict[str, str]) -> list[str]:
    """The released versions whose tag predates their section, so that check_changelog has nothing to hold them
    to. Named in the report rather than counted among the protected: a check that says it compared what it
    passed over is a check that passes having compared nothing, and nobody can tell from the outside."""
    return sorted((version for version, text in released.items() if version not in sections(text)), key=precedence)


def with_version(text: str, version: str) -> str:
    """gradle.properties with the version line rewritten and everything else left alone.

    A plain function over the text, like the rest of this file, so that what a release does to that line can be
    exercised without a repository - and so that the knowledge of how the line is written sits here, beside the
    reader of it, rather than in a pattern in a workflow that nothing tests. A replacement built by hand rather
    than by the regular expression's own substitution, because a version is data: `&` and `\\1` in it are
    characters, not instructions.
    """
    replaced, count = VERSION_LINE.subn(lambda found: found.group(1) + version, text, count=1)
    if count == 0:
        raise SystemExit("gradle.properties names no version to rewrite")
    return replaced


def check_ancestry(reachable: dict[str, bool], prefix: str, held_by: dict[str, str] | None = None) -> list[str]:
    """Whether every released tag is still part of the history it was released from.

    A tag points at the commit a release was built and signed from, and it is the only thing that still says
    what that was. Anything that rewrites the commits it sits among - a squash merge, a rebase merge, GitHub's
    `Update with rebase`, a force-push - leaves the tag pointing at a commit no branch reaches.

    Asking whether the tag is reachable answers that whatever the cause. Forbidding the causes one at a time
    does not: the list of ways to rewrite a branch is GitHub's to extend, not this project's.

    `held_by` names, for a tag that is not on this history, a branch that does still hold it. A release whose
    branch has not been carried back yet is exactly as unreleasable as one a rewrite orphaned, and is failed
    the same way - but it is a different thing to have happened and a different thing to do about it, so it is
    said differently. Blaming a rewrite for a merge that is merely outstanding sends the reader looking for
    damage that is not there.

    A branch holding the tag does not settle which of the two it was: a squash or a rebase merge replays the
    release commit and leaves the branch it came from standing, holding the original. Both readings are
    therefore named, because both are things the reader may be looking at and both are answered by looking at
    that one branch. Only a tag no branch holds at all is laid at a rewrite's door outright.
    """
    problems = []
    held_by = held_by or {}
    for version, found in sorted(reachable.items(), key=lambda item: precedence(item[0])):
        if found:
            continue
        branch = held_by.get(version)
        if branch:
            problems.append(
                f"{prefix}{version} is released but is not on this history: {branch} still holds it. Either "
                f"that branch has not been carried back yet, or it was landed with a squash or a rebase, "
                f"which replays the release commit and leaves the tag on the copy that was replaced"
            )
        else:
            problems.append(
                f"{prefix}{version} is released but is no longer reachable: a rewrite has taken it off this "
                f"history"
            )
    return problems


def git(*arguments: str) -> str:
    return subprocess.run(["git", *arguments], cwd=REPO, capture_output=True, text=True, check=True).stdout


def properties() -> dict[str, str]:
    """gradle.properties, read the way Gradle reads it rather than by prefix. `version = 0.2.0` and
    `version=0.2.0` are one declaration written two ways, and a reader that knows only the second reports a
    file that names no version when it names one."""
    found = {}
    for line in (REPO / "gradle.properties").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "!")) or "=" not in stripped:
            continue
        name, value = stripped.split("=", 1)
        found[name.strip()] = value.strip()
    return found


def declared_version() -> str:
    """The version gradle.properties names, which is what a release is asked to be."""
    declared = properties().get("version")
    if declared is None:
        raise SystemExit("gradle.properties names no version")
    return declared


def tag_prefix() -> str:
    """What a release tag carries in front of its version - `v0.1.0` against `0.1.0`.

    Declared rather than assumed, and with no default, because both spellings are in use and the wrong guess is
    silent: a repository that tags bare versions, read as though it tagged `v*`, turns up no released tags at
    all, and every check over them then passes having compared nothing.
    """
    prefix = properties().get("tagPrefix")
    if prefix is None:
        raise SystemExit("gradle.properties does not say, in tagPrefix, what release tags are called")
    return prefix


def tags() -> list[str]:
    """Every tag that names a version, with the prefix off. The repository carries tags that are not releases
    - the backups a history rewrite left behind - and those are nothing to be consistent with."""
    prefix = tag_prefix()
    return [
        tag[len(prefix) :]
        for tag in git("tag", "-l", f"{prefix}*").split()
        if tag.startswith(prefix) and VERSION.match(tag[len(prefix) :])
    ]


def version_command(arguments) -> list[str]:
    declared = arguments.version or declared_version()

    # Work carries the marker and a release is what drops it. Reading the release version off the development
    # one, rather than being handed it, is what keeps the two from ever naming different things.
    if not declared.endswith(SNAPSHOT):
        return [
            f"gradle.properties says {declared}, which is not a version being worked on: "
            f"between releases it names the next one, marked {SNAPSHOT}"
        ]

    candidate = declared[: -len(SNAPSHOT)]
    problems = check_version(candidate, tags())
    if not problems:
        print(candidate)
    return problems


def changelog_command(arguments) -> list[str]:
    head = (REPO / "CHANGELOG.md").read_text(encoding="utf-8")
    prefix = tag_prefix()

    released = {}
    for version in tags():
        try:
            released[version] = git("show", f"{prefix}{version}:CHANGELOG.md")
        except subprocess.CalledProcessError:
            continue  # Tagged before the file existed.

    problems = check_changelog(head, released, off_history=unreachable(prefix, released))
    if not problems:
        skipped = unprotected(released)
        print(f"Compared {len(released) - len(skipped)} released section(s) against the tag that released them.")
        if skipped:
            print(f"Not compared: {', '.join(skipped)} - tagged before the section existed, so the tag holds "
                  f"no text to compare against.")
    return problems


def next_command(arguments) -> list[str]:
    released = arguments.released.removeprefix(tag_prefix())
    if not VERSION.match(released):
        return [f"'{arguments.released}' is not a version this project releases"]
    print(next_worked_on(released))
    return []


def set_version_command(arguments) -> list[str]:
    """Write the version gradle.properties names, which is what a release does to it before it builds."""
    path = REPO / "gradle.properties"
    path.write_text(with_version(path.read_text(encoding="utf-8"), arguments.version), encoding="utf-8")

    # Read back through the same reader everything else here uses. A rewrite that matched nothing is the failure
    # worth catching and it is the quiet one, which is the whole reason this is not a pattern in a shell script.
    if declared_version() != arguments.version:
        return [f"gradle.properties still does not name {arguments.version} after being rewritten"]
    print(arguments.version)
    return []


def channel_command(arguments) -> list[str]:
    version = arguments.version.removeprefix(tag_prefix())
    if not VERSION.match(version):
        return [f"'{arguments.version}' is not a version this project releases"]
    print(channel_of(version))
    return []


def prefix_command(arguments) -> list[str]:
    """What release tags are called here. Printed rather than written into the workflows, so that the spelling
    is declared once and a repository cannot come to disagree with its own tags."""
    print(tag_prefix())
    return []


def unreachable(prefix: str, versions) -> set[str]:
    """The released versions whose tag this history does not reach. Asked of Git in one place and handed to
    whichever check needs it, so that two checks looking at one repository cannot come to disagree about which
    releases are on it."""
    return {
        version
        for version in versions
        if subprocess.run(
            ["git", "merge-base", "--is-ancestor", f"{prefix}{version}", "HEAD"], cwd=REPO, capture_output=True
        ).returncode
        != 0
    }


def holder(tag: str, version: str) -> str:
    """A branch that still holds `tag`, or the empty string if none does.

    Asked only of a tag that HEAD does not reach, and only to tell one report apart from the other. Remote
    branches are asked first and named as they are fetched: a release is carried back from what the remote
    has, and a local branch of the same name may be something else entirely. The release branch is named
    ahead of anything else that happens to hold the tag, because it is the one the reader has to act on.
    """
    for pattern in ("refs/remotes", "refs/heads"):
        branches = git("for-each-ref", "--contains", tag, "--format=%(refname:short)", pattern).split()
        if branches:
            return sorted(branches, key=lambda name: (not name.endswith(f"release/{version}"), name))[0]
    return ""


def ancestry_command(arguments) -> list[str]:
    prefix = tag_prefix()
    released = tags()
    off_history = unreachable(prefix, released)
    reachable = {version: version not in off_history for version in released}

    held_by = {
        version: holder(f"{prefix}{version}", version) for version, found in reachable.items() if not found
    }
    problems = check_ancestry(reachable, prefix, held_by)
    if not problems:
        print(f"All {len(reachable)} released tag(s) are reachable from HEAD.")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(required=True)

    version = commands.add_parser("version", help="whether the declared version may be released next")
    version.add_argument("--version", help="check this instead of what gradle.properties says")
    version.set_defaults(run=version_command)

    following = commands.add_parser("next", help="the version to be worked on once one is released")
    following.add_argument("released", help="the version just released, with or without the leading v")
    following.set_defaults(run=next_command)

    changelog = commands.add_parser("changelog", help="whether released sections still read as released")
    changelog.set_defaults(run=changelog_command)

    setting = commands.add_parser("set-version", help="write the version gradle.properties names")
    setting.add_argument("version", help="the version to write")
    setting.set_defaults(run=set_version_command)

    prefix = commands.add_parser("prefix", help="what release tags carry in front of the version")
    prefix.set_defaults(run=prefix_command)

    channel = commands.add_parser("channel", help="the Marketplace channel a version is published to")
    channel.add_argument("version", help="the version, with or without the tag prefix")
    channel.set_defaults(run=channel_command)

    ancestry = commands.add_parser("ancestry", help="whether every released tag is still reachable")
    ancestry.set_defaults(run=ancestry_command)

    arguments = parser.parse_args()
    problems = arguments.run(arguments)

    for problem in problems:
        print(problem, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
