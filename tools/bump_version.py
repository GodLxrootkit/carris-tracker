"""Read or bump the app version in app/build.gradle.kts.

The weekly release needs the next patch version, and it needs it to be the *minimum* increment:
2.5.0 -> 2.5.1 -> 2.5.2, so the in-app updater sees strictly newer tags each week.

Both fields move together. `versionName` is what the app compares release tags against, and
`versionCode` is what Android itself refuses to let go backwards on - bump only the first and
installed devices would silently refuse the APK.
"""

import argparse
import pathlib
import re
import sys

# Anchored on the exact shape the file has, so a reformat cannot make this rewrite something
# it did not read. Both must be present or we refuse rather than guess.
NAME_RE = re.compile(r'^(\s*)versionName\s*=\s*"([^"]+)"\s*$', re.MULTILINE)
CODE_RE = re.compile(r'^(\s*)versionCode\s*=\s*(\d+)\s*$', re.MULTILINE)


def parse(text):
    name = NAME_RE.search(text)
    code = CODE_RE.search(text)
    if not name or not code:
        sys.exit("could not find versionName/versionCode in app/build.gradle.kts")
    return name, code


def version_core(version_name):
    """2.5.1-rc1 -> (2, 5, 1), refusing anything that is not MAJOR.MINOR.PATCH."""
    parts = version_name.partition("-")[0].split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        sys.exit(f"versionName {version_name!r} is not MAJOR.MINOR.PATCH")
    return tuple(int(p) for p in parts)


def next_patch(version_name, floor=None):
    """2.5.0 -> 2.5.1, but never at or below [floor] (the highest published tag).

    The version file can fall behind the tags after a history rewrite, and reusing a tag then
    fails only once the bump has already been pushed.
    """
    major, minor, patch = version_core(version_name)
    if floor:
        floor_major, floor_minor, floor_patch = version_core(floor)
        if (floor_major, floor_minor) > (major, minor):
            sys.exit(f"cannot bump {version_name!r} past {floor!r}: raise the minor version by hand")
        if (floor_major, floor_minor) == (major, minor):
            patch = max(patch, floor_patch)
    _, _, suffix = version_name.partition("-")
    bumped = f"{major}.{minor}.{patch + 1}"
    return f"{bumped}-{suffix}" if suffix else bumped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default="app/build.gradle.kts")
    parser.add_argument("--apply", action="store_true",
                        help="rewrite the file; without it, just print the next version")
    parser.add_argument("--github-output", metavar="FILE",
                        help="append version= and code= to a GITHUB_OUTPUT file")
    parser.add_argument("--floor", metavar="VERSION",
                        help="highest version already published (a release tag); never reuse it")
    args = parser.parse_args()

    path = pathlib.Path(args.file)
    text = path.read_text(encoding="utf-8")
    name_m, code_m = parse(text)

    current_name = name_m.group(2)
    current_code = int(code_m.group(2))
    floor = args.floor.removeprefix("v").removeprefix("V") if args.floor else None
    new_name = next_patch(current_name, floor)
    # Advance versionCode by the number of releases the name moved past the file's version.
    # Normally that is 1; after a stale file it also covers the skipped patch versions.
    new_code = current_code + version_core(new_name)[2] - version_core(current_name)[2]

    # Guard against the two drifting apart: a release whose code is not ahead of the published
    # one installs as a downgrade, which Android refuses.
    if new_code <= current_code:
        sys.exit("refusing to bump: versionCode would not increase")

    if args.apply:
        text = NAME_RE.sub(lambda m: f'{m.group(1)}versionName = "{new_name}"', text, count=1)
        text = CODE_RE.sub(lambda m: f'{m.group(1)}versionCode = {new_code}', text, count=1)
        path.write_text(text, encoding="utf-8")

    if args.github_output:
        # Written here rather than assembled from shell one-liners with nested quotes, which is
        # where a workflow like this usually breaks.
        with open(args.github_output, "a", encoding="utf-8") as fh:
            fh.write(f"before={current_name}\n")
            fh.write(f"version={new_name}\n")
            fh.write(f"code={new_code}\n")

    verb = "bumped to" if args.apply else "next would be"
    print(f"{current_name}({current_code}) -> {verb} {new_name}({new_code})")


if __name__ == "__main__":
    main()