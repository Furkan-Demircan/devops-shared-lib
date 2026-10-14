# devops-shared-lib

Jenkins Shared Library: projelerin ortak CI/CD pipeline'ı. Bildirimler ve prod onayı GitHub üzerinden.

```
devops-shared-lib/
├── vars/
│   ├── githubApi.groovy         # GitHub REST API yardımcıları
│   ├── notifyGitHub.groovy      # Commit status + @mention'lı commit yorumu
│   └── standardPipeline.groovy  # main: Build → Test → Stage | v* tag: Build → Test → Kontrol → Prod
└── example/
    └── Jenkinsfile              # Bir projede kullanım örneği
```

## Akış

1. Geliştirici `main`'e push'lar → Jenkins build + test + stage deploy yapar.
2. Başarılıysa commit'e `jenkins/stage = success` işareti ve proje sahibini etiketleyen yorum düşer.
3. Proje sahibi GitHub'da o commit için Release oluşturur (örn. `v1.4.0`).
4. Jenkins tag'i algılar, commit'in stage'den geçtiğini doğrular ve prod'a deploy eder.

Kim tag oluşturabilir? → GitHub'daki tag ruleset belirler.

## Jenkins kurulum notları

- **Tag build'leri otomatik tetiklenmez.** Multibranch job'da "Discover tags" yetmez;
  [Basic Branch Build Strategies](https://plugins.jenkins.io/basic-branch-build-strategies/) plugin'i ile
  *Build strategies* altına "Regular branches" + "Tags" eklenmeli (varsayılan olarak 7 günden eski tag'ler atlanır).
- **Library'yi sürüme sabitleyin:** `@Library('devops-lib@v1') _`. Bu repo'da değişiklikleri tag'leyip (`v1`, `v1.1` …) yayınlayın.
- **Prod credential'larını ayırın:** `stageCredentials` / `prodCredentials` kullanın. Jenkinsfile proje repo'sunda
  olduğundan herhangi bir branch'teki Jenkinsfile credential'ı kendisi bağlayabilir; prod credential'larını yalnızca
  tag build'lerinin erişebildiği bir kapsamda (ayrı folder/job) tutmak gerçek korumayı sağlar.
- `jenkins/stage` status'u bir işarettir, kanıt değildir: repo'da yazma yetkisi olan biri API ile elle yazabilir.
- Test raporu bulunamazsa build düşer; raporunuz farklı bir yerdeyse `testReports` verin
  (örn. Maven: `**/target/surefire-reports/*.xml`).
- GitHub Enterprise: `githubApiUrl: 'https://git.sirket.com/api/v3'`, farklı token için `githubCredentialsId`.