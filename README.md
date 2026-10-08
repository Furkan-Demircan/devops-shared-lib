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