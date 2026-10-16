#!/usr/bin/env bash
# ==============================================================================
# UploadGithub.sh — Antigravity Linux Initial GitHub Uploader
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
echo "          Antigravity Linux — GitHub Repository Setup       "
echo "============================================================"
echo -e "${NC}"

# Check for git
if ! command -v git &> /dev/null; then
    echo -e "${RED}[ERROR] 'git' command not found. Please install git.${NC}"
    exit 1
fi

cd "$WORKSPACE_DIR"

# 1. Ask for GitHub Personal Access Token (PAT)
echo -e "${YELLOW}Step 1: GitHub Authentication${NC}"
echo -e "You need a GitHub Personal Access Token (Classic with 'repo' scope or Fine-grained)."
echo -e "Generate one at: ${CYAN}https://github.com/settings/tokens${NC}"
echo ""

# Check if config already has a PAT
DEFAULT_PAT=""
if [ -f "$CONFIG_FILE" ]; then
    # shellcheck source=/dev/null
    source "$CONFIG_FILE" 2>/dev/null || true
    DEFAULT_PAT="$GITHUB_PAT"
fi

if [ -n "$DEFAULT_PAT" ]; then
    echo -e "${GREEN}[*] Found existing token in .github_config: ${DEFAULT_PAT:0:7}...${DEFAULT_PAT: -4}${NC}"
    read -p "Use this saved token? (Y/n): " USE_SAVED
    if [[ "$USE_SAVED" =~ ^[Nn]$ ]]; then
        DEFAULT_PAT=""
    else
        GITHUB_PAT="$DEFAULT_PAT"
    fi
fi

if [ -z "$DEFAULT_PAT" ]; then
    read -s -p "Enter your GitHub Personal Access Token (PAT): " GITHUB_PAT
    echo ""
    while [ -z "$GITHUB_PAT" ]; do
        echo -e "${RED}[!] Token cannot be empty.${NC}"
        read -s -p "Enter your GitHub Personal Access Token (PAT): " GITHUB_PAT
        echo ""
    done
fi

# 2. Auto-detect GitHub Username from PAT
echo -e "${CYAN}[*] Validating token and detecting GitHub username...${NC}"
DETECTED_USER=$(curl -s -H "Authorization: token ${GITHUB_PAT}" https://api.github.com/user | grep '"login":' | head -1 | cut -d'"' -f4 || true)

if [ -n "$DETECTED_USER" ]; then
    echo -e "${GREEN}[✓] Authenticated as GitHub user:${NC} ${BOLD}${DETECTED_USER}${NC}"
    GITHUB_USER="$DETECTED_USER"
else
    echo -e "${YELLOW}[!] Could not auto-detect username from token.${NC}"
    read -p "Enter your GitHub Username: " GITHUB_USER
    while [ -z "$GITHUB_USER" ]; do
        echo -e "${RED}[!] Username cannot be empty.${NC}"
        read -p "Enter your GitHub Username: " GITHUB_USER
    done
fi

# 3. Ask for Repository Name
echo ""
echo -e "${YELLOW}Step 2: Repository Configuration${NC}"
DEFAULT_REPO="Antigravity-Android"
read -p "Enter your GitHub Repository Name [Default: ${DEFAULT_REPO}]: " INPUT_REPO
GITHUB_REPO="${INPUT_REPO:-$DEFAULT_REPO}"

# Clean repo name (remove URL prefix or .git suffix if user pasted a link)
GITHUB_REPO="$(echo "$GITHUB_REPO" | sed -e 's|^https://github.com/||' -e 's|\.git$||')"
# If user entered "username/reponame", extract clean username and reponame
if [[ "$GITHUB_REPO" == *"/"* ]]; then
    GITHUB_USER="$(echo "$GITHUB_REPO" | cut -d'/' -f1)"
    GITHUB_REPO="$(echo "$GITHUB_REPO" | cut -d'/' -f2)"
fi

echo ""
echo -e "${CYAN}[*] Target Repository: https://github.com/${GITHUB_USER}/${GITHUB_REPO}${NC}"

# 4. Save configuration for Update.sh (with restricted permissions)
cat <<EOF > "$CONFIG_FILE"
# Antigravity Linux GitHub Config
# Created on $(date -u)
GITHUB_USER="${GITHUB_USER}"
GITHUB_REPO="${GITHUB_REPO}"
GITHUB_PAT="${GITHUB_PAT}"
EOF

chmod 600 "$CONFIG_FILE"
echo -e "${GREEN}[✓] Credentials & configuration saved to .github_config (chmod 600 protected).${NC}"

# Ensure .github_config is ignored by git
if ! grep -q "^\.github_config" .gitignore 2>/dev/null; then
    echo ".github_config" >> .gitignore
fi

# 5. Initialize Git Repository (Compatible with all git versions)
if [ ! -d ".git" ]; then
    echo -e "${CYAN}[*] Initializing local git repository...${NC}"
    git init
    git checkout -b main 2>/dev/null || git branch -M main 2>/dev/null || true
else
    echo -e "${CYAN}[*] Local git repository already initialized.${NC}"
    git checkout -b main 2>/dev/null || git branch -M main 2>/dev/null || true
fi

# Configure git user if not set
if [ -z "$(git config user.name)" ]; then
    git config user.name "$GITHUB_USER"
fi
if [ -z "$(git config user.email)" ]; then
    git config user.email "${GITHUB_USER}@users.noreply.github.com"
fi

# 6. Check if remote repo exists; automatically create if missing
REMOTE_URL="https://${GITHUB_USER}:${GITHUB_PAT}@github.com/${GITHUB_USER}/${GITHUB_REPO}.git"

echo -e "${CYAN}[*] Checking repository on GitHub...${NC}"
API_CHECK=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: token ${GITHUB_PAT}" \
    "https://api.github.com/repos/${GITHUB_USER}/${GITHUB_REPO}" || true)

if [ "$API_CHECK" == "404" ]; then
    echo -e "${YELLOW}[!] Repository '${GITHUB_USER}/${GITHUB_REPO}' does not exist on GitHub.${NC}"
    echo -e "${CYAN}[*] Automatically creating repository '${GITHUB_REPO}' via GitHub API...${NC}"
    CREATE_RES=$(curl -s -H "Authorization: token ${GITHUB_PAT}" \
        -d "{\"name\":\"${GITHUB_REPO}\",\"description\":\"Antigravity Linux - Arch Linux ARM64 Desktop & Google Antigravity for Android\",\"private\":false}" \
        "https://api.github.com/user/repos")
    if echo "$CREATE_RES" | grep -q "\"name\": \"${GITHUB_REPO}\""; then
        echo -e "${GREEN}[✓] Repository successfully created on GitHub!${NC}"
    else
        echo -e "${YELLOW}[!] Note: Please ensure the repository is created manually on github.com if API creation failed.${NC}"
    fi
elif [ "$API_CHECK" == "200" ]; then
    echo -e "${GREEN}[✓] Repository verified on GitHub.${NC}"
fi

# Configure remote URL with authenticated credentials
if git remote | grep -q "^origin$"; then
    git remote set-url origin "$REMOTE_URL"
else
    git remote add origin "$REMOTE_URL"
fi

# 7. Stage all files
echo -e "${CYAN}[*] Staging files...${NC}"
git add -A

# 8. Initial Commit
COMMIT_COUNT=$(git rev-list --count HEAD 2>/dev/null || echo "0")
if [ "$COMMIT_COUNT" == "0" ]; then
    echo -e "${CYAN}[*] Creating initial commit...${NC}"
    git commit -m "Initial commit: Antigravity Linux - Arch Linux ARM64 Desktop for Android"
else
    if ! git diff --cached --quiet; then
        echo -e "${CYAN}[*] Committing changes...${NC}"
        git commit -m "Antigravity Linux project release update"
    fi
fi

# 9. Push to GitHub
echo -e "${CYAN}[*] Uploading files to https://github.com/${GITHUB_USER}/${GITHUB_REPO} (main branch)...${NC}"

if git push -u origin main; then
    echo ""
    echo -e "${GREEN}${BOLD}============================================================${NC}"
    echo -e "${GREEN}${BOLD} [SUCCESS] All files successfully uploaded to GitHub!       ${NC}"
    echo -e "${GREEN}${BOLD} Repository URL: https://github.com/${GITHUB_USER}/${GITHUB_REPO}${NC}"
    echo -e "${GREEN}${BOLD}============================================================${NC}"
    echo -e "You can now run ${CYAN}./Update.sh${NC} anytime to push future updates."
else
    echo -e "${YELLOW}[!] Push rejected (remote may contain conflicting commits). Trying force push...${NC}"
    if git push -u origin main --force; then
        echo ""
        echo -e "${GREEN}${BOLD}============================================================${NC}"
        echo -e "${GREEN}${BOLD} [SUCCESS] Project successfully synchronized to GitHub!     ${NC}"
        echo -e "${GREEN}${BOLD} Repository URL: https://github.com/${GITHUB_USER}/${GITHUB_REPO}${NC}"
        echo -e "${GREEN}${BOLD}============================================================${NC}"
        echo -e "You can now run ${CYAN}./Update.sh${NC} anytime to push future updates."
    else
        echo -e "${RED}[ERROR] Upload failed. Please check repository permissions or network connection.${NC}"
        exit 1
    fi
fi
