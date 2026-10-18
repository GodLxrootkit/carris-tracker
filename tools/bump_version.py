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


def next_patch(version_name):
    """2.5.0 -> 2.5.1. Preserves a pre-release suffix rather than dropping it silently."""
    core, _, suffix = version_name.partition("-")
    parts = core.split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        sys.exit(f"versionName {version_name!r} is not MAJOR.MINOR.PATCH")
    major, minor, patch = (int(p) for p in parts)
    bumped = f"{major}.{minor}.{patch + 1}"
    return f"{bumped}-{suffix}" if suffix else bumped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default="app/build.gradle.kts")
    parser.add_argument("--apply", action="store_true",
                        help="rewrite the file; without it, just print the next version")
    parser.add_argument("--github-output", metavar="FILE",
                        help="append version= and code= to a GITHUB_OUTPUT file")
    args = parser.parse_args()

    path = pathlib.Path(args.file)
    text = path.read_text(encoding="utf-8")
    name_m, code_m = parse(text)

    current_name = name_m.group(2)
    current_code = int(code_m.group(2))
    new_name = next_patch(current_name)
    new_code = current_code + 1

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