# releasePipeline kurulumu

Bu doküman `releasePipeline` (devops-lib v3) için şunları içerir:
- GitHub, Jenkins ve sunucu tarafında yapılması gerekenler,
- kullanılan GitHub API çağrıları,
- bilinen riskler.

Developer'lar için kullanım: [developer-guide.md](developer-guide.md).

Kısaca: deploy'ları yalnızca tag'ler tetikler, merge'ler değil. Ortam branch'leri (`dev`, `test`, `main`)
bağımsızdır; feature'lar her birine ayrı PR ile merge edilir. Merge'den sonra o branch'in ucu tag'lenir: `dev`'e
`v1.0.5-dev`, `test`'e `v1.0.5-test`, `main`'e `v1.0.5`.

Jenkins hiç tag oluşturmaz; her tag'de o commit'ten image build edip ilgili ortama deploy eder. Her başarılı deploy,
tag'lenen commit'e `release/<sürüm>/<ortam> = success` status'u yazar. Sıradaki ortam, önceki ortamın tag'inin
commit'inde bu status'u şart koşar (dev → test → prod).

Repo'nun bir **organization** altında olması gerekir. Tag ruleset'lerinde "yalnızca şu grup atabilir" ayrımı
team bazlı bypass listesiyle yapılır ve bu kişisel repolarda yoktur.

## 1. GitHub

### 1.1 Team'ler ve Jenkins token'ı

1. Organization'da team'ler:
   - `testers`: `v*-test` tag'i atabilecekler.
   - `release-managers`: prod (`vX.Y.Z`) ve `rollback-*` tag'i atabilecekler. En dar grup bu olmalı.
2. Jenkins için ayrı bir **machine user** açın (ör. `techlife-jenkins`) ve repo'ya **Write** yetkisiyle ekleyin.
   Release ve commit status yazmak için Write gerekir; tag atmaz.
3. Bu kullanıcıyla bir **Personal Access Token (classic)** oluşturun. Gereken yetkiler: `repo`, `write:packages`,
   `read:packages`. `delete:packages` **gerekmez**, pipeline hiçbir şey silmez. GHCR fine-grained token'ları
   desteklemediği için classic token gerekir. Token'a son kullanma tarihi koyun.
4. Paket (ör. `usermanagement-api`) → Package settings → Manage access:
   - Jenkins kullanıcısı için **Write**.
   - Developer'lar için en fazla **Read**.

   Pakete yalnızca Jenkins yazabilmelidir (bkz. Riskler).

### 1.2 Repo ayarları (Settings → General → Pull Requests)

- ✅ **Allow merge commits**
- ⬜ Allow squash merging
- ⬜ Allow rebase merging
- Automatically delete head branches: ekip tercihi. Aynı feature branch'i sırayla dev, test ve main'e merge
  edileceği için açıksa ilk merge'den sonra silinir. Kapalı tutun.

Squash ve rebase kapalı olmalı. Jenkins, tag'in ortam branch'inin **kendi** commit'ine atıldığını
`git rev-list --first-parent origin/<branch>` ile doğrular. Merge commit'leri branch'in first-parent hattındadır;
rebase merge'de ise branch'e feature'ın kopyalanmış commit'leri dizilir ve merge kaydı kalmaz.

### 1.3 Branch ruleset (Settings → Rules → Rulesets → New branch ruleset)

| Alan | Değer |
|---|---|
| Name | `release-branches` |
| Enforcement | Active |
| Bypass list | boş (admin'ler dahil kimse atlayamasın) |
| Target branches | `dev`, `test`, `main` |
| Restrict deletions | ✅ |
| Require a pull request before merging | ✅. **Allowed merge methods: yalnızca Merge**. Onay sayısı ekip kararıdır |
| Block force pushes | ✅ |
| Require linear history | ⬜ (merge commit'leri engeller) |
| Require status checks to pass | Önerilir: `jenkins/ci` (PR build + test) |

Force push yasağı ayrıca şunu sağlar: tag'lenmiş bir commit, merge edildikten sonra branch geçmişinden çıkarılamaz.

### 1.4 Tag ruleset'leri (New tag ruleset)

GitHub tag desenleri fnmatch'tir: `v*.*.*` deseni `v1.0.5-dev` ve `v1.0.5-test` ile de eşleşir. Bu yüzden kurallar
"her şey kilitli, istisnalar şunlar" biçiminde kurulur:

| Ruleset | Target (include / exclude) | Kurallar | Bypass |
|---|---|---|---|
| `tags-immutable` | include: `~ALL` | Restrict updates, Restrict deletions | **boş** |
| `tags-test` | include: `v*-test` | Restrict creations | team `testers`, team `release-managers` |
| `tags-release` | include: `~ALL`; exclude: `v*-dev`, `v*-test` | Restrict creations | team `release-managers` |

Sonuç:
- Write yetkisi olan her developer yalnızca `v*-dev` oluşturabilir.
- `v*-test` tag'ini `testers` (ve `release-managers`) oluşturabilir.
- `vX.Y.Z`, `rollback-*` ve diğer her tag'i yalnızca `release-managers` oluşturabilir.
- Hiçbir tag güncellenemez veya silinemez.
- Jenkins kullanıcısı hiçbir bypass listesinde değildir.

### 1.5 Webhook

GitHub Branch Source plugin'i webhook'u kendisi yönetebilir (Manage Jenkins → System → GitHub → Manage hooks).
Elle kuracaksanız:
- Payload URL: `https://<jenkins>/github-webhook/`
- İçerik türü: `application/json`
- Olaylar: **Pushes** (tag'ler dahil) ve **Pull requests**

## 2. Jenkins

### 2.1 Plugin'ler

`github-branch-source`, `basic-branch-build-strategies`, `credentials-binding`, `ssh-credentials`,
`lockable-resources`, `workflow-aggregator`, `git`, `junit`

- **Lockable Resources** aynı ortama iki tag build'inin aynı anda deploy etmesini engeller. Örneğin bir prod tag'i
  ile bir rollback tag'i aynı anda gelirse ikincisi bekler.
- Jenkins agent'ında (veya controller'da) `git`, `curl`, `ssh`, `docker` CLI ve `docker buildx` bulunmalı.

### 2.2 Credential'lar

Bunları multibranch job'ın folder'ında tanımlayın, global değil:

| ID | Tür | İçerik |
|---|---|---|
| `github-jenkins` | Username with password | Username: Jenkins machine user'ı, Password: PAT |
| `deploy-ssh-devtest` | SSH Username with private key | dev/test sunucusundaki deploy kullanıcısı + private key |
| `deploy-ssh-prod` | SSH Username with private key | prod sunucusundaki deploy kullanıcısı + **ayrı** private key |

`github-jenkins` şu işlerin hepsinde kullanılır: branch/tag taraması, ghcr.io login ve image push'u, commit
status'ları, Release API'si.

### 2.3 Global Pipeline Library (Manage Jenkins → System → Global Trusted Pipeline Libraries)

- Name: `devops-lib`, Default version: `v3`
- Allow default version to be overridden: ✅ (Jenkinsfile'larda `@Library('devops-lib@v3')`)
- Retrieval method: Modern SCM → GitHub → `devops-shared-lib`

### 2.4 Multibranch Pipeline job'ı

**Branch Sources → GitHub**: Credentials `github-jenkins`, repo URL.

**Behaviors**:

| Behavior | Ayar |
|---|---|
| Discover branches | Strategy: All branches |
| Discover tags | — |
| Discover pull requests from origin | (PR'da build + test isteniyorsa) Strategy: Merging the pull request with the current target branch revision |
| Filter by name (with regular expression) | `^(dev\|test\|main\|PR-\d+\|v\d+\.\d+\.\d+(-dev\|-test)?\|rollback-\d+\.\d+\.\d+-.+)$` |
| Advanced clone behaviours | Fetch tags ✅, Shallow clone ⬜ |

**Build strategies**:
- **Tags** → Ignore tags older than: `1` (gün)
- (PR'da build + test isteniyorsa) **Change requests**
- **Regular branches eklemeyin.** Branch push'ları ve merge'ler hiçbir şey tetiklemez. Branch job'ları yine görünür,
  ama yalnızca elle "Build Now" ile çalışırlar ve o zaman da sadece build + test yaparlar.

İsim filtresi tetiklenecek tag'leri sınırlar: `v1.2.3-dev`, `v1.2.3-test`, `v1.2.3`, `rollback-1.2.3-...`. Pipeline da
ayrıca bu desenlere tam uymayan tag'leri NOT_BUILT olarak geçer, örneğin `v01.2.3`.

**Scan Repository Triggers**: Periodically if not otherwise run → 1 gün. Webhook kaçarsa bu yedek olur; 1 günden eski
tag'ler yine de atlanır.

> "Ignore tags older than" kuralı annotated tag'de tag'in oluşturulma tarihine, lightweight tag'de commit tarihine
> bakar. Test ve prod tag'leri günler önceki bir commit'e atıldığı için **annotated olmak zorundadır**. Pipeline
> lightweight tag'leri reddeder.

### 2.5 Başarısız bir tag build'ini yeniden çalıştırmak

Developer'lar tag'i tekrar atamaz (tag'ler silinemez). Deploy hatası, SSH kesintisi ya da Release API hatası gibi
**geçici** bir sebeple düşen tag build'ini bir Jenkins yöneticisi ilgili tag job'ında **Build Now** ile yeniden
çalıştırır. Pipeline kaldığı yerden devam eder:
- Ortamın image'ı (`1.0.5-dev` / `1.0.5-test` / `1.0.5`) zaten varsa ve label'ındaki commit
  (`org.opencontainers.image.revision`) bu commit'se build atlanır, deploy yapılır.
- **Durduğu durumlar:** sürüm o ortama zaten başarıyla çıktıysa (`release/<sürüm>/<ortam>` = success veya prod
  Release'i varsa) ya da image başka bir commit'ten build edilmişse. Hiçbir şeyin üzerine yazılmaz.

**Kontrol** hatalarında yeniden çalıştırmak işe yaramaz (yanlış commit, merge edilmemiş commit, eksik önceki aşama);
developer yeni bir sürümle başlar.

## 3. Sunucular (Ubuntu)

dev ve test aynı makinede, prod ayrı bir makinede çalışır.

```bash
# Her iki sunucuda
sudo adduser --disabled-password deploy && sudo usermod -aG docker deploy
# Jenkins'teki private key'in public karşılığı (devtest ve prod için farklı anahtarlar)
sudo -u deploy sh -c 'mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys' < jenkins_deploy_devtest.pub
# Image'ları çekebilmek için: yalnızca read:packages yetkili ayrı bir token (Jenkins'in yazma token'ı değil)
sudo -u deploy sh -c 'echo "$READ_TOKEN" | docker login ghcr.io -u <kullanıcı> --password-stdin'

# devtest:  /opt/usermanagement/dev ve /opt/usermanagement/test
# prod:     /opt/usermanagement/prod
sudo install -d -o deploy -g deploy /opt/usermanagement/dev /opt/usermanagement/test
```

Jenkinsfile'daki `knownHosts` değeri, `ssh-keyscan -t ed25519 <host>` çıktısıdır. Bunu bir kez ve güvenilir bir
ağdan alın.

Gereksinimler: Docker Compose v2 (`--wait` için ≥ 2.1.1) ve curl ≥ 7.71 (`--retry-all-errors` için).

Her ortam dizininde bir compose dosyası ve `.env` bulunur; bu dosyalar repoda değil sunucuda durur. Servisin image
satırı sürümü `IMAGE_TAG`'den almalıdır. Jenkins deploy ederken `IMAGE_TAG`'i kendisi verir, başarılı deploy'dan sonra
da `.env`'e yazar. Böylece sunucuda elle çalıştırılan `docker compose up` da aynı sürümü kullanır.

```yaml
# /opt/usermanagement/prod/compose.yaml
services:
  api:
    image: ghcr.io/furkan-demircan/usermanagement-api:${IMAGE_TAG:?IMAGE_TAG tanımlı değil}
    restart: unless-stopped
    ports:
      - "5000:8080"            # dev: 5001, test: 5002 (Jenkinsfile'daki healthUrl ile aynı)
    environment:
      ConnectionStrings__DefaultConnection: Host=postgres;Database=usermanagement;Username=app;Password=${POSTGRES_PASSWORD:?}
      DeepSeek__ApiKey: ${DEEPSEEK_API_KEY:-}
    depends_on:
      postgres:
        condition: service_healthy
  postgres:
    image: postgres:16-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: usermanagement
      POSTGRES_USER: app
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d usermanagement"]
      interval: 5s
      retries: 10
volumes:
  pgdata:
```

Jenkins deploy sırasında yalnızca `api` servisini günceller (`--no-deps`), veritabanına dokunmaz. Deploy sonunda
çalışan container'ın image'ının beklenen `<image>:<tag>` olduğunu doğrular. Compose dosyası `${IMAGE_TAG}`
kullanmıyorsa deploy başarısız sayılır.

**Prod'da şu an hangi sürüm çalışıyor?** Jenkins bunu prod sunucusundan okur: `docker compose ps -q api` →
`docker inspect --format '{{.Config.Image}}'` → `ghcr.io/...:1.0.5`. Elle yapılmış bir değişiklik ya da önceki bir
rollback olsa bile gerçekte çalışan sürümü verir. Bu değer iki yerde kullanılır: rollback'te hangi Release'in
işaretleneceğini belirler ve yeni Release'teki "Önceki prod sürümü" satırına yazılır.

## 4. GitHub API çağrıları (curl)

Jenkins bunları `githubApi.request` ile yapar. Aşağıdakiler, elle müdahale gerektiğinde çalıştırılabilecek aynı
çağrılardır. `jq` gerekir.

```bash
export GH_TOKEN=<PAT>  REPO=org/repo  API=https://api.github.com
gh_api() { curl -fsS -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" \
                -H "X-GitHub-Api-Version: 2022-11-28" "$@"; }
```

**Prod Release'i oluşturmak (v1.0.5):**

```bash
TAG=v1.0.5  VERSION=1.0.5  IMAGE=ghcr.io/furkan-demircan/usermanagement-api
# Notların başlangıcı: tag'li commit'in öncesindeki en yakın prod tag'i (içinde '-' olmayan vX.Y.Z)
PREV=$(git describe --tags --abbrev=0 --first-parent --match 'v[0-9]*' --exclude '*-*' "$TAG^")

# 1) Otomatik release notları (önceki prod tag'inden bu yana merge edilen PR'lar)
NOTES=$(gh_api -X POST "$API/repos/$REPO/releases/generate-notes" \
  -d "$(jq -n --arg t "$TAG" --arg p "$PREV" '{tag_name: $t} + (if $p != "" then {previous_tag_name: $p} else {} end)')" \
  | jq -r .body)

# 2) Release: image adı ve deploy tarihi notun başında
BODY="**Image:** \`$IMAGE:$VERSION\`
**Prod'a deploy:** $(date -u '+%Y-%m-%d %H:%M UTC')

$NOTES"
gh_api -X POST "$API/repos/$REPO/releases" \
  -d "$(jq -n --arg t "$TAG" --arg b "$BODY" \
        '{tag_name: $t, name: $t, body: $b, draft: false, prerelease: false, make_latest: "true"}')"
```

`generate_release_notes: true` parametresi tek başına da kullanılabilir. Ama o zaman notların başlangıcını GitHub
"son release"e göre seçer; rollback ile pre-release'e çevrilmiş bir sürüm araya girince yanlış aralık çıkabilir. Bu
yüzden önce `generate-notes` çağrısı `previous_tag_name` ile yapılır ve gelen not Release body'sine eklenir.

**Rollback sonrası, geri alınan Release'i işaretlemek (1.0.5 → 1.0.4):**

```bash
FROM=v1.0.5  TO=v1.0.4  ROLLBACK_TAG=rollback-1.0.4-20261019-1
REL=$(gh_api "$API/repos/$REPO/releases/tags/$FROM")
ID=$(jq -r .id <<< "$REL")
BODY="$(jq -r '.body // ""' <<< "$REL")

---
⚠️ **Geri alındı:** $(date -u '+%Y-%m-%d %H:%M UTC'), \`${TO#v}\`'e dönüldü (tag: \`$ROLLBACK_TAG\`)"
gh_api -X PATCH "$API/repos/$REPO/releases/$ID" \
  -d "$(jq -n --arg b "$BODY" '{prerelease: true, body: $b}')"

# Dönülen sürümün Release'i yeniden "Latest" olsun
TO_ID=$(gh_api "$API/repos/$REPO/releases/tags/$TO" | jq -r .id)
gh_api -X PATCH "$API/repos/$REPO/releases/$TO_ID" -d '{"make_latest": "true"}'
```

**Bir commit'in aşama status'larını görmek:**

```bash
gh_api "$API/repos/$REPO/commits/$(git rev-parse 'v1.0.5-dev^{}')/status" \
  | jq -r '.statuses[] | select(.context | startswith("release/")) | "\(.context)\t\(.state)\t\(.description)"'
```

## 5. Riskler ve eksikler

1. **Veritabanı şeması rollback ile geri gelmez.** Rollback yalnızca image'ı değiştirir.
   - McpTest şu an migration yerine `DatabaseInitializer` kullanıyor: `EnsureCreated` + eksik tabloları ekleme. Bu
     yöntem kolon ekleyip değiştirmediği için yeni bir sürümün şema değişikliği hiç uygulanmayabilir.
   - Gerçek migration'lara geçildiğinde ise tersi olur: geri alınan sürümün şeması yerinde kalır.

   Öneriler:
   - EF Core migration'larına geçin ve migration'ları **expand/contract** yapın: bir sürümde kolonu ekleyin, eskisini
     ancak bir sonraki sürümde kaldırın. Böylece N-1 sürümü N'in şemasıyla çalışabilir.
   - Prod deploy'undan önce otomatik yedek alın.
   - Geriye uyumsuz migration içeren sürümü Release notunda "rollback edilemez" diye işaretleyin.
2. **Sürüm numarası ortamları yalnızca adıyla bağlar.** Branch'ler bağımsız olduğu için test'teki 1.0.5 ile
   prod'daki 1.0.5 farklı kod olabilir; örneğin yalnızca `main`'e merge edilmiş bir feature prod'a test'te hiç
   denenmeden çıkar. Jenkins bunu engellemez: `git diff --stat <v1.0.5-test> <v1.0.5>` çıktısını loga ve Release
   notuna yazar. Engellemek istenirse `releaseSteps.checkRelease`'teki uyarı `error`'a çevrilebilir. Ama bağımsız
   branch modelinde bu, prod'a çıkışı çoğu zaman tıkar.
3. **Image her ortamda yeniden build edilir.** Aynı kod, farklı base image veya paket sürümüyle farklı bir image
   üretebilir. `--locked-mode`'u koruyun, base image'ları digest ile sabitleyin.
4. **Branch'ler zamanla ayrışabilir (drift).** Bir feature'ın hangi ortamlara merge edildiğini pipeline takip etmez.
   "Prod'a çıkan her feature önce test'e merge edilir" kuralını PR şablonu, kontrol listesi veya review ile
   uygulayın.
5. **Yanlış sırada atılan tag sürümü yakar.** Tag merge'den önce atılırsa ya da yanlış commit'e atılırsa Jenkins
   durur. Tag silinemediği için o numara bir daha kullanılamaz ve developer yeni numarayla dev'den başlar. Aynı
   şekilde test ve prod tag'leri art arda hızlıca atılırsa, prod build'i test deploy'u bitmeden
   `release/<sürüm>/test` kontrolünde düşer. "Merge et, sonucu bekle, sonra tag'le" sırası önemlidir.
6. **Jenkinsfile repoda duruyor.** Repo'ya yazabilen biri bir PR'da veya branch'te Jenkinsfile'ı değiştirip
   `github-jenkins` ya da `deploy-ssh-prod` credential'ını kendi komutlarıyla kullanabilir. Origin'den açılan PR'lar
   güvenilir sayılır.

   Öneriler:
   - Jenkinsfile'ı developer'ların değiştiremediği bir yerden okutun ("Remote Jenkinsfile Provider" veya
     "Pipeline: Multibranch with defaults" plugin'i).
   - Prod credential'larını ayrı bir job'a taşıyın.
   - En azından `Jenkinsfile` için `CODEOWNERS` ve zorunlu review koyun.

   Not: tag build'i, tag'lenen commit'teki Jenkinsfile'ı çalıştırır.
7. **Commit status'ları kanıt değildir.** `release/1.0.5/test` status'unu repo'ya yazma yetkisi olan herkes API ile
   elle yazabilir; prod kapısı bu status'a güvenir. Daha güçlü bir kapı için status'lar yerine GitHub
   Deployments/Environments (ortam başına koruma kuralı) kullanılabilir.
8. **Image tag'leri registry'de değişmez değil.** GHCR immutable tag desteklemez, pakete yazabilen biri `1.0.5`'in
   üzerine yazabilir.
   - Paketin yazma yetkisini yalnızca Jenkins kullanıcısına verin.
   - Release notunda image digest'i yer alır; ileride sunucularda tag yerine digest (`image@sha256:...`) ile deploy
     edilebilir.
9. **Yarım kalan deploy.** Sağlık kontrolü başarısız olursa yeni container çalışır durumda kalır, ama status
   `failure` olur, Release oluşmaz ve `.env` eski sürümde kalır. Otomatik geri dönüş yoktur: ya Build Now ile yeniden
   denenir ya da prod'daysa rollback tag'iyle önceki sürüme dönülür. İyileştirme: deploy başarısız olunca önceki
   sürümü otomatik yeniden deploy etmek.
10. **Rollback ve tag yetkileri yalnızca GitHub'da kontrol edilir.** Git tag'inin "tagger" alanı doğrulanmış bir kimlik
   değildir ve Jenkins tag'i kimin push ettiğini bilemez. Koruma tamamen tag ruleset'lerine bağlıdır.
11. **Jenkins'in kapalı kalması.** "Ignore tags older than 1 day" yüzünden Jenkins bir günden uzun kapalı kalırsa o
   sürede atılan tag'ler otomatik build edilmez. Jenkins'ten elle Build Now gerekir.
12. **Sürüm sırası zorlanmaz.** 1.0.5 prod'dayken birisi `v1.0.3-dev` atabilir (1.0.3 hiç kullanılmadıysa). SemVer'e
   göre geriye gitmek engellenmek isteniyorsa ek bir kontrol eklenmeli.
13. **Prod'a çıkan kod `main`'in ucu değildir.** `main`'e tag'lenen commit'ten sonra başka commit'ler de merge
    edilmiş olabilir; prod'da yalnızca tag'li commit çalışır. Bu tasarım gereğidir, ama `main`'in ucunun "prod'daki
    kod" olduğunu varsayan araçlar yanılır. Prod kodu için `v*` tag'lerine (veya Latest Release'e) bakılmalı.
14. **Sunucu yapılandırması versiyonlanmıyor.** Compose dosyası ve `.env` sunucuda elle yönetilir; eksik bir ortam
    değişkeni ancak deploy sırasında ortaya çıkar. Bunları ayrı, erişimi kısıtlı bir "infra" reposunda tutmak
    önerilir.
15. **`docker` grubu root'a eşdeğerdir.** Prod SSH anahtarına erişen, prod sunucusunda root yetkisi elde eder.
    Anahtarı ayrı tutun ve kapsamını daraltın (bkz. madde 6).
16. **Classic PAT geniş yetkilidir.** `repo` yetkisi, machine user'ın erişebildiği tüm repolarda geçerlidir. Machine
    user'ı yalnızca gerekli repolara ekleyin.
17. **dev ve test aynı makinede.** Test ortamındaki yük testi veya yüksek bellek tüketimi dev'i etkiler (ya da tersi).
