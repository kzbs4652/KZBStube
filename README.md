# KZBStube 🎬🎵
> **YouTube Video ve Müzik İndirici (Android)**  
> Sürüm: `v1.0.0`

KZBStube, YouTube üzerinden video (MP4) ve müzik (MP3) dosyalarını en yüksek kalitede, hızlı ve reklamsız bir şekilde indirmenizi sağlayan modern bir Android uygulamasıdır.

---

## 📲 APK İndirme (Kolay Kurulum)

Uygulamanın hazır kurulum APK'sı doğrudan **`apk/`** klasöründe yer almaktadır:

👉 **[📥 KZBStube.apk İndir](https://github.com/kzbs4652/KZBStube/raw/main/apk/KZBStube.apk)** *(Doğrudan indirme)*  
👉 **[📁 APK Klasörünü Görüntüle](https://github.com/kzbs4652/KZBStube/tree/main/apk)**  
👉 **[🚀 GitHub Releases Sayfası](https://github.com/kzbs4652/KZBStube/releases)**  

---

## ✨ Özellikler

- **Modern & Karanlık Tasarım:** Kırmızı ve siyah tonlarında, akıcı ve şık arayüz.
- **Açılış Animasyonu (Splash Screen):** Özel parlayan KZBStube logosu ve geçiş animasyonları.
- **Arama Motoru:**
  - Video başlığı / kelime ile doğrudan YouTube araması.
  - YouTube video linki (URL) veya Shorts linki yapıştırarak anında videoyu getirme.
  - Akıllı Pano (Clipboard) Algılama: YouTube'dan link kopyalandığında otomatik indirme önerisi.
- **Gelişmiş İndirme Seçenekleri:**
  - **Video (MP4):** 1080p FHD, 720p HD, 480p, 360p, 240p (sesiyle birleşik tam MP4).
  - **Müzik (MP3):** 320 kbps, 256 kbps, 128 kbps yüksek kaliteli ses akışı.
- **Canlı İndirme Ekranı:**
  - Anlık ilerleme çubuğu (%)
  - Gerçek zamanlı indirme hızı (MB/s)
  - Duraklatma, devam ettirme ve iptal etme.
- **İndirilenler Yöneticisi:**
  - "Videolar" ve "Müzikler" kategorilendirilmiş sekmeler.
  - Dahili ve harici medya oynatıcılarla tek tıkla açma.
- **Otomatik Uygulama İçi Güncelleme (In-App Updater):**
  - GitHub Releases API (`kzbs4652/KZBStube`) entegrasyonu.
  - Yeni sürüm yayınlandığında otomatik bildirim çubuğu veya açılır pencere.
  - Uygulama içinden tek tıkla yeni APK'yı indirip kurma.

---

## 🚀 Yazılım Güncelleme Sistemi (Nasıl Çalışır?)

1. Yeni bir özellik veya düzeltme yapıldığında GitHub'a yeni bir sürüm etiketi (Örn: `v1.0.1` veya `v1.1.0`) gönderilir.
2. GitHub Actions bulutta otomatik olarak `KZBStube.apk` dosyasını derler, `apk/` klasörüne ve Releases alanına yükler.
3. Uygulama açılışta ve **Ayarlar -> Güncellemeleri Denetle** alanından en son sürümü kontrol eder.
4. Yeni bir sürüm tespit edildiğinde kullanıcıya güncelleme bildirimi sunulur ve uygulama içinden kurulur.
