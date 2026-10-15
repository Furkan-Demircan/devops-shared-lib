# devops-shared-lib

Jenkins Shared Library: projelerin ortak CI/CD pipeline'ı. Bildirimler ve prod onayı GitHub üzerinden.

```
devops-shared-lib/
├── vars/
│   ├── githubApi.groovy         # GitHub REST API yardımcıları
│   ├── notifyGitHub.groovy      # Commit status + @mention'lı commit yorumu
│   └── standardPipeline.groovy  # stage: Build → Test → Stage | main: Build → Test → Kontrol → Prod
└── example/
    └── Jenkinsfile              # Bir projede kullanım örneği
```

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
