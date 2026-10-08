/**
 * Tüm projeler için ortak CI/CD pipeline'ı.
 *
 * Zorunlu parametreler:
 *   owner          : Proje sahibinin GitHub kullanıcı adı (hata olursa etiketlenir)
 *   buildCmd       : Build komutu
 *   testCmd        : Test komutu
 *
 * Opsiyonel parametreler:
 *   approvers      : Prod onayı verebilecek Jenkins kullanıcı(ları), virgülle ayrılmış
 *   deployStageCmd : Stage'e deploy komutu (main branch'te çalışır)
 *   deployProdCmd  : Prod'a deploy komutu (onaydan sonra çalışır)
 *   testReports    : JUnit XML rapor yolu (varsayılan: **\/test-results/**\/*.xml)
 *   mainBranch     : Deploy yapılacak branch (varsayılan: main)
 */
def call(Map cfg = [:]) {
    ['owner', 'buildCmd', 'testCmd'].each { key ->
        if (!cfg[key]) {
            error "standardPipeline: '${key}' parametresi zorunlu."
        }
    }

    String mainBranch  = cfg.mainBranch ?: 'main'
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
                }
            }

            stage('Prod Onayı') {
                when {
                    allOf {
                        branch mainBranch
                        expression { cfg.deployProdCmd }
                    }
                }
                steps {
                    notifyGitHub(
                        state      : 'pending',
                        context    : 'jenkins/prod-approval',
                        description: 'Stage hazır, prod onayı bekleniyor',
                        owner      : cfg.owner,
                        comment    : true,
                        message    : "@${cfg.owner} 🚀 Stage'e çıktı. Production'a çıkmak için onay verin: ${env.BUILD_URL}input"
                    )
                    timeout(time: 1, unit: 'DAYS') {
                        input message: "Production'a çıkılsın mı?",
                              ok: 'Evet, çık',
                              submitter: cfg.approvers ?: ''
                    }
                }
            }

            stage('Deploy Prod') {
                when {
                    allOf {
                        branch mainBranch
                        expression { cfg.deployProdCmd }
                    }
                }
                steps {
                    sh cfg.deployProdCmd
                    notifyGitHub(
                        state      : 'success',
                        context    : 'jenkins/prod-approval',
                        description: "Production'a çıkıldı"
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
                notifyGitHub(state: 'error', description: 'Pipeline iptal edildi / onay verilmedi')
            }
        }
    }
}
