/**
 * GitHub'a bildirim gönderir: commit yanına durum işareti ve istenirse @mention'lı yorum.
 *
 * Kullanım:
 *   notifyGitHub(state: 'pending', description: 'Build başladı')
 *   notifyGitHub(state: 'failure', description: 'Testler başarısız', owner: 'ayse-gh', comment: true)
 *
 * Parametreler:
 *   state         : pending | success | failure | error
 *   context       : Status kontrolünün adı (varsayılan: jenkins/ci)
 *   description   : Kısa açıklama (GitHub 140 karakterle sınırlar)
 *   comment       : true ise commit'e yorum da yazılır
 *   owner         : Yorumda @ ile etiketlenecek GitHub kullanıcı adı
 *   message       : Yorum metni (verilmezse otomatik oluşturulur)
 *   credentialsId, apiUrl : githubApi'ye aynen iletilir
 */
def call(Map args = [:]) {
    String repo = githubApi.repoSlug()
    String sha  = env.GIT_COMMIT

    if (!repo || !sha) {
        echo "notifyGitHub: repo (${repo}) veya commit (${sha}) bulunamadı, bildirim atlanıyor."
        return
    }

    Map opts = [credentialsId: args.credentialsId, apiUrl: args.apiUrl]

    githubApi.post("repos/${repo}/statuses/${sha}", [
        state      : args.state ?: 'pending',
        context    : args.context ?: 'jenkins/ci',
        description: (args.description ?: '').take(140),
        target_url : env.BUILD_URL
    ], opts)

    if (args.comment) {
        String text = args.message ?: defaultMessage(args.state ?: 'pending', args.owner)
        githubApi.post("repos/${repo}/commits/${sha}/comments", [body: text], opts)
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