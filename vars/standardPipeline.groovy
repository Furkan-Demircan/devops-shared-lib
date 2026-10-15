/**
 * Tüm projeler için ortak CI/CD pipeline'ı. Prod onayı GitHub üzerinden, stage -> main PR'ı ile verilir.
 *
 * Akış:
 *   stage branch'ine merge/push -> Build -> Test -> Deploy Stage -> "prod için main'e PR açın" yorumu
 *   stage -> main PR'ı          -> Build -> Test (PR kontrolü, deploy yok)
 *   PR sorumlu kişi tarafından main'e merge -> Build -> Test -> Prod Ön Kontrol -> Deploy Prod
 *
 * Prod Ön Kontrol, main'deki commit için şunları doğrular:
 *   - main'e stage branch'inden açılmış, merge edilmiş bir PR ile gelmiş (doğrudan push reddedilir)
 *   - PR'ı merge eden kişi prodMergers listesinde (prod onayı = sorumlu kişinin merge'ü)
 *   - PR'ın son commit'i stage'e başarıyla çıkmış (jenkins/stage = success)
 *   - main'in içeriği stage'de denenen içerikle birebir aynı (tree SHA eşleşmesi)
 *
 * Zorunlu parametreler:
 *   owner             : Proje sahibinin GitHub kullanıcı adı (bildirimlerde etiketlenir)
 *   buildCmd          : Build komutu
 *
 * Opsiyonel parametreler:
 *   testCmd             : Test komutu (verilmezse Test aşaması atlanır)
 *   deployStageCmd      : Stage'e deploy komutu (stage branch'inde çalışır)
 *   deployProdCmd       : Prod'a deploy komutu (main branch'inde çalışır)
 *   testReports         : JUnit XML rapor yolu (varsayılan: **\/test-results/**\/*.xml)
 *   allowEmptyTestResults : true ise rapor bulunamaması hata sayılmaz (varsayılan: false)
 *   stageBranch         : Stage'e çıkılacak branch (varsayılan: stage)
 *   mainBranch          : Prod'a çıkılacak branch (varsayılan: main)
 *   prodMergers         : stage -> main PR'ını merge ederek prod'a çıkarabilecek GitHub kullanıcıları
 *                         (varsayılan: [owner])
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

    String stageBranch    = cfg.stageBranch ?: 'stage'
    String mainBranch     = cfg.mainBranch ?: 'main'
    List prodMergers      = cfg.prodMergers ?: [cfg.owner]
    String testReports    = cfg.testReports ?: '**/test-results/**/*.xml'
    boolean allowEmpty    = cfg.allowEmptyTestResults == true  // CPS "null as boolean" -> null döner, junit patlar
    String agentLabel     = cfg.agentLabel ?: ''
    int timeoutMinutes    = (cfg.timeoutMinutes ?: 60) as int
    List stageCredentials = cfg.stageCredentials ?: cfg.credentials ?: []
    List prodCredentials  = cfg.prodCredentials ?: cfg.credentials ?: []
    Map gh                = [credentialsId: cfg.githubCredentialsId, apiUrl: cfg.githubApiUrl]

    if (stageBranch == mainBranch) {
        error "standardPipeline: stageBranch ve mainBranch aynı olamaz ('${mainBranch}')."
    }

    // Hata yorumlarıyla owner'ı yalnızca stage ve main build'lerinde etiketle (PR/feature spam'i önlenir)
    boolean notifyOwner = env.BRANCH_NAME in [stageBranch, mainBranch]

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
                    expression { !!cfg.testCmd }
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
                        branch stageBranch
                        expression { !!cfg.deployStageCmd }
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

Production'a çıkmak için GitHub'da `${stageBranch}` → `${mainBranch}` Pull Request'i açın.
PR ${prodMergers.collect { "@${it}" }.join(' / ')} tarafından merge edildiğinde prod deploy otomatik başlar.
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
                        branch mainBranch
                        expression { !!cfg.deployProdCmd }
                    }
                }
                steps {
                    script {
                        String sha = env.GIT_COMMIT
                        Map pr = githubApi.mergedPullFor(sha, mainBranch, gh)
                        if (!pr) {
                            error "${sha} commit'i ${mainBranch}'e merge edilmiş bir PR ile gelmemiş " +
                                  "(doğrudan push?). Prod deploy reddedildi."
                        }
                        if (pr.head?.ref != stageBranch) {
                            error "PR #${pr.number} '${pr.head?.ref}' branch'inden açılmış; prod'a yalnızca " +
                                  "'${stageBranch}' branch'inden gelen değişiklikler çıkabilir. Prod deploy reddedildi."
                        }

                        String merger = githubApi.mergerOf(pr, gh)
                        if (!(merger in prodMergers)) {
                            error "PR #${pr.number}'ı '${merger ?: 'bilinmiyor'}' merge etmiş; prod'a yalnızca " +
                                  "${prodMergers.join(', ')} çıkarabilir. Prod deploy reddedildi."
                        }

                        String stageSha   = pr.head.sha
                        String stageState = githubApi.statusOf(stageSha, 'jenkins/stage', gh)
                        if (stageState != 'success') {
                            error "PR #${pr.number}'ın son commit'i (${stageSha}) stage'e başarıyla çıkmamış " +
                                  "(jenkins/stage = ${stageState ?: 'yok'}). Prod deploy reddedildi."
                        }

                        String mainTree  = githubApi.treeOf(sha, gh)
                        String stageTree = githubApi.treeOf(stageSha, gh)
                        if (!mainTree || mainTree != stageTree) {
                            error "${mainBranch}'in içeriği stage'de denenen commit'le (${stageSha}) aynı değil; " +
                                  "${mainBranch}'de stage'de olmayan değişiklik var. Önce ${mainBranch}'i " +
                                  "${stageBranch}'e merge edip stage'den geçirin. Prod deploy reddedildi."
                        }

                        env.PROD_PR_NUMBER = "${pr.number}"
                        env.PROD_MERGER    = merger
                        echo "Prod onayı: PR #${pr.number}, merge eden: ${merger}"
                    }
                }
            }

            stage('Deploy Prod') {
                when {
                    allOf {
                        branch mainBranch
                        expression { !!cfg.deployProdCmd }
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
                        description: "Production'da (PR #${env.PROD_PR_NUMBER})",
                        owner      : cfg.owner,
                        comment    : true,
                        message    : "@${cfg.owner} ✅ PR #${env.PROD_PR_NUMBER} production'a çıktı " +
                                     "(merge eden: @${env.PROD_MERGER}). Build: ${env.BUILD_URL}"
                    ])
                }
                post {
                    failure {
                        notifyGitHub(gh + [state: 'failure', context: 'jenkins/prod', description: 'Prod deploy başarısız'])
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
