/**
 * Tag tabanlı deploy pipeline'ı. Deploy'ları yalnızca developer'ın / yetkili kişinin attığı tag'ler tetikler;
 * Jenkins hiç tag oluşturmaz, merge'ler deploy etmez. Ortam branch'leri (dev, test, main) bağımsızdır: feature'lar
 * her birine ayrı PR ile merge edilir, tag o ortamın merge'ünden sonra branch'in ucuna atılır.
 *
 *   v1.0.5-dev  (dev'in ucu)   -> Build -> Test -> image 1.0.5-dev  -> dev deploy
 *   v1.0.5-test (test'in ucu)  -> Build -> Test -> image 1.0.5-test -> test deploy  (1.0.5 dev'e çıkmış olmalı)
 *   v1.0.5      (main'in ucu)  -> Build -> Test -> image 1.0.5      -> prod deploy + GitHub Release
 *                                                                      (1.0.5 test'e çıkmış olmalı)
 *   rollback-1.0.4-...         -> 1.0.4'ü prod'a yeniden deploy (build yok) -> Release'i işaretle
 *   PR'lar                     -> Build -> Test -> image build doğrulaması (deploy yok)
 *   diğer tag'ler              -> hiçbir şey yapılmaz (NOT_BUILT)
 *
 * Her başarılı deploy commit'e release/<sürüm>/<ortam> = success status'u yazar. test bunu dev için, prod test için
 * şart koşar: tag'in varlığı tek başına o ortama başarıyla çıkıldığını göstermez.
 *
 * Zorunlu parametreler:
 *   owner        : Proje sahibinin GitHub kullanıcı adı (bildirimlerde etiketlenir)
 *   image        : Tag'siz image adı, küçük harf, ör. ghcr.io/org/app
 *   dockerfile   : Dockerfile yolu (repo köküne göre)
 *   buildCmd     : Build komutu
 *   environments : dev, test ve prod için deploy hedefi:
 *     host             : SSH ile bağlanılacak sunucu
 *     dir              : Sunucuda compose dosyası ve .env'in bulunduğu dizin
 *     service          : Compose servisinin adı
 *     sshCredentialsId : "SSH Username with private key" credential'ı
 *     knownHosts       : Sunucunun host key satırı/satırları (ssh-keyscan -t ed25519 <host>)
 *     healthUrl        : (Opsiyonel) Deploy sonrası sunucuda curl ile kontrol edilecek adres
 *
 * Opsiyonel parametreler:
 *   dockerContext         : Docker build context'i (varsayılan: .)
 *   testCmd               : Test komutu (verilmezse Test aşaması atlanır)
 *   testReports           : JUnit XML rapor yolu (varsayılan: **\/test-results/**\/*.xml)
 *   allowEmptyTestResults : true ise rapor bulunamaması hata sayılmaz (varsayılan: false)
 *   verifyImageOnCi       : PR build'lerinde image build'i denensin mi (varsayılan: true)
 *   devBranch, testBranch, mainBranch : Ortam branch'leri; tag'li commit ilgili branch'in first-parent
 *                           geçmişinde olmalı (varsayılan: dev, test, main)
 *   githubCredentialsId   : "Username with password" credential'ı: GitHub kullanıcısı + PAT
 *                           (repo, write:packages, read:packages). ghcr.io, status ve Release için
 *                           (varsayılan: github-jenkins)
 *   githubApiUrl          : GitHub Enterprise API adresi (varsayılan: https://api.github.com)
 *   agentLabel            : Jenkins agent label'ı (varsayılan: herhangi biri)
 *   timeoutMinutes        : Pipeline zaman aşımı, dakika (varsayılan: 60)
 *
 * Gerekli Jenkins plugin'leri: GitHub Branch Source, Basic Branch Build Strategies, Credentials Binding,
 * SSH Credentials, Lockable Resources. Kurulum için docs/release-setup.md.
 */
def call(Map cfg = [:]) {
    Map rc = releaseSteps.config(cfg)
    Map gh = rc.gh
    List tagModes = ['dev', 'test', 'prod', 'rollback']

    pipeline {
        agent { label rc.agentLabel }

        options {
            disableConcurrentBuilds()
            timeout(time: rc.timeoutMinutes, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '30'))
        }

        stages {
            stage('Hazırlık') {
                steps {
                    script {
                        env.RELEASE_MODE = releaseSteps.detectMode(rc)
                        echo "Mod: ${env.RELEASE_MODE} (branch/tag: ${env.BRANCH_NAME})"
                        if (env.RELEASE_MODE == 'ignore') {
                            currentBuild.result = 'NOT_BUILT'
                            currentBuild.description = 'Tag deseni tanınmadı, build edilmez'
                            return
                        }
                        if (!(env.RELEASE_MODE in tagModes)) {
                            notifyGitHub(gh + [state: 'pending', description: 'Build + test çalışıyor'])
                        }
                        releaseSteps.fetchRefs(rc)
                    }
                }
            }

            // ---------------------------------------------------------------- kontroller

            stage('Sürüm Kontrolü') {
                when { expression { env.RELEASE_MODE in tagModes } }
                steps {
                    script {
                        if (env.RELEASE_MODE == 'rollback') {
                            releaseSteps.checkRollback(rc)
                        } else {
                            releaseSteps.checkRelease(rc, env.RELEASE_MODE)
                        }
                        currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.TAG_NAME}"
                        // Status yalnızca kontroller geçtikten sonra yazılır: kontrolde düşen bir build (ör. zaten
                        // yayınlanmış sürümün Rebuild'i) önceki "success"in üzerine "failure" yazmasın
                        env.RELEASE_STATUS_OWNED = 'true'
                        releaseSteps.setStatus(rc, 'pending', "${env.TAG_NAME} çalışıyor")
                    }
                }
            }

            // ---------------------------------------------------------------- build (tag'ler ve PR'lar)

            stage('Build') {
                when {
                    anyOf {
                        expression { env.RELEASE_MODE in ['ci', 'pr'] }
                        expression { env.RELEASE_MODE in ['dev', 'test', 'prod'] && env.IMAGE_READY != 'true' }
                    }
                }
                steps {
                    sh rc.buildCmd
                }
            }

            stage('Test') {
                when {
                    allOf {
                        expression { !!rc.testCmd }
                        anyOf {
                            expression { env.RELEASE_MODE in ['ci', 'pr'] }
                            expression { env.RELEASE_MODE in ['dev', 'test', 'prod'] && env.IMAGE_READY != 'true' }
                        }
                    }
                }
                steps {
                    sh rc.testCmd
                }
                post {
                    always {
                        junit allowEmptyResults: rc.allowEmpty, testResults: rc.testReports
                    }
                }
            }

            stage('Image') {
                when {
                    anyOf {
                        expression { env.RELEASE_MODE in ['dev', 'test', 'prod'] }
                        expression { env.RELEASE_MODE in ['ci', 'pr'] && rc.verifyImageOnCi }
                    }
                }
                steps {
                    script {
                        boolean push = env.RELEASE_MODE in ['dev', 'test', 'prod']
                        // UNSTABLE (başarısız test raporu) build'ler image push etmez
                        if (push && currentBuild.currentResult != 'SUCCESS') {
                            error "Testler başarısız; ${rc.image}:${env.TARGET_IMAGE_TAG} push edilmedi."
                        }
                        releaseSteps.buildImage(rc, push)
                    }
                }
            }

            // ---------------------------------------------------------------- deploy

            stage('Deploy Dev') {
                when { expression { env.RELEASE_MODE == 'dev' } }
                steps {
                    script {
                        releaseSteps.deploy(rc, 'dev')
                    }
                }
            }

            stage('Deploy Test') {
                when { expression { env.RELEASE_MODE == 'test' } }
                steps {
                    script {
                        releaseSteps.deploy(rc, 'test')
                    }
                }
            }

            stage('Deploy Prod') {
                when { expression { env.RELEASE_MODE == 'prod' } }
                steps {
                    script {
                        env.PREVIOUS_PROD_VERSION = releaseSteps.deploy(rc, 'prod')
                    }
                }
            }

            stage('Rollback Prod') {
                when { expression { env.RELEASE_MODE == 'rollback' } }
                steps {
                    script {
                        env.PREVIOUS_PROD_VERSION = releaseSteps.deploy(rc, 'prod', true)
                    }
                }
            }

            // ---------------------------------------------------------------- GitHub Release

            stage('GitHub Release') {
                when { expression { env.RELEASE_MODE == 'prod' } }
                steps {
                    script {
                        env.RELEASE_URL = releaseSteps.createRelease(rc, env.PREVIOUS_PROD_VERSION)
                    }
                }
            }

            stage('Release Güncelle') {
                when { expression { env.RELEASE_MODE == 'rollback' } }
                steps {
                    script {
                        releaseSteps.markRolledBack(rc, env.PREVIOUS_PROD_VERSION, env.RELEASE_VERSION)
                    }
                }
            }
        }

        post {
            success {
                script {
                    if (env.RELEASE_MODE in tagModes) {
                        notifyGitHub(gh + [state: 'success', context: env.RELEASE_CONTEXT, description: successText(),
                                           owner: rc.owner, comment: true, message: successComment(rc)])
                    } else {
                        notifyGitHub(gh + [state: 'success', description: 'Build + test başarılı'])
                    }
                }
            }
            unstable {
                script {
                    if (env.RELEASE_MODE == 'rollback') {
                        // Deploy yapıldı, yalnızca Release işaretlenemedi
                        notifyGitHub(gh + [state: 'success', context: env.RELEASE_CONTEXT, owner: rc.owner, comment: true,
                                           description: 'Rollback yapıldı, Release işaretlenemedi'])
                    } else if (env.RELEASE_MODE in tagModes) {
                        tagFailure(rc, gh, 'Testler başarısız, deploy yapılmadı')
                    } else {
                        notifyGitHub(gh + [state: 'failure', description: 'Testler başarısız'])
                    }
                }
            }
            failure {
                script {
                    if (env.RELEASE_MODE in tagModes) {
                        tagFailure(rc, gh, 'Başarısız, detay için tıklayın')
                    } else {
                        notifyGitHub(gh + [state: 'failure', description: 'Pipeline başarısız, detay için tıklayın'])
                    }
                }
            }
            aborted {
                script {
                    if (env.RELEASE_MODE in tagModes) {
                        tagFailure(rc, gh, 'İptal edildi')
                    } else {
                        notifyGitHub(gh + [state: 'error', description: 'Pipeline iptal edildi'])
                    }
                }
            }
        }
    }
}

/**
 * Tag build'i başarısız: owner'a yorum yazılır. Kontroller geçtiyse release/<sürüm>/<ortam> failure olur;
 * geçmediyse (ör. zaten yayınlanmış sürümün Rebuild'i) o status'a dokunulmaz, ayrı 'jenkins/tag' status'u kullanılır.
 */
private void tagFailure(Map rc, Map gh, String description) {
    String context = env.RELEASE_STATUS_OWNED == 'true' ? env.RELEASE_CONTEXT : 'jenkins/tag'
    notifyGitHub(gh + [state: 'failure', context: context, description: "${env.TAG_NAME}: ${description}",
                       owner: rc.owner, comment: true])
}

private String successText() {
    switch (env.RELEASE_MODE) {
        case 'dev':      return "${env.RELEASE_VERSION} dev'de"
        case 'test':     return "${env.RELEASE_VERSION} test'te"
        case 'prod':     return "${env.RELEASE_VERSION} production'da"
        case 'rollback': return "Prod ${env.RELEASE_VERSION} sürümüne geri alındı"
        default:         return 'Pipeline başarılı'
    }
}

/** Owner'ı etiketleyen commit yorumu: ne oldu ve sıradaki adım. */
private String successComment(Map rc) {
    String v = env.RELEASE_VERSION
    String text
    switch (env.RELEASE_MODE) {
        case 'dev':
            text = "🚀 `${rc.image}:${v}-dev` dev ortamında.\n\n" +
                   "Test'e çıkarmak için: feature'ı `${rc.branches.test}`'e merge edin, sonra `${rc.branches.test}`'in ucuna `v${v}-test` atın."
            break
        case 'test':
            text = "🧪 `${rc.image}:${v}-test` test ortamında.\n\n" +
                   "Prod'a çıkarmak için: feature'ı `${rc.branches.prod}`'e merge edin, sonra `${rc.branches.prod}`'in ucuna `v${v}` atın."
            break
        case 'prod':
            text = "✅ `${v}` production'da (önceki: `${env.PREVIOUS_PROD_VERSION ?: 'bilinmiyor'}`).\n\nRelease: ${env.RELEASE_URL}"
            break
        default:
            text = "⏪ Prod `${env.PREVIOUS_PROD_VERSION ?: 'bilinmiyor'}` → `${v}` geri alındı (tag: `${env.TAG_NAME}`)."
    }
    return "@${rc.owner} ${text}\n\nBuild: ${env.BUILD_URL}"
}
