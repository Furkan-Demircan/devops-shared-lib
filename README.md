# devops-shared-lib

Jenkins Shared Library: projelerin ortak CI/CD pipeline'ları. Bildirimler ve onaylar GitHub üzerinden.

```
devops-shared-lib/
├── vars/
│   ├── releasePipeline.groovy   # (v3) Tag tabanlı deploy: dev, test, prod ortam branch'leri
│   ├── releaseSteps.groovy      # releasePipeline'ın kontrolleri, build, deploy ve Release adımları
│   ├── githubApi.groovy         # GitHub REST API yardımcıları
│   ├── notifyGitHub.groovy      # Commit status + @mention'lı commit yorumu
│   └── standardPipeline.groovy  # (v2) stage: Build → Test → Stage | main: Build → Test → Kontrol → Prod
├── resources/devops/release/
│   ├── common.sh                # git / registry / ssh shell yardımcıları
│   └── remote.sh                # Deploy sunucusunda çalışan kısım (docker compose)
├── docs/
│   ├── release-setup.md         # GitHub, Jenkins, sunucu kurulumu, API çağrıları, riskler
│   └── developer-guide.md       # Developer'lar için sürüm çıkarma kılavuzu
└── example/
    ├── Jenkinsfile              # standardPipeline örneği
    └── Jenkinsfile.release      # releasePipeline örneği
```

## releasePipeline (v3)

Deploy'ları yalnızca tag'ler tetikler; branch push'ları ve merge'ler deploy etmez. Ortam branch'leri (`dev`, `test`,
`main`) bağımsızdır: feature'lar her birine ayrı PR ile merge edilir. Merge'den sonra **o branch'in ucu** tag'lenir.
Tag'leri developer/yetkili kişi atar, Jenkins tag oluşturmaz. Her tag kendi commit'inden image build eder.

| Tag (kim atar, nereye) | Jenkins ne yapar |
|---|---|
| `v1.0.5-dev` (developer, `dev`'in ucu) | Build → Test → image `1.0.5-dev` push → dev'e deploy |
| `v1.0.5-test` (`testers`, `test`'in ucu) | Build → Test → image `1.0.5-test` push → test'e deploy |
| `v1.0.5` (`release-managers`, `main`'in ucu) | Build → Test → image `1.0.5` push → prod'a deploy → GitHub Release |
| `rollback-1.0.4-20261019-1` (`release-managers`) | Image `1.0.4`'ü prod'a yeniden deploy (build yok) → geri alınan sürümün Release'i pre-release + not |
| PR'lar | Build → Test → image build doğrulaması (push/deploy yok) |
| Diğer tag'ler | Hiçbir şey (NOT_BUILT) |

Her başarılı deploy, tag'lenen commit'e `release/<sürüm>/<ortam> = success` status'u yazar. Sıradaki ortam bu
status'u, önceki ortamın tag'inin commit'inde şart koşar.

Durduran kontroller:
- Tag annotated olmalı. Commit, ortam branch'inin kendi (first-parent) geçmişinde olmalı:
  `git rev-list --first-parent origin/<dev|test|main>`. Feature commit'ine atılan tag reddedilir.
- `v1.0.5-test` için `v1.0.5-dev`, `v1.0.5` için `v1.0.5-test` var olmalı ve o ortama başarıyla çıkmış olmalı.
- Ortamın image'ı zaten varsa durur. Tek istisna Rebuild: image'ın revision label'ı bu commit'i gösteriyorsa build
  atlanıp deploy'dan devam edilir. Sürüm o ortama zaten çıktıysa (status success veya prod Release'i varsa) her
  durumda durur. Hiçbir şeyin üzerine yazılmaz.
- Rollback'te hedef sürümün GitHub Release'i (yani başarılı bir prod deploy'u) ve image'ı olmalı. Rollback tag'i
  `v<sürüm>`'ün commit'inde olmalı ve prod'da zaten o sürüm çalışmıyor olmalı.

Prod'da `main`'in içeriği `v1.0.5-test`'in commit'inden farklıysa build durmaz, ama fark listesi loga ve Release
notuna yazılır. Prod'da çalışan sürüm, prod sunucusundaki container'dan okunur (`docker inspect`). Deploy SSH +
docker compose ile yapılır.

```groovy
@Library('devops-lib@v3') _

releasePipeline(
    owner        : 'ayse-github',
    image        : 'ghcr.io/org/app',
    dockerfile   : 'src/Api/Dockerfile',
    buildCmd     : 'make build',
    testCmd      : 'make test',
    environments : [
        dev : [host: 'devtest.internal', dir: '/opt/app/dev',  service: 'api', sshCredentialsId: 'deploy-ssh-devtest', knownHosts: '...'],
        test: [host: 'devtest.internal', dir: '/opt/app/test', service: 'api', sshCredentialsId: 'deploy-ssh-devtest', knownHosts: '...'],
        prod: [host: 'prod.internal',    dir: '/opt/app/prod', service: 'api', sshCredentialsId: 'deploy-ssh-prod',    knownHosts: '...']
    ]
)
```

Tüm parametreler [vars/releasePipeline.groovy](vars/releasePipeline.groovy) başındadır. Kurulum ve riskler:
[docs/release-setup.md](docs/release-setup.md). Developer'lar için: [docs/developer-guide.md](docs/developer-guide.md).

v3'e geçerken `standardPipeline` değişmedi; onu kullanan projeler aynen çalışır. Tek fark: `githubApi` ve
`notifyGitHub` artık `credentialsType: 'usernamePassword'` opsiyonunu da kabul eder.

---

# standardPipeline (v2)

## Akış

1. Geliştirici feature branch'ini `stage`'e merge eder → Jenkins build + test + stage deploy yapar.
2. Başarılıysa commit'e `jenkins/stage = success` işareti ve proje sahibini etiketleyen yorum düşer.
3. Prod'a çıkmak isteyen kişi (proje sorumlusu dahil) `stage` → `main` Pull Request'i açar.
4. **Prod onayı, PR'ı proje sorumlusunun merge etmesidir.** Merge'den sonra Jenkins `main` build'inde
   şunları doğrular, hepsi tamamsa prod'a deploy eder:
   - Commit `main`'e `stage`'den açılmış, merge edilmiş bir PR ile gelmiş (doğrudan push → deploy reddedilir)
   - PR'ı merge eden kişi `prodMergers` listesinde (varsayılan: `owner`)
   - PR'ın son commit'i stage'e başarıyla çıkmış (`jenkins/stage = success`)
   - `main`'in içeriği stage'de denenen commit'le birebir aynı (tree SHA eşleşmesi)

`deployProdCmd` içinde stage'de build edilen image'ı **`$STAGE_COMMIT`** ile bulun, `$GIT_COMMIT` ile değil:
`main`'deki commit PR'ın merge commit'idir ve onun için build yapılmamıştır. `$PROD_PR_NUMBER` ve
`$PROD_MERGER` de kullanılabilir (eski akıştaki `$TAG_NAME` artık yok).

Review onayı aranmaz; sorumlu kişi kendi açtığı PR'ı da merge ederek prod'a çıkarabilir.
Birden fazla sorumlu için: `prodMergers: ['ayse-github', 'mehmet-github']`.

## GitHub kurulum notları

`main` için branch protection (veya ruleset):

- **Require a pull request before merging** (onay sayısı 0) — doğrudan push kapansın, yalnızca PR ile merge.
- **Require status checks to pass** → `jenkins/ci` (PR build'i) zorunlu olsun.
- **Restrict who can push / merge** → yalnızca proje sorumlusu(ları). Bu ayar yalnızca **organizasyon**
  repo'larında var (ruleset bypass listesi veya *Restrict who can push to matching branches*). Kişisel
  repo'larda yazma yetkisi olan herkes merge edebilir; bu durumda merge'ü engelleyemezsiniz ama Jenkins
  yetkisiz kişinin merge'ünü prod'a çıkarmaz (`main` stage'den ileride kalır, yetkili biri tekrar çıkarmalı).
- **Do not allow bypassing the above settings** — admin'ler de kuralı atlayamasın.
- Merge yöntemi olarak **Create a merge commit** önerilir. Squash/rebase de çalışır ama `main`'de `stage`'de
  olmayan commit'ler birikir ve sonraki `stage` → `main` PR'larında çakışma çıkabilir.

`stage` için de PR zorunluluğu koymanız önerilir (stage'e ne çıktığı izlenebilir olsun).

## Jenkins kurulum notları

- Multibranch job'da "Discover branches" ve (PR kontrolü için) "Discover pull requests from origin" açık olmalı.
- **Library'yi sürüme sabitleyin:** `@Library('devops-lib@v1') _`. Bu repo'da değişiklikleri tag'leyip (`v1`, `v1.1` …) yayınlayın.
- **Prod credential'larını ayırın:** `stageCredentials` / `prodCredentials` kullanın. Jenkinsfile proje repo'sunda
  olduğundan herhangi bir branch'teki Jenkinsfile credential'ı kendisi bağlayabilir; prod credential'larını yalnızca
  `main` build'inin erişebildiği bir kapsamda (ayrı folder/job) tutmak gerçek korumayı sağlar.
- `jenkins/stage` status'u bir işarettir, kanıt değildir: repo'da yazma yetkisi olan biri API ile elle yazabilir.
- Test raporu bulunamazsa build düşer; raporunuz farklı bir yerdeyse `testReports` verin
  (örn. Maven: `**/target/surefire-reports/*.xml`).
- Branch adları farklıysa: `stageBranch: 'develop'`, `mainBranch: 'master'`.
- GitHub Enterprise: `githubApiUrl: 'https://git.sirket.com/api/v3'`, farklı token için `githubCredentialsId`.
  Token'ın repo'da PR ve review okuma yetkisi olmalı.
