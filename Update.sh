#!/usr/bin/env bash
# ==============================================================================
# Update.sh — Antigravity Linux GitHub Repository Updater
# ==============================================================================

set -e

# Terminal colors
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m' # No Color

WORKSPACE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_FILE="${WORKSPACE_DIR}/.github_config"

echo -e "${CYAN}${BOLD}"
echo "============================================================"
echo "          Antigravity Linux — GitHub Updater                "
echo "============================================================"
echo -e "${NC}"

cd "$WORKSPACE_DIR"

# 1. Load Stored Configuration
if [ -f "$CONFIG_FILE" ]; then
    # shellcheck source=/dev/null
    source "$CONFIG_FILE"
    echo -e "${GREEN}[✓] Loaded stored credentials for:${NC} ${CYAN}${GITHUB_USER}/${GITHUB_REPO}${NC}"
else
    echo -e "${YELLOW}[!] Configuration file (.github_config) not found.${NC}"
    echo -e "You can run ${CYAN}./UploadGithub.sh${NC} first, or enter credentials now:"
    echo ""
    read -s -p "Enter your GitHub Personal Access Token (PAT): " GITHUB_PAT
    echo ""
    read -p "Enter your GitHub Username: " GITHUB_USER
    read -p "Enter your GitHub Repository Name: " GITHUB_REPO

    # Save to config file
    cat <<EOF > "$CONFIG_FILE"
# Antigravity Linux GitHub Config
# Created on $(date -u)
GITHUB_USER="${GITHUB_USER}"
GITHUB_REPO="${GITHUB_REPO}"
GITHUB_PAT="${GITHUB_PAT}"
EOF
    chmod 600 "$CONFIG_FILE"
    echo -e "${GREEN}[✓] Credentials saved to .github_config.${NC}"
fi

# Ensure git remote is configured with token
REMOTE_URL="https://${GITHUB_USER}:${GITHUB_PAT}@github.com/${GITHUB_USER}/${GITHUB_REPO}.git"
if git remote | grep -q "^origin$"; then
    git remote set-url origin "$REMOTE_URL"
else
    git remote add origin "$REMOTE_URL"
fi

# 2. Check Git Status
echo ""
echo -e "${CYAN}[*] Checking for modified, new, or deleted files...${NC}"
STATUS_OUTPUT=$(git status --porcelain)

if [ -z "$STATUS_OUTPUT" ]; then
    echo -e "${YELLOW}[!] Working tree clean. No changes detected to commit.${NC}"
    read -p "Force push anyway? (y/n): " FORCE_PUSH
    if [[ ! "$FORCE_PUSH" =~ ^[Yy]$ ]]; then
        echo -e "${GREEN}[✓] Repository is up to date.${NC}"
        exit 0
    fi
else
    echo -e "${CYAN}Changed files:${NC}"
    git status -s
fi

echo ""
# 3. Ask for Commit Name / Message
echo -e "${YELLOW}Step: Commit Information${NC}"
read -p "Enter your commit message (commit name): " COMMIT_MSG

if [ -z "$COMMIT_MSG" ]; then
    COMMIT_MSG="Update: $(date '+%Y-%m-%d %H:%M:%S')"
    echo -e "${YELLOW}[*] Using default commit message: '${COMMIT_MSG}'${NC}"
fi

# 4. Stage & Commit
echo ""
echo -e "${CYAN}[*] Staging all changes...${NC}"
git add -A

if ! git diff --cached --quiet; then
    echo -e "${CYAN}[*] Committing with message: '${COMMIT_MSG}'...${NC}"
    git commit -m "$COMMIT_MSG"
fi

# 5. Push to GitHub
echo -e "${CYAN}[*] Pushing updates to https://github.com/${GITHUB_USER}/${GITHUB_REPO} (main branch)...${NC}"
if git push origin main; then
    echo ""
    echo -e "${GREEN}${BOLD}============================================================${NC}"
    echo -e "${GREEN}${BOLD} [SUCCESS] GitHub repository updated successfully!          ${NC}"
    echo -e "${GREEN}${BOLD} Repository: https://github.com/${GITHUB_USER}/${GITHUB_REPO}${NC}"
    echo -e "${GREEN}${BOLD} Commit:     ${COMMIT_MSG}${NC}"
    echo -e "${GREEN}${BOLD}============================================================${NC}"
else
    echo -e "${RED}[ERROR] Push failed. Attempting to pull with rebase first...${NC}"
    git pull --rebase origin main
    echo -e "${CYAN}[*] Re-attempting push...${NC}"
    git push origin main
    echo -e "${GREEN}[✓] Push succeeded after rebase!${NC}"
fi
