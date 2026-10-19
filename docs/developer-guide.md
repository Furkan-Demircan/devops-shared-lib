# Sürüm çıkarma kılavuzu (developer'lar için)

Jenkins'e erişmeniz gerekmez; her şey GitHub'dan yapılır. **Deploy'u tag'ler tetikler, merge'ler değil.**

Ortam branch'leri (`dev`, `test`, `main`) birbirinden bağımsızdır. Feature branch'inizi hangi ortama çıkacaksa o
branch'e PR ile merge edersiniz, sonra **o branch'in ucunu** sürüm tag'iyle işaretlersiniz. Jenkins her tag'de o
commit'ten image build eder ve ilgili ortama deploy eder.

```
feature ──PR──► dev   ── v1.0.5-dev  (dev'in ucu)  ──► dev ortamı         (developer)
feature ──PR──► test  ── v1.0.5-test (test'in ucu) ──► test ortamı        (testers)
feature ──PR──► main  ── v1.0.5      (main'in ucu) ──► prod + Release     (release-managers)
```

## Hangi tag, ne zaman, nereye

| Tag | Kim atar | Ne zaman | Nereye |
|---|---|---|---|
| `v1.0.5-dev` | Her developer | Feature `dev`'e merge edildikten sonra | `dev`'in ucu |
| `v1.0.5-test` | `testers` | 1.0.5 dev'de ✓ **ve** feature `test`'e merge edildikten sonra | `test`'in ucu |
| `v1.0.5` | `release-managers` | 1.0.5 test'te ✓ **ve** feature `main`'e merge edildikten sonra | `main`'in ucu |
| `rollback-1.0.4-20261019-1` | `release-managers` | Prod'daki sürüm geri alınacaksa | `v1.0.4`'ün commit'i |

Kurallar:
- **Önce merge, sonra tag.** Tag, ortam branch'inin kendi commit'ine atılmalıdır. Feature branch'teki bir commit'e
  veya başka bir branch'in commit'ine atılan tag reddedilir.
- **Sıra:** `v1.0.5-test` ancak 1.0.5 dev'e başarıyla çıktıysa, `v1.0.5` ancak 1.0.5 test'e başarıyla çıktıysa
  kabul edilir.
- **Tag'ler silinemez ve değiştirilemez.** Yanlış atılan tag o sürüm numarasını yakar; yeni numarayla dev'den
  başlayın. Sürüm numaralarının atlanması (1.0.5 → 1.0.7) normaldir.
- **Tag'ler annotated olmalıdır** (`git tag -a`). Lightweight tag'ler reddedilir.

## 1. Dev

Feature'ınızı `dev`'e PR ile merge edin (**Create a merge commit**; squash ve rebase kapalıdır), sonra:

```bash
git fetch origin --tags
git tag -a v1.0.5-dev origin/dev -m "Kullanıcı listesine filtre eklendi"
git push origin v1.0.5-dev
```

- Biçim: `vMAJOR.MINOR.PATCH-dev`, başında sıfır olmaz (`v1.0.05-dev` geçersiz).
- Jenkins build eder, test'leri çalıştırır, `1.0.5-dev` image'ını dev'e çıkarır. Sonuç commit'te
  `release/1.0.5/dev` status'u (✓/✗) ve bir yorum olarak görünür.
- Daha önce kullanılmış bir sürüm numarası reddedilir.

## 2. Test

Feature'ı `test`'e PR ile merge edin, sonra:

```bash
git fetch origin --tags
git tag -a v1.0.5-test origin/test -m "1.0.5 test'e"
git push origin v1.0.5-test
```

Jenkins şunlara bakar, biri tutmazsa durur:
- Commit `test`'in kendi geçmişinde olmalı (merge'den sonra `test`'in ucu).
- `v1.0.5-dev` var olmalı ve 1.0.5 dev'e başarıyla çıkmış olmalı (`release/1.0.5/dev` = ✓).
- `1.0.5-test` image'ı daha önce oluşmamış olmalı.

Kontroller geçerse Jenkins `test`'in ucundan build eder ve `1.0.5-test` image'ını test'e çıkarır.

**Test'te hata çıkarsa** aynı sürümü tekrar kullanmayın: düzeltmeyi yapın, yeni sürümle (`v1.0.6-dev` → …)
akışı baştan izleyin.

## 3. Prod

Feature'ı `main`'e PR ile merge edin, sonra:

```bash
git fetch origin --tags
git tag -a v1.0.5 origin/main -m "1.0.5 prod'a"
git push origin v1.0.5
```

Kontroller aynıdır: commit `main`'in geçmişinde olmalı, 1.0.5 test'e başarıyla çıkmış olmalı
(`release/1.0.5/test` = ✓). Jenkins `main`'in ucundan build eder, prod'a çıkarır ve bir **GitHub Release** oluşturur.

> **Dikkat:** Branch'ler bağımsız olduğu için `main`'in içeriği `test`'tekinden farklı olabilir (ör. bir feature
> yalnızca `main`'e merge edildiyse). Jenkins bu durumda durmaz, uyarır: farklı dosyaların listesi build loguna ve
> Release notuna "Test'te denenen koddan farklı dosyalar" başlığıyla yazılır. Prod'a çıkan her feature'ın önce
> `test`'e merge edilip denenmesi ekip kuralıdır.

## 4. Rollback

Prod'daki sürümde sorun varsa, **daha önce prod'a çıkmış** bir sürüme dönülür. Yeni build yapılmaz; o sürümün
image'ı yeniden deploy edilir.

```bash
git fetch origin --tags
# Sondaki tarih + sıra numarası tag'i benzersiz kılar
git tag -a rollback-1.0.4-20261019-1 'v1.0.4^{}' -m "1.0.5'te login hatası"
git push origin rollback-1.0.4-20261019-1
```

- Biçim: `rollback-<sürüm>-<YYYYMMDD>-<sıra>`, annotated olmalı. Commit, `v1.0.4`'ün commit'i olmalı.
- Jenkins şu durumlarda durur:
  - `v1.0.4`'ün GitHub Release'i yoksa (sürüm hiç prod'a başarıyla çıkmadıysa),
  - `1.0.4` image'ı registry'de yoksa,
  - prod'da zaten 1.0.4 çalışıyorsa.
- Aynı gün ikinci kez dönmek gerekirse sıra numarasını artırın (`...-20261019-2`).
- Jenkins prod'da o an çalışan sürümü (ör. 1.0.5) prod sunucusundaki container'dan okur. Onun Release'ini
  **pre-release** olarak işaretler ve notuna `Geri alındı: <tarih>, 1.0.4'e dönüldü` ekler. 1.0.4'ün Release'i
  yeniden "Latest" olur.
- Düzeltme normal akışla yeni bir sürüm olarak çıkar.

> **Veritabanı:** Rollback yalnızca uygulama image'ını geri alır, şemayı geri almaz. Yeni sürüm şemayı geriye
> uyumsuz değiştirdiyse (kolon silme/yeniden adlandırma) eski sürüm çalışmayabilir. Şema değişikliklerini geriye
> uyumlu yapın: önce ekleyin, eskisini sonraki sürümde kaldırın.

## 5. Releases sayfasını okumak

GitHub → **Releases** prod geçmişinin tek kaynağıdır. Release yalnızca prod deploy'u **başarılı olduktan sonra**
oluşur; prod tag'i (`v1.0.5`) var ama Release'i yoksa, o sürüm prod'a çıkamamış demektir.

| Görünüm | Anlamı |
|---|---|
| **Latest** rozeti | Prod'da şu an çalışan sürüm (rollback'ten sonra dönülen sürüm Latest olur) |
| Normal Release | Prod'a çıkmış ve geri alınmamış sürüm |
| **Pre-release** rozeti + "⚠️ Geri alındı" notu | Prod'a çıkmış ama geri alınmış (hatalı) sürüm |
| "Test'te denenen koddan farklı dosyalar" bölümü | Prod'a çıkan kod test'teki 1.0.5'ten farklıydı |

Her Release'in başında şunlar bulunur:
- prod'a çıkan image (`ghcr.io/...:1.0.5`) ve digest'i,
- deploy tarihi,
- önceki prod sürümü,
- Jenkins build bağlantısı.

Altında bir önceki prod sürümünden bu yana `main`'e merge edilen PR'lar listelenir.

Bir sürümün hangi ortamlara çıktığını tag'lenen commit'lerdeki status'lardan da görebilirsiniz:
`release/1.0.5/dev` (`v1.0.5-dev`'in commit'inde), `release/1.0.5/test`, `release/1.0.5/prod`.

## Sık karşılaşılan hatalar

| Jenkins mesajı | Ne yapmalı |
|---|---|
| `... annotated tag değil` | Yeni bir sürüm numarasıyla `git tag -a` kullanın |
| `... branch'inin kendi geçmişinde yok` | Tag merge'den önce, feature commit'ine veya yanlış branch'e atılmış. Sürüm yandı; yeni numarayla başlayın |
| `... tag'i yok: ... aşamasından geçmemiş` | Önceki ortamın tag'i atılmamış. Sırayı izleyin |
| `... başarıyla çıkmamış` | Önceki ortamın deploy'u başarısız. O tag'in commit'indeki ✗ işaretine ve Jenkins yorumuna bakın |
| `... daha önce çıkmış` / `... zaten var` | Bu sürüm yayınlanmış. Yeni bir sürüm numarası kullanın |
| Deploy veya sağlık kontrolü hatası | Geçici olabilir: Jenkins yöneticisinden aynı tag build'ini **Build Now** ile yeniden çalıştırmasını isteyin. Sürüm yanmaz; image zaten build edildiyse build atlanır |
