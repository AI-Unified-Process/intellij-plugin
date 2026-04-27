#!/usr/bin/env bash
#
# Cuts a release: promotes the <h3>Unreleased</h3> block in build.gradle.kts to
# the new version, bumps the `version = "..."` line, commits, tags, and pushes.
#
# Workflow:
#   - During development, edit changeNotes in build.gradle.kts and add bullets
#     under an <h3>Unreleased</h3> heading at the top of the changeNotes block.
#   - When ready to release: ./scripts/release.sh 0.2.0
#   - The CI workflow (.github/workflows/release.yml) takes it from there.

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

GRADLE_FILE="build.gradle.kts"

# --- args ---------------------------------------------------------------------

if [[ $# -ne 1 ]]; then
    echo "usage: $0 <new-version>   e.g. $0 0.2.0" >&2
    exit 1
fi

NEW_VERSION="$1"
TAG="v$NEW_VERSION"

if ! [[ "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    echo "error: '$NEW_VERSION' is not a valid semver (expected X.Y.Z[-suffix])" >&2
    exit 1
fi

# --- preflight ----------------------------------------------------------------

if [[ -n "$(git status --porcelain)" ]]; then
    echo "error: working tree has uncommitted changes — commit or stash first" >&2
    git status --short >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "error: tag '$TAG' already exists locally" >&2
    exit 1
fi

if git ls-remote --exit-code --tags origin "$TAG" >/dev/null 2>&1; then
    echo "error: tag '$TAG' already exists on origin" >&2
    exit 1
fi

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$CURRENT_BRANCH" != "main" ]]; then
    read -r -p "you are on '$CURRENT_BRANCH', not 'main'. Continue? [y/N] " reply
    [[ "$reply" =~ ^[Yy]$ ]] || exit 1
fi

if ! grep -q '<h3>Unreleased</h3>' "$GRADLE_FILE"; then
    cat >&2 <<EOF
error: $GRADLE_FILE has no <h3>Unreleased</h3> section.

Expected workflow:
  - During development, add an <h3>Unreleased</h3> heading at the top of the
    changeNotes string in build.gradle.kts and append <li> bullets as you work.
  - This script renames Unreleased -> $NEW_VERSION when you cut the release.
EOF
    exit 1
fi

# Make sure Unreleased has at least one <li>.
UNRELEASED_BODY="$(awk '
    /<h3>Unreleased<\/h3>/ { capture = 1; next }
    capture && /<h3>/      { exit }
    capture                { print }
' "$GRADLE_FILE")"

if ! grep -q '<li>' <<<"$UNRELEASED_BODY"; then
    echo "error: <h3>Unreleased</h3> section has no <li> entries — nothing to release." >&2
    exit 1
fi

# --- edits --------------------------------------------------------------------

# Portable in-place edits: sed -i.bak then rm works on both BSD (macOS) and GNU sed.
sed -i.bak -E 's/^version = "[^"]*"$/version = "'"$NEW_VERSION"'"/' "$GRADLE_FILE"
sed -i.bak "s|<h3>Unreleased</h3>|<h3>$NEW_VERSION</h3>|" "$GRADLE_FILE"
rm -f "$GRADLE_FILE.bak"

echo
echo "Proposed changes:"
echo "-----------------"
git --no-pager diff -- "$GRADLE_FILE"
echo "-----------------"
read -r -p "Commit, tag $TAG, and push to origin? [y/N] " reply
if [[ ! "$reply" =~ ^[Yy]$ ]]; then
    echo "aborting; reverting working-tree edits."
    git checkout -- "$GRADLE_FILE"
    exit 1
fi

# --- commit, tag, push --------------------------------------------------------

git add "$GRADLE_FILE"
git commit -m "Release $TAG"
git tag -a "$TAG" -m "Release $NEW_VERSION"
git push origin HEAD "$TAG"

echo
echo "Released $TAG."

REMOTE_URL="$(git remote get-url origin 2>/dev/null || true)"
if [[ "$REMOTE_URL" =~ github\.com[:/]([^/]+)/([^/]+)$ ]]; then
    OWNER="${BASH_REMATCH[1]}"
    REPO="${BASH_REMATCH[2]%.git}"
    echo "CI: https://github.com/$OWNER/$REPO/actions"
fi

echo
echo "Next: when you start work on the next release, add a fresh"
echo "  <h3>Unreleased</h3>"
echo "  <ul></ul>"
echo "block at the top of changeNotes in $GRADLE_FILE."
