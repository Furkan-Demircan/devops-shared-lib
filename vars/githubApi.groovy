import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/**
 * GitHub REST API için küçük yardımcılar. Diğer adımlar tarafından kullanılır.
 *
 *   githubApi.repoSlug()                      -> "org/repo"
 *   githubApi.headSha()                       -> GitHub'daki commit SHA'sı (PR build'lerinde PR head'i)
 *   githubApi.apiPost("repos/org/repo/...", [..]) -> true/false
 *   githubApi.apiGet("repos/org/repo/...")        -> Map/List (veya hata olursa null)
 *   githubApi.statusOf(sha, "jenkins/stage")   -> "success" | "failure" | "pending" | null
 *   githubApi.mergedPullFor(sha, "main")      -> sha'yı main'e getiren merge edilmiş PR (veya null)
 *   githubApi.mergerOf(pr)                    -> PR'ı merge eden kullanıcı
 *   githubApi.treeOf(sha)                     -> commit'in tree SHA'sı (içerik karşılaştırması için)
 *
 * Ortak opsiyonlar (opts):
 *   credentialsId : Jenkins'teki GitHub token credential ID'si (varsayılan: github-token)
 *   apiUrl        : GitHub Enterprise için API adresi (varsayılan: https://api.github.com)
 */

String repoSlug() {
    return parseSlug(env.GIT_URL)
}

boolean apiPost(String path, Map body, Map opts = [:]) {
    String file = ".gh-api-${env.BUILD_NUMBER}-${System.nanoTime()}.json"
    try {
        writeFile file: file, text: JsonOutput.toJson(body)

        int rc
        withCredentials([string(credentialsId: credId(opts), variable: 'GH_TOKEN')]) {
            rc = sh(returnStatus: true, label: "GitHub API POST ${path}", script: """
                curl -sS -f -X POST \\
                  -H "Authorization: Bearer \$GH_TOKEN" \\
                  -H "Accept: application/vnd.github+json" \\
                  -H "X-GitHub-Api-Version: 2022-11-28" \\
                  --data @${file} \\
                  ${apiUrl(opts)}/${path} > /dev/null
                rc=\$?
                rm -f ${file}
                exit \$rc
            """)
        }
        if (rc != 0) {
            echo "UYARI: GitHub API çağrısı başarısız (POST ${path}), çıkış kodu: ${rc}"
        }
        return rc == 0
    } catch (err) {
        // Bildirim hatası (örn. eksik credential) build'i düşürmemeli
        echo "UYARI: GitHub API çağrısı başarısız (POST ${path}): ${err}"
        sh(returnStatus: true, script: "rm -f ${file}")
        return false
    }
}

def apiGet(String path, Map opts = [:]) {
    try {
        String out
        withCredentials([string(credentialsId: credId(opts), variable: 'GH_TOKEN')]) {
            out = sh(returnStdout: true, label: "GitHub API GET ${path}", script: """
                curl -sS -f \\
                  -H "Authorization: Bearer \$GH_TOKEN" \\
                  -H "Accept: application/vnd.github+json" \\
                  -H "X-GitHub-Api-Version: 2022-11-28" \\
                  '${apiUrl(opts)}/${path}'
            """).trim()
        }
        return parseJson(out)
    } catch (err) {
        echo "UYARI: GitHub API çağrısı başarısız (GET ${path}): ${err}"
        return null
    }
}

/**
 * GitHub'da gerçekten var olan commit SHA'sı.
 * PR build'lerinde env.GIT_COMMIT Jenkins'in yerelde oluşturduğu merge commit'i olabilir;
 * bu durumda PR'ın head SHA'sı API'den alınır.
 */
String headSha(Map opts = [:]) {
    if (!env.CHANGE_ID) return env.GIT_COMMIT
    if (env.GH_HEAD_SHA) return env.GH_HEAD_SHA
    String repo = repoSlug()
    Map pr = repo ? apiGet("repos/${repo}/pulls/${env.CHANGE_ID}", opts) : null
    String sha = pr?.head?.sha
    if (sha) env.GH_HEAD_SHA = sha
    return sha
}

/** Bir commit'teki belirli bir status kontrolünün son durumunu döner. */
String statusOf(String sha, String context, Map opts = [:]) {
    String repo = repoSlug()
    if (!repo || !sha) return null
    Map combined = apiGet("repos/${repo}/commits/${sha}/status", opts)
    return findState(combined, context)
}

/**
 * Verilen commit'i hedef branch'e getiren, merge edilmiş PR.
 * Merge, squash ve rebase merge'lerin hepsinde PR'ın merge_commit_sha'sı branch'e düşen commit'tir.
 */
Map mergedPullFor(String sha, String baseBranch, Map opts = [:]) {
    String repo = repoSlug()
    if (!repo || !sha) return null
    def pulls = apiGetOrFail("repos/${repo}/commits/${sha}/pulls?per_page=100", opts)
    return findMergedPull(pulls, sha, baseBranch)
}

/** PR'ı merge eden kullanıcı. Liste endpoint'i merged_by döndürmediği için PR ayrıca okunur. */
String mergerOf(Map pr, Map opts = [:]) {
    String repo = repoSlug()
    if (!repo || !pr) return null
    Map full = apiGetOrFail("repos/${repo}/pulls/${pr.number}", opts)
    return full?.merged_by?.login
}

String treeOf(String sha, Map opts = [:]) {
    String repo = repoSlug()
    if (!repo || !sha) return null
    Map commit = apiGetOrFail("repos/${repo}/git/commits/${sha}", opts)
    return commit?.tree?.sha
}

/**
 * Prod kontrolleri için: API hatası "PR yok" / "merge eden yok" gibi yanlış bir sonuca dönüşmesin,
 * gerçek sebebiyle build'i düşürsün.
 */
private def apiGetOrFail(String path, Map opts) {
    def result = apiGet(path, opts)
    if (result == null) {
        error "GitHub API'ye erişilemedi (GET ${path}). '${credId(opts)}' credential'ındaki token'ı " +
              "kontrol edin: 401 = geçersiz/süresi dolmuş token, 403/404 = token'ın bu repo'ya yetkisi yok."
    }
    return result
}

private String credId(Map opts) { return opts.credentialsId ?: 'github-token' }
private String apiUrl(Map opts) { return opts.apiUrl ?: 'https://api.github.com' }

@NonCPS
private String findState(Map combined, String context) {
    def match = combined?.statuses?.find { it.context == context }
    return match?.state
}

@NonCPS
private Object parseJson(String text) {
    return new JsonSlurperClassic().parseText(text)
}

@NonCPS
private Map findMergedPull(def pulls, String sha, String baseBranch) {
    if (!(pulls instanceof List)) return null
    return pulls.find { it.merged_at && it.merge_commit_sha == sha && it.base?.ref == baseBranch } as Map
}

@NonCPS
private String parseSlug(String url) {
    if (!url) return null
    // https://host[:port]/org/repo(.git) ve git@host:org/repo(.git); host'tan bağımsız (GHE dahil)
    def m = url =~ /[:\/]([^\/:]+\/[^\/]+?)(?:\.git)?\/?$/
    return m ? m[0][1] : null
}
