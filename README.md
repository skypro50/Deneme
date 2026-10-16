# EFK Music

EFK Studio'nun mavi temasında, YouTube Music benzeri bir Android uygulaması.
YouTube'dan şarkıyı **MP3** olarak telefona indirir, sonra **internetsiz** dinletir.

## Telefona kurulum

1. Telefondan şu bağlantıyı aç: **https://github.com/skypro50/Deneme/releases/latest**
2. `EFK-Music.apk` dosyasına dokun ve indir.
3. İndirilen dosyayı aç. Telefon "bilinmeyen kaynak" uyarısı verirse
   **Ayarlar → Bu kaynaktan izin ver** de ve **Yükle**'ye bas.
4. Uygulama ilk açılışta indirme motorunu hazırlar (10–30 saniye) ve
   kendini en son yt-dlp sürümüne günceller.

> Android 10 ve üstü gerekir. `EFK-Music.apk` 2017 sonrası telefonların neredeyse hepsinde çalışır.
> Çok eski bir telefonsa `EFK-Music-eski-telefonlar.apk`, emin değilsen `EFK-Music-universal.apk` dosyasını kur.

## Nasıl kullanılır

| Ne yapmak istiyorsun | Nasıl |
| --- | --- |
| Link ile indir | Ana Sayfa'daki kutuya YouTube linkini yapıştır → **MP3** |
| YouTube uygulamasından indir | Videoda **Paylaş → EFK Music** |
| Arayıp indir | **Keşfet** sekmesinde şarkı/sanatçı ara → indirme simgesi |
| Bütün oynatma listesini indir | Liste linkini (`playlist?list=...`) yapıştır |
| Dinle | **Kitaplık** → şarkıya dokun. Bildirimden ve kilit ekranından da kontrol edilir |
| Kaliteyi seç | Sağ üstteki ⚙️ → MP3 kalitesi (128 / 192 / 320 kbps) |

MP3'ler telefonda **Müzik / EFK Music** klasörüne kaydedilir. Uygulamayı silsen bile
şarkılar kalır, yeniden kurunca kitaplık kendini o klasörden geri yükler.

## YouTube'dan MP3 nasıl alınıyor?

Reklamlı ve sık kapanan dönüştürme sitelerine bağlı değil. Bu sitelerin çoğunun
arkasında da çalışan açık kaynak **[yt-dlp](https://github.com/yt-dlp/yt-dlp)**
doğrudan uygulamanın içinde, telefonda çalışıyor
([youtubedl-android](https://github.com/yausername/youtubedl-android) ile):

- **yt-dlp**, şarkının en iyi ses akışını YouTube'dan çeker.
- **QuickJS**, YouTube'un artık zorunlu tuttuğu JavaScript doğrulamasını çözer.
- **ffmpeg**, sesi MP3'e çevirir, kapak resmini kare kırpıp dosyaya gömer,
  şarkı adı ve sanatçı bilgisini ekler ("Sanatçı - Şarkı (Official Video)" → temiz etiket).

YouTube sık değiştiği için motor her gün en fazla bir kez kendini günceller.
İndirme hata verirse **İndirmeler → Güncelle** düğmesi çoğu sorunu çözer.

Yalnızca hakkına sahip olduğun ya da kişisel kullanım için izin verilen içerikleri indir.

## Geliştirici notları

- `app/src/main/java/...` → Kotlin: yt-dlp sarmalayıcısı (`Engine.kt`), indirme kuyruğu
  (`Downloads.kt`), kitaplık ve MediaStore (`Library.kt`), Media3 arka plan oynatıcı
  (`PlaybackService.kt`), WebView köprüsü (`WebBridge.kt`).
- `app/src/main/assets/web/` → arayüz (HTML/CSS/JS). Renkler `app.css` başındaki
  `:root` değişkenlerinde; EFK Studio paletini değiştirmek için oraya bak.
- Her `push`'ta GitHub Actions APK'yı derleyip **Releases**'e koyar
  (`.github/workflows/android.yml`). Commit mesajında `[skip release]` varsa sürüm yayınlanmaz.
- APK'lar `app/efk-release.jks` ile imzalanır; böylece her yeni sürüm eskisinin üzerine kurulur.
