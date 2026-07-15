#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly ENV_FILE="$PROJECT_DIR/.env"
readonly DEFAULT_MODEL="embeddinggemma:300m"

NON_INTERACTIVE=false
INSTALL_DOCKER=false
DOCKER=()

info() { printf '\033[1;34m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[OK]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[WARN]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31m[ERROR]\033[0m %s\n' "$*" >&2; exit 1; }

on_error() {
    local exit_code=$?
    printf '\n\033[1;31mSetup failed at line %s (exit %s).\033[0m\n' "${BASH_LINENO[0]}" "$exit_code" >&2
    if ((${#DOCKER[@]})); then
        "${DOCKER[@]}" compose -f "$PROJECT_DIR/docker-compose.yml" --project-directory "$PROJECT_DIR" logs --tail=80 ai-review 2>/dev/null || true
    fi
    exit "$exit_code"
}
trap on_error ERR

usage() {
    cat <<'EOF'
Usage: ./scripts/setup-and-run.sh [options]

Options:
  --install-docker    Install Docker Engine from Docker's official APT repository
                      when Docker is not installed (Debian/Ubuntu only).
  --non-interactive   Reuse an existing .env without asking questions.
  -h, --help          Show this help.
EOF
}

while (($#)); do
    case "$1" in
        --install-docker) INSTALL_DOCKER=true ;;
        --non-interactive) NON_INTERACTIVE=true ;;
        -h|--help) usage; exit 0 ;;
        *) die "Unknown argument: $1" ;;
    esac
    shift
done

[[ "$(uname -s)" == "Linux" ]] || die "This setup script supports Linux servers only."
cd "$PROJECT_DIR"

sudo_run() {
    if ((EUID == 0)); then "$@"; else command -v sudo >/dev/null || die "sudo is required"; sudo "$@"; fi
}

install_docker() {
    [[ -r /etc/os-release ]] || die "Cannot identify the Linux distribution."
    # shellcheck disable=SC1091
    . /etc/os-release
    case "${ID:-}" in
        ubuntu|debian) ;;
        *) die "Automatic Docker installation supports Debian and Ubuntu only. Install Docker manually and rerun." ;;
    esac
    local distro="$ID"
    local codename="${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}"
    [[ -n "$codename" ]] || die "Cannot determine distribution codename."

    info "Installing Docker Engine from the official $distro repository..."
    sudo_run apt-get update
    sudo_run apt-get install -y ca-certificates curl
    sudo_run install -m 0755 -d /etc/apt/keyrings
    sudo_run curl -fsSL "https://download.docker.com/linux/$distro/gpg" -o /etc/apt/keyrings/docker.asc
    sudo_run chmod a+r /etc/apt/keyrings/docker.asc

    local architecture
    architecture="$(dpkg --print-architecture)"
    printf 'Types: deb\nURIs: https://download.docker.com/linux/%s\nSuites: %s\nComponents: stable\nArchitectures: %s\nSigned-By: /etc/apt/keyrings/docker.asc\n' \
        "$distro" "$codename" "$architecture" |
        sudo_run tee /etc/apt/sources.list.d/docker.sources >/dev/null
    sudo_run apt-get update
    sudo_run apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    sudo_run systemctl enable --now docker
    ok "Docker Engine installed."
}

select_docker_command() {
    if ! command -v docker >/dev/null; then
        if [[ "$INSTALL_DOCKER" == true ]]; then
            install_docker
        elif [[ "$NON_INTERACTIVE" == true ]]; then
            die "Docker is not installed. Rerun with --install-docker."
        else
            read -r -p "Docker is not installed. Install it now from the official repository? [y/N] " answer
            [[ "$answer" =~ ^[Yy]$ ]] || die "Docker is required."
            install_docker
        fi
    fi

    if docker info >/dev/null 2>&1; then
        DOCKER=(docker)
    elif ((EUID == 0)); then
        DOCKER=(docker)
    elif command -v sudo >/dev/null && sudo docker info >/dev/null 2>&1; then
        DOCKER=(sudo docker)
        warn "Docker requires sudo for the current user."
    else
        die "Docker daemon is unavailable. Check: systemctl status docker"
    fi
    "${DOCKER[@]}" compose version >/dev/null || die "Docker Compose v2 plugin is required."
}

compose() {
    "${DOCKER[@]}" compose -f "$PROJECT_DIR/docker-compose.yml" --project-directory "$PROJECT_DIR" "$@"
}

require_value() {
    local name="$1" value="$2"
    [[ -n "$value" ]] || die "$name must not be empty."
    [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || die "$name must be a single line."
    [[ "$value" != *"'"* ]] || die "$name must not contain a single quote."
}

write_env() {
    local auth_mode github_app_id="" github_private_key="" github_token=""
    local deepseek_key public_url

    printf '\nGitHub authentication:\n  1) GitHub App (recommended)\n  2) Fine-grained token\n'
    read -r -p "Select [1]: " auth_mode
    auth_mode="${auth_mode:-1}"
    case "$auth_mode" in
        1)
            read -r -p "GitHub App ID: " github_app_id
            require_value "GitHub App ID" "$github_app_id"
            [[ "$github_app_id" =~ ^[0-9]+$ ]] || die "GitHub App ID must contain digits only."
            read -r -p "Path to the GitHub App PEM private key: " pem_path
            [[ -r "$pem_path" ]] || die "Cannot read PEM file: $pem_path"
            grep -q -- "-----BEGIN .*PRIVATE KEY-----" "$pem_path" || die "The selected file is not a PEM private key."
            github_private_key="$(awk '{printf "%s\\n", $0}' "$pem_path")"
            github_private_key="${github_private_key%\\n}"
            ;;
        2)
            read -r -s -p "GitHub fine-grained token: " github_token; printf '\n'
            require_value "GitHub token" "$github_token"
            ;;
        *) die "Select 1 or 2." ;;
    esac

    read -r -s -p "DeepSeek API key: " deepseek_key; printf '\n'
    require_value "DeepSeek API key" "$deepseek_key"
    read -r -p "Public HTTPS base URL (optional, e.g. https://review.example.com): " public_url
    public_url="${public_url%/}"
    if [[ -n "$public_url" ]]; then
        require_value "Public URL" "$public_url"
        [[ "$public_url" =~ ^https://[^[:space:]]+$ ]] || die "Public URL must start with https:// and contain no spaces."
    fi

    local webhook_secret temp_file
    webhook_secret="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    temp_file="$(mktemp "$PROJECT_DIR/.env.tmp.XXXXXX")"
    chmod 600 "$temp_file"
    {
        printf "GITHUB_WEBHOOK_SECRET='%s'\n" "$webhook_secret"
        if [[ "$auth_mode" == 1 ]]; then
            printf "GITHUB_APP_ID='%s'\n" "$github_app_id"
            printf "GITHUB_APP_PRIVATE_KEY='%s'\n" "$github_private_key"
        else
            printf "GITHUB_TOKEN='%s'\n" "$github_token"
        fi
        printf "DEEPSEEK_API_KEY='%s'\n" "$deepseek_key"
        printf "DEEPSEEK_BASE_URL='https://api.deepseek.com'\n"
        printf "DEEPSEEK_MODEL='deepseek-v4-flash'\n"
        printf "OLLAMA_EMBED_MODEL='%s'\n" "$DEFAULT_MODEL"
        printf "RAG_TOP_K=5\nRAG_CHUNK_SIZE=1800\nRAG_CHUNK_OVERLAP=200\n"
        printf "MAX_PATCH_CHARS=60000\nPORT=8080\n"
        [[ -z "$public_url" ]] || printf "PUBLIC_BASE_URL='%s'\n" "$public_url"
    } >"$temp_file"
    mv -f -- "$temp_file" "$ENV_FILE"
    chmod 600 "$ENV_FILE"

    printf '\nSave this value in the GitHub App webhook settings:\n'
    printf '  Webhook secret: %s\n' "$webhook_secret"
    if [[ -n "$public_url" ]]; then
        printf '  Payload URL:    %s/webhooks/github\n' "$public_url"
    else
        printf '  Payload URL:    https://YOUR-DOMAIN/webhooks/github\n'
    fi
    printf '  Content type:   application/json\n  Event:          Pull requests\n\n'
}

configure_environment() {
    if [[ "$NON_INTERACTIVE" == true ]]; then
        [[ -s "$ENV_FILE" ]] || die "--non-interactive requires an existing non-empty .env file."
        info "Using existing .env."
        return
    fi
    if [[ -s "$ENV_FILE" ]]; then
        read -r -p ".env already exists. Reuse it without changing secrets? [Y/n] " reuse
        if [[ ! "$reuse" =~ ^[Nn]$ ]]; then
            info "Using existing .env."
            return
        fi
        cp -p -- "$ENV_FILE" "$ENV_FILE.backup.$(date +%Y%m%d%H%M%S)"
        info "A timestamped .env backup was created."
    fi
    write_env
}

wait_for_ollama() {
    info "Waiting for Ollama..."
    for _ in {1..60}; do
        if compose exec -T ollama ollama list >/dev/null 2>&1; then return; fi
        sleep 2
    done
    die "Ollama did not become ready within 120 seconds."
}

wait_for_application() {
    info "Waiting for AI Review health endpoint..."
    for _ in {1..60}; do
        if curl --fail --silent --max-time 3 http://127.0.0.1:8080/health >/dev/null 2>&1; then return; fi
        sleep 2
    done
    compose logs --tail=100 ai-review || true
    die "AI Review did not become healthy within 120 seconds."
}

select_docker_command
configure_environment

info "Validating Compose configuration..."
compose config --quiet
info "Starting Ollama..."
compose pull ollama ollama-init
compose up -d ollama
wait_for_ollama
info "Downloading embedding model $DEFAULT_MODEL (first run can take several minutes)..."
compose run --rm ollama-init
info "Building and starting AI Review..."
compose up -d --build ai-review
wait_for_application

ok "AI Review is running."
printf '  Health:       http://127.0.0.1:8080/health\n'
printf '  Webhook path: /webhooks/github\n'
printf '  Status:       %s compose ps\n' "${DOCKER[*]}"
printf '  Logs:         %s compose logs -f ai-review\n' "${DOCKER[*]}"
