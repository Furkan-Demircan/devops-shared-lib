# devops-shared-lib

Jenkins Shared Library: 5 projenin ortak CI/CD pipeline'ı ve GitHub bildirimleri.

```
devops-shared-lib/
├── vars/
│   ├── notifyGitHub.groovy      # Commit status + @mention'lı commit yorumu
│   └── standardPipeline.groovy  # Build → Test → Stage → Onay → Prod
└── example/
    └── Jenkinsfile              # Bir projede kullanım örneği
```

Kurulum adımları için sohbetteki açıklamaya bakın.
