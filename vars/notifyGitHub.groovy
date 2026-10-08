import groovy.json.JsonOutput

/**
 * GitHub'a bildirim gönderir.
 *
 * Kullanım:
 *   notifyGitHub(state: 'pending', description: 'Build başladı')
 *   notifyGitHub(state: 'failure', description: 'Testler başarısız', owner: 'ayse-gh', comment: true)
 *
 * Parametreler:
 *   state         : pending | success | failure | error   (commit yanındaki işaret)
 *   context       : Status kontrolünün adı (varsayılan: jenkins/ci)
 *   description   : Kısa açıklama (GitHub 140 karakterle sınırlar)
 *   comment       : true ise commit'e yorum da yazılır
 *   owner         : Yorumda @ ile etiketlenecek GitHub kullanıcı adı (bildirim gider)
 *   message       : Yorum metni (verilmezse otomatik oluşturulur)
 *   credentialsId : Jenkins'teki GitHub token credential ID'si (varsayılan: github-token)
 *   apiUrl        : GitHub Enterprise için API adresi (varsayılan: https://api.github.com)
 */
def call(Map args = [:]) {
    String state       = args.state ?: 'pending'
    String context     = args.context ?: 'jenkins/ci'
    String description = (args.description ?: '').take(140)
    String credId      = args.credentialsId ?: 'github-token'
    String apiUrl      = args.apiUrl ?: 'https://api.github.com'

    String repo = repoSlug(env.GIT_URL)
    String sha  = env.GIT_COMMIT

    if (!repo || !sha) {
        echo "notifyGitHub: repo (${repo}) veya commit (${sha}) bulunamadı, bildirim atlanıyor."
        return
    }

    withCredentials([string(credentialsId: credId, variable: 'GH_TOKEN')]) {
        // 1) Commit yanına yeşil/kırmızı işaret
        postJson(apiUrl, "repos/${repo}/statuses/${sha}", [
            state      : state,
            context    : context,
            description: description,
            target_url : env.BUILD_URL
        ])

        // 2) İstenirse commit'e yorum (+ @mention ile kişiye GitHub bildirimi)
        if (args.comment) {
            String text = args.message ?: defaultMessage(state, args.owner)
            postJson(apiUrl, "repos/${repo}/commits/${sha}/comments", [body: text])
        }
    }
}

private String defaultMessage(String state, String owner) {
    String mention = owner ? "@${owner} " : ''
    String icon = (state == 'success') ? '✅' : '❌'
    return """${mention}${icon} **${env.JOB_NAME} #${env.BUILD_NUMBER}** sonucu: `${state}`

- Konsol çıktısı: ${env.BUILD_URL}console
- Test raporu: ${env.BUILD_URL}testReport
"""
}

private void postJson(String apiUrl, String path, Map body) {
    String file = ".gh-notify-${env.BUILD_NUMBER}-${System.nanoTime()}.json"
    writeFile file: file, text: JsonOutput.toJson(body)

    int rc = sh(returnStatus: true, label: "GitHub API: ${path}", script: """
        curl -sS -f -X POST \\
          -H "Authorization: Bearer \$GH_TOKEN" \\
          -H "Accept: application/vnd.github+json" \\
          -H "X-GitHub-Api-Version: 2022-11-28" \\
          --data @${file} \\
          ${apiUrl}/${path} > /dev/null
        rc=\$?
        rm -f ${file}
        exit \$rc
    """)

    // Bildirim hatası pipeline'ı düşürmesin, sadece uyarı versin
    if (rc != 0) {
        echo "UYARI: GitHub bildirimi gönderilemedi (${path}), çıkış kodu: ${rc}"
    }
}

@NonCPS
private String repoSlug(String url) {
    if (!url) return null
    def m = url =~ /github[^\/:]*[:\/](.+?)(\.git)?$/
    return m ? m[0][1] : null
}
