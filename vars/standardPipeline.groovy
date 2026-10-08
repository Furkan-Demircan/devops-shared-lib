/**
 * Tüm projeler için ortak CI/CD pipeline'ı. Prod onayı GitHub üzerinden, tag ile verilir.
 *
 * Akış:
 *   main branch'e push  -> Build -> Test -> Deploy Stage -> "prod için tag oluşturun" yorumu
 *   v* tag'i oluşturulur -> Build -> Test -> Stage kontrolü -> Deploy Prod
 *
 * Zorunlu parametreler:
 *   owner             : Proje sahibinin GitHub kullanıcı adı (bildirimlerde etiketlenir)
 *   buildCmd          : Build komutu
 *   testCmd           : Test komutu
 *
 * Opsiyonel parametreler:
 *   deployStageCmd    : Stage'e deploy komutu (main branch'te çalışır)
 *   deployProdCmd     : Prod'a deploy komutu (release tag'lerinde çalışır)
 *   testReports       : JUnit XML rapor yolu (varsayılan: **\/test-results/**\/*.xml)
 *   mainBranch        : Stage'e çıkılacak branch (varsayılan: main)
 *   releaseTagPattern : Prod deploy'u tetikleyen tag deseni (varsayılan: v*)
 */
def call(Map cfg = [:]) {
    ['owner', 'buildCmd', 'testCmd'].each { key ->
        if (!cfg[key]) {
            error "standardPipeline: '${key}' parametresi zorunlu."
        }
    }

    String mainBranch  = cfg.mainBranch ?: 'main'
    String tagPattern  = cfg.releaseTagPattern ?: 'v*'
    String testReports = cfg.testReports ?: '**/test-results/**/*.xml'

    pipeline {
        agent any

        stages {
            stage('Build') {
                steps {
                    notifyGitHub(state: 'pending', description: 'Pipeline çalışıyor')
                    sh cfg.buildCmd
                }
            }

            stage('Test') {
                steps {
                    sh cfg.testCmd
                }
                post {
                    always {
                        junit allowEmptyResults: true, testResults: testReports
                    }
                }
            }

            stage('Deploy Stage') {
                when {
                    allOf {
                        branch mainBranch
                        expression { cfg.deployStageCmd }
                    }
                }
                steps {
                    sh cfg.deployStageCmd
                    notifyGitHub(
                        state      : 'success',
                        context    : 'jenkins/stage',
                        description: "Stage'e çıktı",
                        owner      : cfg.owner,
                        comment    : true,
                        message    : """@${cfg.owner} 🚀 Bu commit stage'e çıktı.

Production'a çıkmak için GitHub'da bu commit'i hedefleyen bir **Release** oluşturun (tag örn. `v1.4.0`).
Commit: `${env.GIT_COMMIT}`
"""
                    )
                }
            }

            stage('Prod Ön Kontrol') {
                when {
                    allOf {
                        tag pattern: tagPattern, comparator: 'GLOB'
                        expression { cfg.deployProdCmd }
                    }
                }
                steps {
                    script {
                        String stageState = githubApi.statusOf(env.GIT_COMMIT, 'jenkins/stage')
                        if (stageState != 'success') {
                            error "${env.TAG_NAME} tag'inin gösterdiği commit stage'e başarıyla çıkmamış " +
                                  "(jenkins/stage = ${stageState ?: 'yok'}). Prod deploy reddedildi."
                        }
                    }
                }
            }

            stage('Deploy Prod') {
                when {
                    allOf {
                        tag pattern: tagPattern, comparator: 'GLOB'
                        expression { cfg.deployProdCmd }
                    }
                }
                steps {
                    sh cfg.deployProdCmd
                    notifyGitHub(
                        state      : 'success',
                        context    : 'jenkins/prod',
                        description: "${env.TAG_NAME} production'da",
                        owner      : cfg.owner,
                        comment    : true,
                        message    : "@${cfg.owner} ✅ `${env.TAG_NAME}` production'a çıktı. Build: ${env.BUILD_URL}"
                    )
                }
            }
        }

        post {
            success {
                notifyGitHub(state: 'success', description: 'Pipeline başarılı')
            }
            failure {
                notifyGitHub(
                    state      : 'failure',
                    description: 'Pipeline başarısız, detay için tıklayın',
                    owner      : cfg.owner,
                    comment    : true
                )
            }
            aborted {
                notifyGitHub(state: 'error', description: 'Pipeline iptal edildi')
            }
        }
    }
}