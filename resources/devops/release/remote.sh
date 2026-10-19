#!/usr/bin/env bash
# Deploy sunucusunda çalışır; Jenkins ssh ile stdin'den gönderir:
#   bash -s -- current <dizin> <servis> <image>
#   bash -s -- deploy  <dizin> <servis> <image> <tag> [health-url]
#
# Dizinde bir compose dosyası ve .env bulunur. Servisin image satırı sürümü IMAGE_TAG'den alır:
#   image: ghcr.io/org/app:${IMAGE_TAG:?IMAGE_TAG tanımlı değil}
set -euo pipefail

cmd=$1 dir=$2 service=$3 image=$4
cd "$dir"

# Servisin çalışan container'ının image'ı (ör. ghcr.io/org/app:1.0.5); container yoksa boş.
# .env'de henüz IMAGE_TAG yoksa (ilk kurulum) compose dosyası çözümlenebilsin diye yer tutucu verilir.
running_image() {
    local id
    if [ -n "${IMAGE_TAG:-}" ] || grep -q '^IMAGE_TAG=' .env 2> /dev/null; then
        id=$(docker compose ps -q "$service")
    else
        id=$(IMAGE_TAG=none docker compose ps -q "$service")
    fi
    if [ -n "$id" ]; then
        docker inspect --format '{{.Config.Image}}' "$id"
    fi
}

case $cmd in
    current)
        running_image
        ;;
    deploy)
        tag=$5 health=${6:-}
        export IMAGE_TAG=$tag # .env'deki eski değeri ezer
        docker compose pull "$service"
        docker compose up -d --no-deps --wait --wait-timeout 180 "$service"

        actual=$(running_image)
        if [ "$actual" != "$image:$tag" ]; then
            echo "HATA: $service '$actual' ile çalışıyor, beklenen '$image:$tag'." \
                "Compose dosyasındaki image satırı \${IMAGE_TAG} kullanmalı." >&2
            exit 1
        fi
        if [ -n "$health" ] &&
            ! curl -fsS -o /dev/null --max-time 5 --retry 20 --retry-delay 3 --retry-all-errors "$health"; then
            echo "HATA: Sağlık kontrolü başarısız ($health)" >&2
            exit 1
        fi

        # Sürüm .env'e yazılır: sunucuda elle çalıştırılan 'docker compose up' da aynı sürümü kullanır
        if grep -q '^IMAGE_TAG=' .env 2> /dev/null; then
            sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$tag/" .env
        else
            printf 'IMAGE_TAG=%s\n' "$tag" >> .env
        fi
        echo "$service -> $image:$tag"
        ;;
    *)
        echo "remote.sh: bilinmeyen komut '$cmd'" >&2
        exit 2
        ;;
esac
