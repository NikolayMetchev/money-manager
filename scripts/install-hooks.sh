#!/usr/bin/env bash
# Install Git hooks for the project
# Works on Windows (Git Bash), Linux, and macOS

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOOKS_DIR="$SCRIPT_DIR/hooks"

# Determine the correct Git hooks directory, robust to worktrees and repo location
GIT_HOOKS_DIR="$(git -C "$SCRIPT_DIR/.." rev-parse --git-path hooks)"

echo "Installing Git hooks..."

# Ensure .git/hooks directory exists
if [ ! -d "$GIT_HOOKS_DIR" ]; then
    mkdir -p "$GIT_HOOKS_DIR"
    echo "Created hooks directory at $GIT_HOOKS_DIR"
fi

# Install pre-commit hook
if [ -f "$HOOKS_DIR/pre-commit" ]; then
    cp "$HOOKS_DIR/pre-commit" "$GIT_HOOKS_DIR/pre-commit"
    chmod +x "$GIT_HOOKS_DIR/pre-commit"
    echo "✓ Installed pre-commit hook"
else
    echo "✗ pre-commit hook not found in $HOOKS_DIR"
    exit 1
fi

echo ""
echo "Git hooks installed successfully!"
echo ""
echo "The pre-commit hook will:"
echo "  - Validate codecov.yml changes before committing"
