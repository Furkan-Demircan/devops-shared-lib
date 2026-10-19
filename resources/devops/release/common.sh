#!/usr/bin/env bash
# releasePipeline'ın shell yardımcıları. releaseSteps bu dosyanın sonuna çalıştırılacak komutları ekler.
#
# Shebang sayesinde Jenkins bu script'i 'sh -xe' ile değil doğrudan bash ile çalıştırır: komutlar loga
# yazılmaz, credential'lar (GH_TOKEN, base64'lü hâlleri vb.) maskelenmeden sızmaz.
#
# Ortam değişkenleri (releaseSteps verir):
#   GH_USER, GH_TOKEN : GitHub kullanıcı adı + PAT (git push, ghcr.io)
#   REPO_URL          : Repo'nun clone adresi
#   IMAGE             : Tag'siz image adı, ör. ghcr.io/org/app
set -euo pipefail

CLEANUP=()
trap 'rm -rf "${CLEANUP[@]}"' EXIT

die() { echo "HATA: $*" >&2; exit 1; }

if [ -n "${IMAGE:-}" ]; then
    REGISTRY=${IMAGE%%/*}
    IMAGE_PATH=${IMAGE#*/}
fi

MANIFEST_TYPES='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'

# ---------------------------------------------------------------- git

# GH_USER/GH_TOKEN ile kimlik doğrulayan git. Token remote URL'ye veya .git/config'e yazılmaz;
# helper ilk satırda sıfırlanır ki makinedeki başka bir credential helper devreye girmesin.
# shellcheck disable=SC2016  # $GH_USER/$GH_TOKEN, helper çalışırken açılır (komut satırına/loga düşmez)
git_auth() {
    git -c credential.helper= \
        -c 'credential.helper=!f() { test "$1" = get || exit 0; echo "username=$GH_USER"; echo "password=$GH_TOKEN"; }; f' \
        "$@"
}

# Uzak repodaki tag'in işaret ettiği commit. Tag yoksa boş döner, git hatasında script durur.
remote_tag_commit() {
    local out
    out=$(git_auth ls-remote --tags "$REPO_URL" "refs/tags/$1" "refs/tags/$1^{}") || die "git ls-remote başarısız ($1)"
    # Annotated tag'de '^{}' satırı commit'i, düz satır tag nesnesini verir
    printf '%s\n' "$out" | awk -v t="refs/tags/$1" '$2 == t "^{}" { p = $1 } $2 == t { l = $1 } END { print (p ? p : l) }'
}

# ---------------------------------------------------------------- registry (Docker Registry v2 API)

# $1: pull | pull,push
registry_token() {
    local resp
    # Kullanıcı adı/token curl'e argüman olarak değil stdin'den verilir (process listesinde görünmesin)
    resp=$(printf 'user = "%s:%s"\n' "$GH_USER" "$GH_TOKEN" |
        curl -fsS -K - "https://${REGISTRY}/token?service=${REGISTRY}&scope=repository:${IMAGE_PATH}:$1") ||
        die "${REGISTRY} token'ı alınamadı; PAT'te read:packages/write:packages yetkisi ve paket erişimi olmalı"
    printf '%s' "$resp" | sed -n 's/.*"token" *: *"\([^"]*\)".*/\1/p'
}

manifest_url() { echo "https://${REGISTRY}/v2/${IMAGE_PATH}/manifests/$1"; }

# Tag (veya digest) registry'de varsa manifest digest'ini yazar, yoksa boş döner.
# 200/404 dışındaki her yanıtta durur: erişim hatası "image yok" diye yorumlanmasın.
# Genelde $(...) içinde çağrılır; subshell'de CLEANUP/trap işlemediği için geçici dosyasını kendisi siler.
image_digest() {
    local tok headers code digest
    tok=$(registry_token pull)
    headers=$(mktemp)
    code=$(curl -sS -I -o /dev/null -D "$headers" -w '%{http_code}' \
        -H "Authorization: Bearer $tok" -H "Accept: $MANIFEST_TYPES" "$(manifest_url "$1")") || code=000
    digest=$(tr -d '\r' < "$headers" | awk -F': ' 'tolower($1) == "docker-content-digest" { print $2 }')
    rm -f "$headers"
    case $code in
        200) [ -n "$digest" ] || die "Registry digest döndürmedi (${IMAGE}:$1)"; echo "$digest" ;;
        404) ;;
        *) die "Registry sorgusu başarısız (${IMAGE}:$1, HTTP ${code})" ;;
    esac
}

# Manifest (tag veya digest ile) ya da blob (digest ile) içeriğini yazar. $1: manifest | blob, $2: referans.
# releaseSteps image config'indeki label'ları okumak için kullanır.
registry_get() {
    local tok kind=$1 ref=$2
    tok=$(registry_token pull)
    if [ "$kind" = manifest ]; then
        curl -fsS -H "Authorization: Bearer $tok" -H "Accept: $MANIFEST_TYPES" "$(manifest_url "$ref")"
    else
        # Blob'lar imzalı bir adrese yönlendirilir; curl Authorization'ı başka host'a taşımaz
        curl -fsSL -H "Authorization: Bearer $tok" "https://${REGISTRY}/v2/${IMAGE_PATH}/blobs/$ref"
    fi || die "Registry'den okunamadı ($kind $ref)"
}

# Build'e özel docker config: giriş bilgisi Jenkins home'da kalmaz
docker_login() {
    DOCKER_CONFIG=$(mktemp -d); export DOCKER_CONFIG; CLEANUP+=("$DOCKER_CONFIG")
    printf '%s' "$GH_TOKEN" | docker login "$REGISTRY" -u "$GH_USER" --password-stdin > /dev/null
}

# ---------------------------------------------------------------- ssh

# SSH_KEY_FILE/SSH_USER: sshUserPrivateKey credential'ı, SSH_KNOWN_HOSTS: sunucunun host key satır(lar)ı
ssh_setup() {
    local dir
    dir=$(mktemp -d); CLEANUP+=("$dir")
    printf '%s\n' "$SSH_KNOWN_HOSTS" > "$dir/known_hosts"
    SSH_OPTS=(-i "$SSH_KEY_FILE" -o IdentitiesOnly=yes -o BatchMode=yes -o ConnectTimeout=20
        -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$dir/known_hosts")
}

# remote.sh'ı sunucuda çalıştırır; script stdin'den gider, sunucuya dosya bırakılmaz
remote() {
    printf '%s' "$REMOTE_SCRIPT" | ssh "${SSH_OPTS[@]}" "${SSH_USER}@${DEPLOY_HOST}" bash -s -- "$@"
}
