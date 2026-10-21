package com.jarvis.assistant.ui.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Content-based attachment inspection. It never extracts archive paths to disk. */
object ChatAttachmentAnalyzer {
    private const val TAG = "JarvisAttachment"
    private const val MAX_TEXT_BYTES = 768 * 1024
    private const val MAX_ARCHIVE_TEXT_BYTES = 640 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 80
    private const val MAX_FRAME_EDGE = 768

    enum class DetailMode { QUICK, DETAILED }

    data class SelectedFile(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long?
    )

    data class Analysis(
        val file: SelectedFile,
        val extractedText: String = "",
        val imageFrames: List<ByteArray> = emptyList(),
        val audioPcm16k: List<ByteArray> = emptyList(),
        val durationMs: Long? = null,
        val notes: List<String> = emptyList()
    )

    fun describe(context: Context, uri: Uri): SelectedFile {
        val resolver = context.contentResolver
        var name = "ek_dosya"
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                }
            }
        } catch (_: Exception) { }
        val mime = resolver.getType(uri)?.lowercase(Locale.ROOT)
            ?: guessMimeByName(name)
        return SelectedFile(uri, name, mime, size)
    }

    fun videoDurationMs(context: Context, file: SelectedFile): Long? {
        if (!isVideo(file)) return null
        return try {
            MediaMetadataRetriever().useRetriever(context, file.uri) { retriever ->
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        } catch (_: Exception) { null }
    }

    suspend fun analyze(context: Context, file: SelectedFile, mode: DetailMode): Analysis = withContext(Dispatchers.IO) {
        val contentType = sniffType(context.contentResolver, file)
        when {
            contentType == Kind.IMAGE -> analyzeImage(context, file)
            contentType == Kind.VIDEO -> analyzeVideo(context, file, mode)
            contentType == Kind.AUDIO -> analyzeAudio(context, file, mode)
            contentType == Kind.PDF -> analyzePdf(context, file, mode)
            contentType == Kind.ARCHIVE || isOfficeDocument(file.name) -> analyzeArchive(context, file)
            else -> analyzeTextOrBinary(context.contentResolver, file, contentType)
        }
    }

    private enum class Kind { IMAGE, VIDEO, AUDIO, PDF, ARCHIVE, TEXT, BINARY }

    private fun sniffType(resolver: ContentResolver, file: SelectedFile): Kind {
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val header = try { resolver.openInputStream(file.uri)?.use { it.readNBytesCompat(32) } ?: byteArrayOf() } catch (_: Exception) { byteArrayOf() }
        return when {
            header.startsWithBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)) || ext == "pdf" -> Kind.PDF
            header.startsWithBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) || ext in setOf("zip", "docx", "xlsx", "pptx", "odt", "ods", "odp") -> Kind.ARCHIVE
            file.mimeType.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif") -> Kind.IMAGE
            file.mimeType.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpeg", "mpg", "ts") -> Kind.VIDEO
            file.mimeType.startsWith("audio/") || ext in setOf("mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "amr") -> Kind.AUDIO
            looksLikeText(header) || file.mimeType.startsWith("text/") || ext in setOf("txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "jsx", "tsx", "kt", "java", "py", "c", "h", "cpp", "rs", "go", "php", "sql", "yaml", "yml", "toml", "ini", "csv", "log", "sh", "gradle", "properties", "kt", "kts") -> Kind.TEXT
            else -> Kind.BINARY
        }
    }

    private fun analyzeImage(context: Context, file: SelectedFile): Analysis {
        val frame = decodeImage(context, file.uri)
        return Analysis(file, imageFrames = listOfNotNull(frame), notes = if (frame == null) listOf("Görsel çözümlenemedi; dosya adı yerine içerik imzası kontrol edildi.") else emptyList())
    }

    private fun analyzeVideo(context: Context, file: SelectedFile, mode: DetailMode): Analysis {
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<ByteArray>()
        val notes = mutableListOf<String>()
        var durationMs: Long? = null
        try {
            retriever.setDataSource(context, file.uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val duration = (durationMs ?: 0L).coerceAtLeast(0L)
            val frameCount = if (mode == DetailMode.QUICK) 5 else if (duration > 0) ((duration / 12_000L).toInt() + 1).coerceIn(8, 12) else 8
            val positions = if (duration <= 0L) listOf(0L) else (0 until frameCount).map { i ->
                if (frameCount == 1) 0L else (duration * i / (frameCount - 1)).coerceIn(0L, (duration - 1).coerceAtLeast(0L))
            }.distinct()
            positions.forEach { positionMs ->
                try {
                    val bmp = retriever.getFrameAtTime(positionMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        encodeBitmapScaled(bmp)?.let(frames::add)
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                } catch (e: Exception) { Log.w(TAG, "Video frame extraction failed at $positionMs ms", e) }
            }
        } catch (e: Exception) {
            notes += "Video kareleri çıkarılamadı: ${e.message ?: "biçim desteklenmiyor"}"
        } finally { try { retriever.release() } catch (_: Exception) { } }

        val audio = extractAudioSamples(context, file.uri, durationMs, mode)
        if (audio.isEmpty()) notes += "Görüntü kareleri incelenecek; ses parçası çözümlenemedi veya videoda ses yok."
        if (mode == DetailMode.QUICK && (durationMs ?: 0L) > 120_000L) notes += "Hızlı özet modu: tüm video yerine zamana yayılmış seçili kareler ve kısa ses örnekleri eklendi."
        if (mode == DetailMode.DETAILED) notes += "Detaylı mod: görüntü örnekleri tüm süreye yayılır; ses örnekleri farklı bölümlerden alınır. Çok uzun videolarda tüm karelerin tek tek işlenmesi yerine örnekleme kullanılır."
        val detail = "Tür: video\nSüre: ${formatDuration(durationMs)}\nÇıkarılan kare sayısı: ${frames.size}\nSes örnekleri: ${audio.size}"
        return Analysis(file, extractedText = detail, imageFrames = frames, audioPcm16k = audio, durationMs = durationMs, notes = notes)
    }

    private fun analyzeAudio(context: Context, file: SelectedFile, mode: DetailMode): Analysis {
        val duration = try {
            MediaMetadataRetriever().useRetriever(context, file.uri) { r -> r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
        } catch (_: Exception) { null }
        val samples = extractAudioSamples(context, file.uri, duration, mode, standalone = true)
        val notes = if (samples.isEmpty()) listOf("Ses içeriği çözümlenemedi; yalnızca dosya meta verisi gönderilecek.") else listOf("Ses, modelin anlayabilmesi için 16 kHz mono PCM örneklerine dönüştürüldü.")
        return Analysis(file, extractedText = "Tür: ses\nSüre: ${formatDuration(duration)}\nSes örnekleri: ${samples.size}", audioPcm16k = samples, durationMs = duration, notes = notes)
    }

    private fun analyzePdf(context: Context, file: SelectedFile, mode: DetailMode): Analysis {
        val frames = mutableListOf<ByteArray>()
        val notes = mutableListOf<String>()
        try {
            val pfd = context.contentResolver.openFileDescriptor(file.uri, "r") ?: throw IllegalStateException("Dosya açılamadı")
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val count = renderer.pageCount
                    if (count == 0) return Analysis(file, extractedText = "PDF boş görünüyor.")
                    val wanted = if (mode == DetailMode.QUICK) listOf(0, count / 2, count - 1).distinct() else (0 until minOf(count, 8)).toList()
                    wanted.forEach { index ->
                        renderer.openPage(index).use { page ->
                            val scale = minOf(1f, 900f / page.width.toFloat(), 1200f / page.height.toFloat())
                            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            encodeBitmapScaled(bitmap)?.let(frames::add)
                            bitmap.recycle()
                        }
                    }
                    notes += "PDF metin katmanı doğrudan çıkarılmayan sayfa görüntülerine dönüştürüldü; seçilen sayfalar görsel olarak analiz edilir. Toplam sayfa: $count."
                }
            }
        } catch (e: Exception) {
            notes += "PDF sayfaları görüntüye dönüştürülemedi: ${e.message ?: "PDF biçimi desteklenmiyor"}"
        }
        return Analysis(file, extractedText = "Tür: PDF belge\nDosya boyutu: ${formatBytes(file.sizeBytes)}", imageFrames = frames, notes = notes)
    }

    private fun analyzeArchive(context: Context, file: SelectedFile): Analysis {
        val output = StringBuilder()
        val notes = mutableListOf<String>()
        var entryCount = 0
        var collected = 0
        try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (entryCount < MAX_ARCHIVE_ENTRIES) {
                        val entry = zip.nextEntry ?: break
                        entryCount++
                        if (entry.isDirectory) { zip.closeEntry(); continue }
                        output.append("\n--- ").append(entry.name.take(180)).append(" ---\n")
                        val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        val shouldRead = isLikelyTextExtension(ext) || (isOfficeDocument(file.name) && ext == "xml")
                        if (shouldRead && collected < MAX_ARCHIVE_TEXT_BYTES) {
                            val remaining = (MAX_ARCHIVE_TEXT_BYTES - collected).coerceAtMost(96 * 1024)
                            val bytes = zip.readBounded(remaining)
                            collected += bytes.size
                            val decoded = decodeText(bytes)
                            output.append(if (ext == "xml" || ext == "rels") stripXml(decoded) else decoded.take(remaining))
                            output.append('\n')
                        } else {
                            output.append(if (shouldRead) "[İçerik boyut sınırı nedeniyle devamı atlandı]" else "[İkili içerik; ad ve arşiv metaverisi incelendi]").append('\n')
                            if (zip.skipRemainingEntryBounded(4 * 1024 * 1024)) {
                                notes += "Arşiv içindeki tek bir öğe çok büyük olduğu için güvenli biçimde daha fazla açılmadı."
                                break
                            }
                        }
                        if (collected >= MAX_ARCHIVE_TEXT_BYTES) {
                            notes += "Arşiv metin sınırına ulaşıldı; kalan veriler açılmadan bırakıldı."
                            break
                        }
                        zip.closeEntry()
                    }
                    if (entryCount >= MAX_ARCHIVE_ENTRIES) notes += "Arşivdeki dosya sayısı sınırı (${MAX_ARCHIVE_ENTRIES}) aşıldığı için kalan öğeler atlandı."
                    if (collected >= MAX_ARCHIVE_TEXT_BYTES) notes += "Arşiv metni güvenli bellek kullanımı için yaklaşık 640 KB ile sınırlandı."
                }
            } ?: notes.add("Arşiv açılamadı.")
        } catch (e: Exception) {
            notes += "Arşiv içeriği okunurken hata oluştu: ${e.message ?: "bozuk veya parola korumalı arşiv"}"
        }
        if (isOfficeDocument(file.name)) output.insert(0, "Ofis belgesi içindeki XML/metin parçaları çıkarıldı. Biçimlendirme, yerleşim ve gömülü görseller tamamen korunmayabilir.\n")
        if (output.isBlank()) output.append("Arşiv listelendi ancak okunabilir metin bulunamadı.")
        return Analysis(file, extractedText = output.toString().take(MAX_ARCHIVE_TEXT_BYTES + 10_000), notes = notes)
    }

    private fun analyzeTextOrBinary(resolver: ContentResolver, file: SelectedFile, kind: Kind): Analysis {
        val bytes = try { resolver.openInputStream(file.uri)?.use { it.readNBytesCompat(MAX_TEXT_BYTES) } ?: byteArrayOf() } catch (e: Exception) {
            return Analysis(file, notes = listOf("Dosya açılamadı: ${e.message ?: "okuma hatası"}"))
        }
        val decoded = decodeText(bytes)
        val textConfidence = bytes.isNotEmpty() && decoded.count { it == '\u0000' } < maxOf(1, bytes.size / 1000) && decoded.count { it == '\uFFFD' } < maxOf(2, decoded.length / 80)
        return if (kind == Kind.TEXT || textConfidence) {
            val truncated = (file.sizeBytes ?: bytes.size.toLong()) > bytes.size
            Analysis(file, extractedText = decoded, notes = if (truncated) listOf("Metin dosyasının ilk ${formatBytes(bytes.size.toLong())} kısmı incelendi; çok büyük dosyalar bellek güvenliği için sınırlandı.") else emptyList())
        } else {
            val preview = bytes.take(96).joinToString(" ") { "%02X".format(it) }
            Analysis(file, extractedText = "Dosya adı: ${file.name}\nMIME: ${file.mimeType}\nİçerik türü: tanınmayan ikili dosya\nBaşlangıç baytları (hex): $preview", notes = listOf("Bu biçim için güvenli yerel ayrıştırıcı bulunamadı. Dosya adıyla yetinilmedi; içerik imzası ve başlangıç baytları incelendi, ancak dosyanın tamamı çözümlenmiş gibi gösterilmiyor."))
        }
    }

    private fun decodeImage(context: Context, uri: Uri): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_FRAME_EDGE * 2 || bounds.outHeight / sample > MAX_FRAME_EDGE * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return encodeBitmapScaled(bitmap).also { bitmap.recycle() }
    }

    private fun encodeBitmapScaled(source: Bitmap): ByteArray? {
        val maxEdge = MAX_FRAME_EDGE
        val scale = minOf(1f, maxEdge / source.width.toFloat(), maxEdge / source.height.toFloat())
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
        return try {
            val out = ByteArrayOutputStream()
            var quality = 84
            do {
                out.reset()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                quality -= 10
            } while (out.size() > 420 * 1024 && quality >= 54)
            out.toByteArray().takeIf { it.isNotEmpty() }
        } finally { if (bitmap !== source && !bitmap.isRecycled) bitmap.recycle() }
    }

    private fun extractAudioSamples(context: Context, uri: Uri, durationMs: Long?, mode: DetailMode, standalone: Boolean = false): List<ByteArray> {
        val duration = durationMs ?: 0L
        if (duration <= 0L) return listOfNotNull(decodeAudioSegment(context, uri, 0, if (mode == DetailMode.QUICK) 4_000 else 10_000))
        val segmentDuration = when {
            standalone && mode == DetailMode.QUICK -> 4_000L
            standalone -> 8_000L
            mode == DetailMode.QUICK -> 2_500L
            else -> 3_000L
        }
        val maxSegments = when {
            standalone && mode == DetailMode.QUICK -> 1
            standalone -> 4
            mode == DetailMode.QUICK -> 3
            else -> 6
        }
        val count = minOf(maxSegments, maxOf(1, kotlin.math.ceil(duration.toDouble() / 60_000.0).toInt()))
        val starts = if (count == 1) listOf(0L) else (0 until count).map { i ->
            ((duration - segmentDuration).coerceAtLeast(0L) * i / (count - 1)).coerceAtLeast(0L)
        }.distinct()
        return starts.mapNotNull { start -> try { decodeAudioSegment(context, uri, start, segmentDuration) } catch (e: Exception) { Log.w(TAG, "Audio sample decode failed", e); null } }
    }

    /** Decode a short audio segment and resample it to the Live API's 16 kHz mono PCM format. */
    private fun decodeAudioSegment(context: Context, uri: Uri, startMs: Long, lengthMs: Long): ByteArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcmOut = ByteArrayOutputStream()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(i)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = candidate; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val startUs = startMs * 1000L
            val endUs = (startMs + lengthMs) * 1000L
            var inputEnded = false
            var outputEnded = false
            var guard = 0
            var outSampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var outChannels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            while (!outputEnded && guard++ < 20_000) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        val pts = extractor.sampleTime
                        if (size < 0 || pts > endUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        outSampleRate = outFormat.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, outSampleRate)
                        outChannels = outFormat.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, outChannels)
                        pcmEncoding = outFormat.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outputIndex >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs <= endUs) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val raw = ByteArray(info.size)
                                buffer.get(raw)
                                val pcm = convertPcmTo16kMono(raw, outSampleRate, outChannels, pcmEncoding)
                                if (info.presentationTimeUs < startUs && outSampleRate > 0) {
                                    val preStartMs = ((startUs - info.presentationTimeUs) / 1000L).coerceAtLeast(0L)
                                    val bytesToSkip = (preStartMs * 16L * 2L).coerceAtMost(pcm.size.toLong()).toInt() and -2
                                    if (bytesToSkip < pcm.size) pcmOut.write(pcm, bytesToSkip, pcm.size - bytesToSkip)
                                } else pcmOut.write(pcm)
                            }
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs > endUs
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            return pcmOut.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to decode audio segment", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun convertPcmTo16kMono(bytes: ByteArray, sampleRate: Int, channels: Int, encoding: Int): ByteArray {
        if (bytes.isEmpty() || sampleRate <= 0 || channels <= 0) return byteArrayOf()
        val isFloat = encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frameSize = bytesPerSample * channels
        val frameCount = bytes.size / frameSize
        if (frameCount <= 0) return byteArrayOf()
        val out = ByteArrayOutputStream(((frameCount.toDouble() * 16_000.0 / sampleRate) * 2).toInt().coerceAtLeast(2))
        var sourceIndex = 0.0
        val step = sampleRate.toDouble() / 16_000.0
        while (sourceIndex < frameCount) {
            val frame = sourceIndex.toInt().coerceAtMost(frameCount - 1)
            var sum = 0L
            for (channel in 0 until channels) {
                val offset = (frame * channels + channel) * bytesPerSample
                val sample = if (isFloat) {
                    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float.coerceIn(-1f, 1f).let { (it * 32767f).toInt() }
                } else {
                    (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)
                }
                sum += sample.toShort().toInt()
            }
            val mono = (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out.write(mono.toInt() and 0xFF)
            out.write((mono.toInt() shr 8) and 0xFF)
            sourceIndex += step
        }
        return out.toByteArray()
    }

    private fun isVideo(file: SelectedFile): Boolean = file.mimeType.startsWith("video/") || file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "mpeg", "mpg", "ts")
    private fun isOfficeDocument(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")
    private fun isLikelyTextExtension(ext: String): Boolean = ext in setOf("txt", "md", "json", "xml", "html", "htm", "css", "js", "ts", "tsx", "jsx", "kt", "java", "py", "c", "h", "cpp", "rs", "go", "php", "sql", "yaml", "yml", "toml", "ini", "csv", "log", "sh", "gradle", "properties", "kts", "xml", "rels", "svg")
    private fun guessMimeByName(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "pdf" -> "application/pdf"; "zip" -> "application/zip"; "json" -> "application/json"; "xml" -> "application/xml"; else -> "application/octet-stream"
    }
    private fun looksLikeText(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.take(32).none { it == 0.toByte() } && bytes.take(32).count { (it.toInt() and 0xFF) < 9 || ((it.toInt() and 0xFF) in 14..31) } <= 2
    private fun decodeText(bytes: ByteArray): String = try { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(bytes)).toString() } catch (_: Exception) { String(bytes, Charset.forName("UTF-8")) }
    private fun stripXml(text: String): String = text.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace(Regex("\\s+"), " ").trim()
    private fun formatBytes(bytes: Long?): String = if (bytes == null) "bilinmiyor" else when { bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0)); bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0); else -> "$bytes B" }
    private fun formatDuration(ms: Long?): String = if (ms == null) "bilinmiyor" else "%d:%02d".format(ms / 60_000L, (ms / 1000L) % 60L)

    private inline fun <T> MediaMetadataRetriever.useRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        try { setDataSource(context, uri); return block(this) } finally { try { release() } catch (_: Exception) { } }
    }
    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int = try { if (containsKey(key)) getInteger(key) else fallback } catch (_: Exception) { fallback }
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            out.write(buffer, 0, read); total += read
        }
        return out.toByteArray()
    }
    private fun ZipInputStream.readBounded(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (total < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - total))
            if (read <= 0) break
            out.write(buffer, 0, read); total += read
        }
        return out.toByteArray()
    }
    private fun ZipInputStream.skipRemainingEntryBounded(limit: Int): Boolean {
        val buffer = ByteArray(4096); var total = 0
        while (total < limit) { val n = read(buffer, 0, minOf(buffer.size, limit-total)); if (n <= 0) return false; total += n }
        return true
    }
    private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
