import groovy.json.JsonSlurperClassic
import groovy.transform.Field

/**
 * releasePipeline'ın adımları. Shell tarafı resources/devops/release/ altındadır.
 *
 * Ortam branch'leri (dev, test, main) bağımsızdır; feature'lar her birine ayrı PR ile merge edilir. Tag'leri
 * developer/yetkili kişi o ortamın merge'ünden sonra branch'in ucuna atar, Jenkins hiç tag oluşturmaz:
 *   v1.0.5-dev  (dev'in ucu)  -> image 1.0.5-dev  -> dev   -> status release/1.0.5/dev
 *   v1.0.5-test (test'in ucu) -> image 1.0.5-test -> test  -> status release/1.0.5/test (dev status'u şart)
 *   v1.0.5      (main'in ucu) -> image 1.0.5      -> prod  -> status release/1.0.5/prod + Release (test status'u şart)
 *
 * release/<sürüm>/<ortam> = success, "bu sürüm o ortama başarıyla çıktı" demektir ve bir sonraki aşamanın şartıdır.
 */

@Field final String SEMVER = /(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)/
@Field final Map TAG_PATTERNS = [
    dev : /^v/ + SEMVER + /-dev$/,
    test: /^v/ + SEMVER + /-test$/,
    prod: /^v/ + SEMVER + /$/
]
// Build'i tetikleyen desen (Jenkins'teki isim filtresiyle aynı) ve beklenen tam biçim
@Field final String ROLLBACK_TRIGGER = /^rollback-\d+\.\d+\.\d+-.*$/
@Field final String ROLLBACK_TAG = /^rollback-/ + SEMVER + /-\d{8}-\d+$/
@Field final String PROD_VERSION = /^/ + SEMVER + /$/
// Sunucuya giden ssh komutlarına girecek değerler
@Field final String SAFE = /^[A-Za-z0-9._\/-]+$/

/** releasePipeline parametrelerini doğrular, varsayılanları doldurur. */
Map config(Map cfg) {
    ['owner', 'image', 'dockerfile', 'buildCmd', 'environments'].each { key ->
        if (!cfg[key]) {
            error "releasePipeline: '${key}' parametresi zorunlu."
        }
    }
    if (!(cfg.image ==~ /^[a-z0-9.-]+(:\d+)?\/[a-z0-9._\/-]+$/)) {
        error "releasePipeline: image '${cfg.image}' geçersiz. Küçük harfli ve tag'siz olmalı, ör. ghcr.io/org/app"
    }

    Map rc = [
        owner              : cfg.owner,
        image              : cfg.image,
        dockerfile         : cfg.dockerfile,
        dockerContext      : cfg.dockerContext ?: '.',
        buildCmd           : cfg.buildCmd,
        testCmd            : cfg.testCmd,
        testReports        : cfg.testReports ?: '**/test-results/**/*.xml',
        allowEmpty         : cfg.allowEmptyTestResults == true,  // CPS "null as boolean" -> null döner
        verifyImageOnCi    : cfg.verifyImageOnCi != false,
        branches           : [dev: cfg.devBranch ?: 'dev', test: cfg.testBranch ?: 'test', prod: cfg.mainBranch ?: 'main'],
        agentLabel         : cfg.agentLabel ?: '',
        timeoutMinutes     : (cfg.timeoutMinutes ?: 60) as int,
        githubCredentialsId: cfg.githubCredentialsId ?: 'github-jenkins',
        environments       : [:]
    ]
    rc.gh = [credentialsId: rc.githubCredentialsId, credentialsType: 'usernamePassword', apiUrl: cfg.githubApiUrl]

    ['dev', 'test', 'prod'].each { name ->
        Map e = cfg.environments[name]
        if (!e) {
            error "releasePipeline: environments.${name} tanımlı değil."
        }
        ['host', 'dir', 'service', 'sshCredentialsId', 'knownHosts'].each { key ->
            if (!e[key]) {
                error "releasePipeline: environments.${name}.${key} zorunlu" +
                      (key == 'knownHosts' ? " (sunucunun host key'i: ssh-keyscan -t ed25519 ${e.host ?: '<host>'})" : '')
            }
        }
        ['host', 'dir', 'service'].each { key ->
            if (!(e[key] ==~ SAFE)) {
                error "releasePipeline: environments.${name}.${key} geçersiz karakter içeriyor: '${e[key]}'"
            }
        }
        if (e.healthUrl && !(e.healthUrl ==~ /^https?:\/\/[A-Za-z0-9.:\/_-]+$/)) {
            error "releasePipeline: environments.${name}.healthUrl geçersiz: '${e.healthUrl}'"
        }
        rc.environments[name] = e
    }
    return rc
}

/**
 * Build'in ne yapacağı. Deploy'u yalnızca tag'ler tetikler:
 *   dev / test / prod : v1.2.3-dev / v1.2.3-test / v1.2.3 tag'i
 *   rollback          : rollback-1.2.3-YYYYMMDD-N tag'i
 *   pr, ci            : PR'lar ve (elle tetiklenirse) branch'ler -> yalnızca build + test
 *   ignore            : desene uymayan tag'ler -> hiçbir şey yapılmaz
 * Tag build'lerinde RELEASE_VERSION ve RELEASE_CONTEXT (commit status adı) da burada belirlenir.
 */
String detectMode(Map rc) {
    if (env.CHANGE_ID) return 'pr'
    if (!env.TAG_NAME) return 'ci'

    String mode = tagMode(env.TAG_NAME)
    if (mode) {
        env.RELEASE_VERSION = versionOf(env.TAG_NAME, TAG_PATTERNS[mode])
        env.RELEASE_CONTEXT = statusContext(env.RELEASE_VERSION, mode)
        return mode
    }
    if (env.TAG_NAME ==~ ROLLBACK_TRIGGER) {
        env.RELEASE_CONTEXT = "rollback/${env.TAG_NAME}"
        return 'rollback'
    }
    return 'ignore'
}

/** Tüm branch ve tag'leri, tam geçmişle çeker (ancestor ve tag kontrolleri, release notları için). */
void fetchRefs(Map rc) {
    bash(rc, 'Branch ve tag geçmişini çek', '''
        if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
            git_auth fetch --quiet --unshallow --no-tags "$REPO_URL"
        fi
        git_auth fetch --quiet --prune --no-tags "$REPO_URL" \\
            '+refs/heads/*:refs/remotes/origin/*' '+refs/tags/*:refs/tags/*'
    ''')
}

// ------------------------------------------------------------------ kontroller

/**
 * dev / test / prod tag build'inin kontrolleri. Ortam branch'leri bağımsızdır: feature'lar her birine ayrı merge
 * edilir, tag o ortamın merge'ünden sonra branch'in ucuna atılır. Her tag kendi commit'inden build eder.
 *   TARGET_IMAGE_TAG : ortamın image tag'i (1.0.5-dev / 1.0.5-test / 1.0.5)
 *   IMAGE_READY      : 'true' ise image bu commit'ten zaten build edilmiş (Rebuild), build atlanır
 *   UNTESTED_DIFF    : (prod) main'deki commit ile test'te denenen commit arasındaki dosya farkı
 */
void checkRelease(Map rc, String stage) {
    String tag = env.TAG_NAME
    String version = env.RELEASE_VERSION
    String commit = git('git rev-parse HEAD')
    String branch = rc.branches[stage]

    if (git("git cat-file -t 'refs/tags/${tag}'") != 'tag') {
        error "${tag} annotated tag değil. Lightweight tag'ler commit tarihini alır ve Jenkins'te atlanabilir. " +
              "Silinemediği için yeni bir sürümle annotated tag atın: git tag -a <tag> origin/${branch} -m '<açıklama>'"
    }
    // first-parent geçmişi yalnızca branch'in kendi commit'lerini (merge commit'leri) içerir: feature branch'teki
    // ya da başka bir ortam branch'indeki bir commit'e atılan tag reddedilir
    String onBranch = sh(returnStdout: true,
        script: "git rev-list --first-parent refs/remotes/origin/${branch} | grep -Fx ${commit} || true").trim()
    if (!onBranch) {
        error "${tag} commit'i (${commit}) '${branch}' branch'inin kendi geçmişinde yok. Tag, feature ${branch}'e " +
              "merge edildikten sonra ${branch}'in ucuna atılmalı. Tag silinemediği için yeni bir sürüm numarası kullanın."
    }

    // Aynı sürüm bu ortama daha önce başarıyla çıktıysa iki kez yayınlanmaz
    if (statusOf(rc, commit, env.RELEASE_CONTEXT) == 'success') {
        error "${version} ${stage} ortamına daha önce çıkmış (${env.RELEASE_CONTEXT} = success). Aynı sürüm iki kez yayınlanmaz."
    }
    if (stage == 'prod' && releaseOf(rc, "v${version}")) {
        error "v${version} için GitHub Release zaten var; bu sürüm prod'a daha önce çıkmış."
    }

    // Sıra: test için dev, prod için test aşaması bu sürümle başarıyla tamamlanmış olmalı (farklı commit'te)
    if (stage != 'dev') {
        String prev = stage == 'test' ? 'dev' : 'test'
        String prevTag = "v${version}-${prev}"
        String prevCommit = remoteTagCommit(rc, prevTag)
        if (!prevCommit) {
            error "${prevTag} tag'i yok: ${version} ${prev} aşamasından geçmemiş."
        }
        String prevContext = statusContext(version, prev)
        String prevState = statusOf(rc, prevCommit, prevContext)
        if (prevState != 'success') {
            error "${version} ${prev} ortamına başarıyla çıkmamış (${prevTag} commit'inde ${prevContext} = ${prevState ?: 'yok'})."
        }
        if (stage == 'prod') {
            String diff = git("git diff --stat ${prevCommit} ${commit}")
            if (diff) {
                echo "UYARI: main'deki kod test'te denenen koddan (${prevTag}) farklı:\n${diff}"
                env.UNTESTED_DIFF = diff.take(3000)
            }
        }
    }

    String targetTag = imageTag(version, stage)
    String targetDigest = imageDigest(rc, targetTag)
    if (targetDigest) {
        // Rebuild: image bu tag'in önceki build'inde push edilmiş, deploy'da düşmüş. Aynı commit'ten mi?
        String revision = imageRevision(rc, targetTag)
        if (revision != commit) {
            error "${rc.image}:${targetTag} zaten var (commit: ${revision ?: 'bilinmiyor'}); image'ların üzerine " +
                  "yazılmaz. Yeni bir sürüm numarası kullanın."
        }
        echo "UYARI: ${rc.image}:${targetTag} bu commit'ten önceki bir build'de push edilmiş; build atlanıp deploy'dan devam ediliyor."
    }
    setImageEnv(targetTag, targetDigest, targetDigest ? 'true' : '')
}

/** rollback-1.2.3-YYYYMMDD-N tag build'i: hedef sürüm prod'a çıkmış mı, image'ı duruyor mu. */
void checkRollback(Map rc) {
    String tag = env.TAG_NAME
    String version = versionOf(tag, ROLLBACK_TAG)
    if (!version) {
        error "${tag} geçersiz. Biçim: rollback-<sürüm>-<YYYYMMDD>-<sıra>, ör. rollback-1.0.4-20261019-1"
    }
    if (git("git cat-file -t 'refs/tags/${tag}'") != 'tag') {
        error "${tag} annotated tag değil (lightweight tag'ler Jenkins'te commit tarihini alır ve atlanabilir). " +
              "Yeni bir sıra numarasıyla annotated tag atın: git tag -a rollback-${version}-<YYYYMMDD>-<sıra> 'v${version}^{}' -m '<sebep>'"
    }

    // Release yalnızca başarılı prod deploy'undan sonra oluşur: "prod'a çıkmış" bilgisinin kaynağı Release'tir
    // (v<sürüm> tag'i deploy'dan önce atıldığı için prod deploy'u düşmüş bir sürümde de bulunabilir)
    Map release = releaseOf(rc, "v${version}")
    if (!release) {
        error "v${version} için GitHub Release yok: ${version} sürümü hiç prod'a çıkmamış. Yalnızca prod'a çıkmış sürümlere dönülebilir."
    }
    String prodCommit = remoteTagCommit(rc, "v${version}")
    String commit = git('git rev-parse HEAD')
    if (prodCommit != commit) {
        error "${tag} ${commit} commit'ine atılmış, ama v${version} ${prodCommit ?: '(yok)'} commit'inde. " +
              "Rollback tag'ini v${version}'in commit'ine atın: git tag -a <rollback tag> 'v${version}^{}' -m '<sebep>'"
    }
    String digest = imageDigest(rc, version)
    if (!digest) {
        error "${rc.image}:${version} registry'de yok; bu sürüme dönülemez."
    }

    env.RELEASE_VERSION = version
    setImageEnv(version, digest, 'true')
    echo "Rollback hedefi: ${rc.image}:${version} (${digest})" +
         (release.prerelease ? " — UYARI: bu sürüm daha önce geri alınmış (pre-release)" : '')
}

// ------------------------------------------------------------------ image

/** Image'ı build eder. push: true ise TARGET_IMAGE_TAG ile registry'ye iter, false ise yalnızca build'in geçtiğini doğrular. */
void buildImage(Map rc, boolean push) {
    if (push && env.IMAGE_READY == 'true') {
        echo "${rc.image}:${env.TARGET_IMAGE_TAG} zaten bu commit'ten build edilmiş, build atlandı."
        return
    }
    List vars = ["DOCKERFILE=${rc.dockerfile}", "DOCKER_CONTEXT=${rc.dockerContext}", "PUSH=${push}",
                 "IMAGE_TAG=${env.TARGET_IMAGE_TAG ?: ''}", "VERSION=${env.RELEASE_VERSION ?: ''}"]
    String digest = bash(rc, push ? 'Image build + push' : 'Image build (doğrulama)', '''
        if [ "$PUSH" != true ]; then
            iid=$(mktemp); CLEANUP+=("$iid")
            docker build -f "$DOCKERFILE" --iidfile "$iid" "$DOCKER_CONTEXT" >&2
            docker rmi "$(cat "$iid")" > /dev/null || true
            exit 0
        fi
        docker_login
        # Etiketler image config'ine yazılır: image hangi commit/sürümden build edildiğini taşır,
        # Rebuild'de "bu image bu commit'ten mi" sorusu revision etiketiyle yanıtlanır
        docker build -f "$DOCKERFILE" -t "$IMAGE:$IMAGE_TAG" \\
            --label "org.opencontainers.image.version=$VERSION" \\
            --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" \\
            --label "org.opencontainers.image.source=${REPO_URL%.git}" \\
            "$DOCKER_CONTEXT" >&2
        docker push "$IMAGE:$IMAGE_TAG" >&2
        image_digest "$IMAGE_TAG"
    ''', vars, true)
    if (push) {
        env.SOURCE_DIGEST = digest
        echo "Push edildi: ${rc.image}:${env.TARGET_IMAGE_TAG} (${digest})"
    }
}

// ------------------------------------------------------------------ deploy

/**
 * TARGET_IMAGE_TAG'i ortama deploy eder. Ortamın kilidi altında önce çalışan sürüm okunur, sonra deploy edilir.
 * rollback: true ise çalışan sürüm hedefle aynıysa durur.
 * Dönen değer: deploy'dan önce çalışan image tag'i (ör. "1.0.4"), okunamadıysa boş.
 */
String deploy(Map rc, String envName, boolean rollback = false) {
    String tag = env.TARGET_IMAGE_TAG
    String previous = ''
    lock(resource: "${githubApi.repoSlug() ?: env.JOB_NAME}-${envName}") {
        previous = runningTag(rc, envName)
        echo "${envName}: çalışan sürüm '${previous ?: 'yok/okunamadı'}', deploy edilecek '${tag}'"
        if (rollback && previous == tag) {
            error "Prod'da zaten ${tag} çalışıyor; rollback yapılacak bir şey yok."
        }
        sshRun(rc, envName, "Deploy ${envName}: ${rc.image}:${tag}",
               'remote deploy "$DEPLOY_DIR" "$DEPLOY_SERVICE" "$IMAGE" "$DEPLOY_TAG" "$HEALTH_URL"', false, tag)
    }
    return previous
}

/** Ortamda çalışan container'ın image tag'i; container yoksa veya başka bir image çalışıyorsa boş. */
String runningTag(Map rc, String envName) {
    String ref = sshRun(rc, envName, "${envName}: çalışan sürümü oku",
                        'remote current "$DEPLOY_DIR" "$DEPLOY_SERVICE" "$IMAGE"', true, '')
    if (ref?.startsWith(rc.image + ':')) {
        return ref.substring(rc.image.length() + 1)
    }
    if (ref) {
        echo "UYARI: ${envName}'de beklenmeyen image çalışıyor: ${ref}"
    }
    return ''
}

private def sshRun(Map rc, String envName, String label, String command, boolean stdout, String tag) {
    Map e = rc.environments[envName]
    String script = libraryResource('devops/release/common.sh') + '\nssh_setup\n' + command + '\n'
    def result
    withCredentials([sshUserPrivateKey(credentialsId: e.sshCredentialsId, keyFileVariable: 'SSH_KEY_FILE',
                                       usernameVariable: 'SSH_USER')]) {
        withEnv(["IMAGE=${rc.image}", "DEPLOY_HOST=${e.host}", "DEPLOY_DIR=${e.dir}", "DEPLOY_SERVICE=${e.service}",
                 "HEALTH_URL=${e.healthUrl ?: ''}", "SSH_KNOWN_HOSTS=${e.knownHosts}", "DEPLOY_TAG=${tag}",
                 "REMOTE_SCRIPT=${libraryResource('devops/release/remote.sh')}"]) {
            result = stdout ? sh(script: script, label: label, returnStdout: true).trim()
                            : sh(script: script, label: label)
        }
    }
    return result
}

// ------------------------------------------------------------------ GitHub Release

/**
 * v<sürüm> tag'i için GitHub Release oluşturur. Notlar bir önceki prod tag'inden bu yana otomatik üretilir,
 * başına image, digest ve deploy tarihi eklenir; main test'ten farklıysa fark listesi de eklenir. Release URL'sini döner.
 */
String createRelease(Map rc, String previousRunning) {
    String repo = githubApi.repoSlug()
    String tag = env.TAG_NAME
    // main'in first-parent hattındaki önceki prod tag'i: notlar = iki prod tag'i arasında main'e merge edilen PR'lar
    String previousTag = gitOrEmpty("git describe --tags --abbrev=0 --first-parent --match 'v[0-9]*' --exclude '*-*' HEAD^")

    Map notesReq = [tag_name: tag]
    if (previousTag) {
        notesReq.previous_tag_name = previousTag
    }
    Map notes = githubApi.request('POST', "repos/${repo}/releases/generate-notes", notesReq, rc.gh).json

    String untested = env.UNTESTED_DIFF ? """
> ⚠️ **Test'te denenen koddan (`v${env.RELEASE_VERSION}-test`) farklı dosyalar:**

```
${env.UNTESTED_DIFF}
```
""" : ''

    String body = """**Image:** `${rc.image}:${env.RELEASE_VERSION}`
**Digest:** `${env.SOURCE_DIGEST}`
**Prod'a deploy:** ${utcNow()}
**Önceki prod sürümü:** ${previousRunning ?: 'bilinmiyor'}
**Jenkins:** ${env.BUILD_URL}
${untested}
${notes.body ?: ''}"""

    Map release = githubApi.request('POST', "repos/${repo}/releases", [
        tag_name   : tag,
        name       : tag,
        body       : body,
        draft      : false,
        prerelease : false,
        make_latest: 'true'
    ], rc.gh).json
    echo "GitHub Release: ${release.html_url}"
    return release.html_url
}

/**
 * Rollback sonrası: geri alınan sürümün Release'i pre-release olur ve notuna geri alma bilgisi eklenir;
 * dönülen sürümün Release'i "Latest" yapılır. Release bulunamazsa build UNSTABLE olur (deploy zaten yapıldı).
 */
void markRolledBack(Map rc, String fromVersion, String toVersion) {
    String repo = githubApi.repoSlug()
    if (!(fromVersion ==~ PROD_VERSION)) {
        unstable "Prod'da önceden çalışan sürüm okunamadı ('${fromVersion}'); hiçbir Release işaretlenmedi. " +
                 "Geri alınan sürümün Release'ini elle işaretleyin."
        return
    }

    Map from = releaseOf(rc, "v${fromVersion}")
    if (!from) {
        unstable "v${fromVersion} için GitHub Release yok; geri alma Releases sayfasına işlenemedi."
    } else {
        String note = "\n\n---\n⚠️ **Geri alındı:** ${utcNow()}, `${toVersion}`'e dönüldü " +
                      "(tag: `${env.TAG_NAME}`, Jenkins: ${env.BUILD_URL})"
        githubApi.request('PATCH', "repos/${repo}/releases/${from.id}", [
            prerelease: true,
            body      : (from.body ?: '') + note
        ], rc.gh)
        echo "v${fromVersion} Release'i pre-release olarak işaretlendi."
    }

    Map to = releaseOf(rc, "v${toVersion}")
    if (to && !to.prerelease) {
        githubApi.request('PATCH', "repos/${repo}/releases/${to.id}", [make_latest: 'true'], rc.gh)
    } else {
        echo "UYARI: v${toVersion} Release'i pre-release (daha önce geri alınmış); 'Latest' olarak işaretlenmedi."
    }
}

// ------------------------------------------------------------------ yardımcılar

/** Tag build'inin commit status'u (ör. release/1.0.5/test). */
void setStatus(Map rc, String state, String description) {
    if (env.RELEASE_CONTEXT) {
        notifyGitHub(rc.gh + [state: state, context: env.RELEASE_CONTEXT, description: description])
    }
}

String statusContext(String version, String stage) {
    return "release/${version}/${stage}"
}

String imageTag(String version, String stage) {
    return stage == 'prod' ? version : "${version}-${stage}"
}

String remoteTagCommit(Map rc, String tag) {
    return bash(rc, "Uzak tag kontrolü: ${tag}", 'remote_tag_commit "$Q_REF"', ["Q_REF=${tag}"], true)
}

String imageDigest(Map rc, String tag) {
    return bash(rc, "Registry kontrolü: ${rc.image}:${tag}", 'image_digest "$Q_REF"', ["Q_REF=${tag}"], true)
}

/** Image'ın build edildiği commit (org.opencontainers.image.revision etiketi). */
String imageRevision(Map rc, String tag) {
    def manifest = parseJson(registryGet(rc, 'manifest', tag))
    if (manifest.manifests) {
        // Çok platformlu index: attestation'lar (platform unknown/unknown) dışındaki ilk image
        String image = platformImage(manifest.manifests)
        manifest = parseJson(registryGet(rc, 'manifest', image))
    }
    def config = parseJson(registryGet(rc, 'blob', manifest.config.digest))
    return config.config?.Labels?.get('org.opencontainers.image.revision')
}

/** Commit status'unun son durumu; status yoksa veya okunamadıysa null. */
String statusOf(Map rc, String sha, String context) {
    return githubApi.statusOf(sha, context, rc.gh)
}

/** Tag'e bağlı GitHub Release (id, body, prerelease, ...); yoksa null. */
Map releaseOf(Map rc, String tag) {
    Map r = githubApi.request('GET', "repos/${githubApi.repoSlug()}/releases/tags/${tag}", null,
                              rc.gh + [allowStatus: [404]])
    return r.status == 404 ? null : r.json
}

String utcNow() {
    return sh(returnStdout: true, script: 'date -u "+%Y-%m-%d %H:%M UTC"').trim()
}

/** common.sh + script'i GitHub credential'ı ile çalıştırır. */
def bash(Map rc, String label, String script, List vars = [], boolean stdout = false) {
    String full = libraryResource('devops/release/common.sh') + '\n' + script + '\n'
    def result
    withCredentials([usernamePassword(credentialsId: rc.githubCredentialsId, usernameVariable: 'GH_USER',
                                      passwordVariable: 'GH_TOKEN')]) {
        withEnv(["REPO_URL=${env.GIT_URL}", "IMAGE=${rc.image}"] + vars) {
            result = stdout ? sh(script: full, label: label, returnStdout: true).trim()
                            : sh(script: full, label: label)
        }
    }
    return result
}

private String registryGet(Map rc, String kind, String ref) {
    return bash(rc, "Registry: ${kind} ${ref}", 'registry_get "$Q_KIND" "$Q_REF"', ["Q_KIND=${kind}", "Q_REF=${ref}"], true)
}

private void setImageEnv(String targetTag, String digest, String ready) {
    env.TARGET_IMAGE_TAG = targetTag
    env.SOURCE_DIGEST    = digest ?: ''
    env.IMAGE_READY      = ready
}

private String git(String cmd) {
    return sh(returnStdout: true, script: cmd).trim()
}

private String gitOrEmpty(String cmd) {
    return sh(returnStdout: true, script: "${cmd} 2>/dev/null || true").trim()
}

/** Tag'den sürüm numarası ("1.2.3"); desene uymazsa null. */
@NonCPS
String versionOf(String tag, String pattern) {
    def m = tag =~ pattern
    return m.matches() ? "${m.group(1)}.${m.group(2)}.${m.group(3)}" : null
}

@NonCPS
private Object parseJson(String text) {
    return new JsonSlurperClassic().parseText(text)
}

/** Tag'in modu (dev | test | prod); hiçbir desene uymazsa null. */
@NonCPS
String tagMode(String tag) {
    return TAG_PATTERNS.find { name, pattern -> tag ==~ pattern }?.key
}

/** Çok platformlu index'te attestation'lar (platform unknown/unknown) dışındaki ilk image'ın digest'i. */
@NonCPS
private String platformImage(List manifests) {
    return manifests.find { it.platform?.os && it.platform.os != 'unknown' }?.digest
}
