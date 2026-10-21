# 🤖 JARVIS — Yeni Nesil Kişisel Android Yapay Zeka Asistanı

[![Android CI](https://github.com/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated/actions/workflows/build-apk.yml)
[![Latest Release](https://img.shields.io/github/v/release/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated?color=brightgreen&label=Son%20S%C3%BCr%C3%BCm)](https://github.com/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated/releases)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%29-blue.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg)](https://kotlinlang.org)
[![Gemini Live](https://img.shields.io/badge/AI-Google%20Gemini%20Live-orange.svg)](https://ai.google.dev)
[![Theme](https://img.shields.io/badge/Theme-AMOLED%20True%20Black-black.svg)](#)

> *"Ben Jarvis'im, kişisel asistanınım. Hazırım, ne üzerinde çalışıyoruz?"*

Merhaba! Jarvis projesine hoş geldiniz. Bu repo, bilim kurgu filmlerinden aşina olduğumuz fütüristik yapay zeka deneyimini cebinize taşıyan, tamamen yerli ve samimi bir vizyonla geliştirilmiş **yeni nesil hibrit bir Android asistanıdır**.

Jarvis; sıradan, soğuk ve mekanik bir chatbot değildir. Dinleyen, düşünen, araştıran, gören ve sizinle gerçek zamanlı ses akışıyla sohbet eden yaşayan bir zekadır.

---

## ✨ Öne Çıkan Özellikler

- 🔮 **Speaking Orb & 9 Farklı Thinking Orb**: Sesin tonuna ve asistanın o anki zihinsel durumuna göre canlı tepki veren büyüleyici 3D WebGL / Canvas animasyonları.
- 🎙️ **Gemini Live Gerçek Zamanlı Ses Deneyimi**: Ultra düşük gecikmeli WebSocket bağlantısı ile doğal, kesintisiz, iki yönlü sesli iletişim.
- 🧠 **Derin Düşünme & Durum Farkındalığı**: Jarvis ne yaptığını bilir; araştırırken dünya döner, kod çözerken rubik küpü şekillenir, dinlerken dalgalar hareket eder.
- 💬 **Zengin Sohbet & Oturum Yönetimi**: Çoklu sohbet oturumları, silinmeyen arşivler, şık Markdown formatı ve renkli kod blokları.
- 📥 **Tek Dokunuşla Kod / Dosya İndirme**: `filename=...` etiketiyle üretilen tüm kod ve metin dosyalarını doğrudan cihazınızın `Downloads/Jarvis` klasörüne kaydetme.
- 🌐 **Otonom Arka Plan Web Araştırması**: Tarayıcıyı açıp dikkatinizi dağıtmadan, arka planda web sayfalarını tarayıp özet çıkarma ve sohbete aktarma.
- 👁️ **Kamera Vizyonu (Gemini Vision)**: Canlı kamera görüntüsü üzerinden nesneleri, belgeleri ve ortamı analiz edebilme kabiliyeti.
- 📁 **Kapsamlı Dosya & Medya Analizcisi**: PDF, resim, ses, video kareleri ve arşiv dosyalarının içeriğini akıllıca ayrıştırma.
- 🖤 **AMOLED Saf Siyah (#000000) Tasarım**: Pil dostu, gözü yormayan, fütüristik neon vurgularla bezeli modern karanlık tema.
- 🗣️ **Kişiselleştirilmiş Türkçe Karşılama**: Ses profili oluşturulduktan sonra ilk açılışta sizi adınızla karşılayan akıcı Türkçe seslendirme.

---

## 🔮 Küre Mimarisi: Speaking Orb & 9 Thinking Orb

Jarvis'in görsel kalbi, ekranın merkezinde atan iki katmanlı orb mimarisidir. Üst panelde yer alan **Thinking Orb** asistanın iç durumunu gösterirken, altındaki **Speaking Orb** doğrudan sizinle konuşur ve ses dalgalarına göre rezonansa girer.

```
+---------------------------------------------------+
|               [ 9x THINKING ORB ]                 |
|       (Zihinsel durum, görev ve araştırma)        |
+---------------------------------------------------+
|                                                   |
|               (( SPEAKING ORB ))                  |
|          (Ses rezonansı ve reaktif 3D küre)        |
|                                                   |
+---------------------------------------------------+
```

### 9 Farklı Düşünce Geometrisi

| Durum | Görsel Form | Anlamı ve İşlevi |
| :--- | :--- | :--- |
| **working** | 🛰️ `orbits` | Arka planda genel veri işleme ve görev yürütme |
| **searching** | 🌍 `globe` | İnternette ve bilgi kaynaklarında derin araştırma |
| **solving** | 🎲 `rubik` | Algoritmik problem çözme ve mantıksal analiz |
| **listening** | 🌊 `wave` | Kullanıcının sesini pürüzsüz akustik dalgalarla dinleme |
| **connecting** | 🕸️ `web` | Nöral ağ ve sunucu bağlantı senkronizasyonu |
| **weaving** | 🧬 `braid` | Karmaşık bağlamları ve bellekleri bir araya örme |
| **composing** | 🎗️ `ribbon` | Akıcı yaratıcı metin, kod veya yanıt besteleme |
| **breathing** | 💫 `ring` | Boşta bekleme, canlı nefes alma nabzı |
| **shaping** | 🌌 `morph` | Çok boyutlu veri dönüşümü ve adaptasyon |

Tüm bu animasyonlar pil tüketimini minimumda tutmak adına Android donanım hızlandırmalı Canvas üzerinde **frame-rate capping** (kare sınırı) ile optimize edilmiştir.

---

## 💬 Gelişmiş Sohbet & Dosya Sistemi

Jarvis metin tabanlı etkileşimde sıradan bir mesajlaşma kutusu sunmaz:

1. **Çoklu Sohbet Geçmişi**: Birden fazla konuşma oturumu açabilir, geçmiş oturumlarınıza dilediğiniz zaman alt sayfadan (BottomSheet) erişebilirsiniz.
2. **Sözdizimi Vurgulu Kod Blokları**: Kotlin, Python, JS, HTML vb. dillerdeki kod blokları renklendirilir; bloğa tek dokunuşla tüm kodu panoya kopyalayabilirsiniz.
3. **Otomatik Dosya Kartları**: Jarvis bir dosya ürettiğinde (örn: ```python filename=script.py), arayüzde özel bir dosya kartı belirir. İster kopyalayın, ister **İndir** butonuna basarak doğrudan `Downloads/Jarvis` dizinine yazdırın.
4. **Toz Efekti (Dust Reveal)**: Asistan yanıtları ekrana toz zerreciklerinden şekillenircesine estetik bir animasyonla gelir.

---

## 🔍 Otonom Web Araştırması

Jarvis'e *"Web'de son yapay zeka gelişmelerini ara"* dediğinizde:
- Telefonunuzda Chrome'u açıp sizi uygulamadan koparmaz.
- Dahili web motoruyla arka planda arama yapar, sayfaları inceler ve filtreler.
- Durumunu Thinking Orb üzerinde `searching` (küre) olarak gösterir.
- Elde ettiği güvenilir kaynaklı özeti doğrudan sohbetinize taşır.

*(Not: Sadece siz açıkça "Tarayıcıda aç" veya "Chrome'da göster" dediğinizde harici tarayıcı tetiklenir).*

---

## 📁 Medya ve Dosya Analizi

Sohbete eklediğiniz dosyaları Jarvis derinlemesine inceler:
- **PDF Dokümanları**: Sayfaları yüksek çözünürlükte tarar ve metinlerini ayıklar.
- **Görseller & Fotoğraflar**: Nesne tanıma, metin okuma (OCR) ve sahne analizi.
- **Videolar**: Ağır video akışlarını dondurmadan stratejik kare örneklemeleri yaparak videonun konusunu çıkarır.
- **Ses Dosyaları**: Android codec'leri ile ses frekans analizleri ve özetleme.
- **Arşivler (ZIP vb.)**: Dosya yapısını ve kod dizilimini çözümler.

---

## 🛠️ Teknik Altyapı ve Teknolojiler

- **Dil**: %100 Modern Kotlin (Coroutines, Flow, StateFlow)
- **Mimari**: MVVM (Model-View-ViewModel) + Repository Pattern + Clean Architecture
- **Ağ & İletişim**: OkHttp WebSocket (Gemini Live Full-Duplex Audio), Retrofit
- **Veritabanı & Bellek**: Android Jetpack Room (Kullanıcı tercihleri, oturumlar ve vektörel hafıza)
- **Kamera & Görsel**: CameraX API (Düşük gecikmeli görüntü akışı)
- **Grafik & UI**: WebView Hardware Acceleration, Three.js, HTML5 Canvas 2D, Material Design 3
- **Ses & Konuşma**: Android SpeechRecognizer, Vosk Offline Engine & Google Text-to-Speech

---

## 🚀 Projeyi Derleme & Çalıştırma

### Gereksinimler
- Android Studio Iguana / Jellyfish veya daha yenisi
- JDK 17 (Temurin önerilir)
- Android SDK 34 (Android 14)
- Minimum Cihaz Sürümü: Android 8.0 (API 26)

### Hızlı Başlangıç

1. **Depoyu Klonlayın**:
   ```bash
   git clone https://github.com/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated.git
   cd Jarvis-SpeakingOrb-Integrated
   ```

2. **API Anahtarlarını Tanımlayın**:
   `local.properties.example` dosyasını `local.properties` olarak kopyalayın ve Gemini API anahtarınızı ekleyin:
   ```properties
   gemini.api.key=YOUR_GEMINI_API_KEY
   ```

3. **Debug APK Derleyin**:
   ```bash
   ./gradlew assembleDebug
   ```
   Derlenen APK çıktısı `app/build/outputs/apk/debug/app-debug.apk` konumunda oluşacaktır.

---

## 📦 Otomatik CI/CD & GitHub Actions

Bu depoda tam otomatik bir CI/CD pipeline'ı bulunmaktadır. 
- Her `main` dalına yapılan push işleminde GitHub Actions otomatik olarak devreye girer.
- Gradle derleme adımlarını çalıştırır.
- Derleme başarılı olduğunda üretilen APK dosyasını otomatik olarak **GitHub Releases** sekmesine yeni bir sürüm etiketiyle yükler.

En güncel hazır APK'ları indirmek için her zaman [Releases](https://github.com/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated/releases) sayfasını ziyaret edebilirsiniz.

---

## 🤝 Katkıda Bulunma

Jarvis açık fikirli, sürekli öğrenen ve toplulukla büyüyen bir projedir!
- Bir hata mı buldunuz? [Issue](https://github.com/ThT0AltayHR/Jarvis-SpeakingOrb-Integrated/issues) açın.
- Yeni bir fikir veya özellik mi eklemek istiyorsunuz? Fork'layıp bir Pull Request gönderin.

---

## 🖤 Geliştirici Notu

Jarvis sadece satırlardan oluşan bir kod yığını değil; kullanıcıyla bağ kurabilen, dinleyen ve çözüm üreten bir yol arkadaşı olması amacıyla sevgiyle geliştirildi. Geri bildirimleriniz, fikirleriniz ve desteğiniz bu projeyi daha da ileri taşıyacaktır.

Keyifli kullanımlar dileriz! 🚀
