import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/**
 * GitHub REST API için küçük yardımcılar. Diğer adımlar tarafından kullanılır.
 *
 *   githubApi.repoSlug()                      -> "org/repo"
 *   githubApi.apiPost("repos/org/repo/...", [..]) -> true/false
 *   githubApi.apiGet("repos/org/repo/...")        -> Map (veya hata olursa null)
 *   githubApi.statusOf(sha, "jenkins/stage")   -> "success" | "failure" | "pending" | null
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
}

Map apiGet(String path, Map opts = [:]) {
    try {
        String out
        withCredentials([string(credentialsId: credId(opts), variable: 'GH_TOKEN')]) {
            out = sh(returnStdout: true, label: "GitHub API GET ${path}", script: """
                curl -sS -f \\
                  -H "Authorization: Bearer \$GH_TOKEN" \\
                  -H "Accept: application/vnd.github+json" \\
                  -H "X-GitHub-Api-Version: 2022-11-28" \\
                  ${apiUrl(opts)}/${path}
            """).trim()
        }
        return parseJson(out)
    } catch (err) {
        echo "UYARI: GitHub API çağrısı başarısız (GET ${path}): ${err}"
        return null
    }
}

/** Bir commit'teki belirli bir status kontrolünün son durumunu döner. */
String statusOf(String sha, String context, Map opts = [:]) {
    String repo = repoSlug()
    if (!repo || !sha) return null
    Map combined = apiGet("repos/${repo}/commits/${sha}/status", opts)
    return findState(combined, context)
}

private String credId(Map opts) { return opts.credentialsId ?: 'github-token' }
private String apiUrl(Map opts) { return opts.apiUrl ?: 'https://api.github.com' }

@NonCPS
private String findState(Map combined, String context) {
    def match = combined?.statuses?.find { it.context == context }
    return match?.state
}

@NonCPS
private Map parseJson(String text) {
    return new JsonSlurperClassic().parseText(text) as Map
}

@NonCPS
private String parseSlug(String url) {
    if (!url) return null
    def m = url =~ /github[^\/:]*[:\/](.+?)(\.git)?$/
    return m ? m[0][1] : null
}