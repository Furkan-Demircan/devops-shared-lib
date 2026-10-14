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
 *
 * Opsiyonel parametreler:
 *   testCmd             : Test komutu (verilmezse Test aşaması atlanır)
 *   deployStageCmd      : Stage'e deploy komutu (main branch'te çalışır)
 *   deployProdCmd       : Prod'a deploy komutu (release tag'lerinde çalışır)
 *   testReports         : JUnit XML rapor yolu (varsayılan: **\/test-results/**\/*.xml)
 *   allowEmptyTestResults : true ise rapor bulunamaması hata sayılmaz (varsayılan: false)
 *   mainBranch          : Stage'e çıkılacak branch (varsayılan: main)
 *   releaseTagPattern   : Prod deploy'u tetikleyen tag deseni (varsayılan: v*)
 *   agentLabel          : Build'in çalışacağı Jenkins agent label'ı (varsayılan: herhangi biri)
 *   timeoutMinutes      : Pipeline zaman aşımı, dakika (varsayılan: 60)
 *   stageCredentials    : Yalnızca stage deploy'una bağlanan withCredentials binding'leri
 *   prodCredentials     : Yalnızca prod deploy'una bağlanan withCredentials binding'leri
 *                         Örn. [string(credentialsId: 'prod-deploy-token', variable: 'DEPLOY_TOKEN')]
 *   credentials         : (Eski) Ayrı tanım yoksa her iki deploy'a da bağlanır
 *   githubCredentialsId : GitHub token credential ID'si (varsayılan: github-token)
 *   githubApiUrl        : GitHub Enterprise API adresi (varsayılan: https://api.github.com)
 */
def call(Map cfg = [:]) {
    ['owner', 'buildCmd'].each { key ->
        if (!cfg[key]) {
            error "standardPipeline: '${key}' parametresi zorunlu."
        }
    }

    String mainBranch     = cfg.mainBranch ?: 'main'
    String tagPattern     = cfg.releaseTagPattern ?: 'v*'
    String testReports    = cfg.testReports ?: '**/test-results/**/*.xml'
    boolean allowEmpty    = cfg.allowEmptyTestResults as boolean
    String agentLabel     = cfg.agentLabel ?: ''
    int timeoutMinutes    = (cfg.timeoutMinutes ?: 60) as int
    List stageCredentials = cfg.stageCredentials ?: cfg.credentials ?: []
    List prodCredentials  = cfg.prodCredentials ?: cfg.credentials ?: []
    Map gh                = [credentialsId: cfg.githubCredentialsId, apiUrl: cfg.githubApiUrl]

    // Hata yorumlarıyla owner'ı yalnızca main ve tag build'lerinde etiketle (PR/feature spam'i önlenir)
    boolean notifyOwner = env.BRANCH_NAME == mainBranch || env.TAG_NAME

    pipeline {
        agent { label agentLabel }

        options {
            // Sıralı deploy: eski bir build yeni sürümün üzerine stage/prod deploy etmesin
            disableConcurrentBuilds()
            timeout(time: timeoutMinutes, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '30'))
        }

        stages {
            stage('Build') {
                steps {
                    notifyGitHub(gh + [state: 'pending', description: 'Pipeline çalışıyor'])
                    sh cfg.buildCmd
                }
            }

            stage('Test') {
                when {
                    expression { cfg.testCmd as boolean }
                }
                steps {
                    sh cfg.testCmd
                }
                post {
                    always {
                        junit allowEmptyResults: allowEmpty, testResults: testReports
                    }
                }
            }

            stage('Deploy Stage') {
                when {
                    allOf {
                        branch mainBranch
                        expression { cfg.deployStageCmd as boolean }
                        // UNSTABLE (başarısız test raporu) build'ler deploy edilmez
                        expression { currentBuild.currentResult == 'SUCCESS' }
                    }
                }
                steps {
                    withCredentials(stageCredentials) {
                        sh cfg.deployStageCmd
                    }
                    notifyGitHub(gh + [
                        state      : 'success',
                        context    : 'jenkins/stage',
                        description: "Stage'e çıktı",
                        owner      : cfg.owner,
                        comment    : true,
                        message    : """@${cfg.owner} 🚀 Bu commit stage'e çıktı.

Production'a çıkmak için GitHub'da bu commit'i hedefleyen bir **Release** oluşturun (tag örn. `v1.4.0`).
Commit: `${env.GIT_COMMIT}`
"""
                    ])
                }
                post {
                    failure {
                        notifyGitHub(gh + [state: 'failure', context: 'jenkins/stage', description: 'Stage deploy başarısız'])
                    }
                }
            }

            stage('Prod Ön Kontrol') {
                when {
                    allOf {
                        tag pattern: tagPattern, comparator: 'GLOB'
                        expression { cfg.deployProdCmd as boolean }
                    }
                }
                steps {
                    script {
                        String stageState = githubApi.statusOf(env.GIT_COMMIT, 'jenkins/stage', gh)
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
                        expression { cfg.deployProdCmd as boolean }
                        expression { currentBuild.currentResult == 'SUCCESS' }
                    }
                }
                steps {
                    withCredentials(prodCredentials) {
                        sh cfg.deployProdCmd
                    }
                    notifyGitHub(gh + [
                        state      : 'success',
                        context    : 'jenkins/prod',
                        description: "${env.TAG_NAME} production'da",
                        owner      : cfg.owner,
                        comment    : true,
                        message    : "@${cfg.owner} ✅ `${env.TAG_NAME}` production'a çıktı. Build: ${env.BUILD_URL}"
                    ])
                }
                post {
                    failure {
                        notifyGitHub(gh + [state: 'failure', context: 'jenkins/prod', description: "${env.TAG_NAME} prod deploy başarısız"])
                    }
                }
            }
        }

        post {
            success {
                notifyGitHub(gh + [state: 'success', description: 'Pipeline başarılı'])
            }
            unstable {
                notifyGitHub(gh + [
                    state      : 'failure',
                    description: 'Testler başarısız, deploy yapılmadı',
                    owner      : cfg.owner,
                    comment    : notifyOwner
                ])
            }
            failure {
                notifyGitHub(gh + [
                    state      : 'failure',
                    description: 'Pipeline başarısız, detay için tıklayın',
                    owner      : cfg.owner,
                    comment    : notifyOwner
                ])
            }
            aborted {
                notifyGitHub(gh + [state: 'error', description: 'Pipeline iptal edildi'])
            }
        }
    }
}
