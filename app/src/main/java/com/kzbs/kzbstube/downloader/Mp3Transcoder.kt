package com.kzbs.kzbstube.downloader

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.qiujuer.lame.Lame
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp3ConversionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Decodes the downloaded AAC stream with Android's media codec and encodes MP3 with LAME. */
object Mp3Transcoder {

    private const val BITRATE_KBPS = 192
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ROUNDS = 500

    suspend fun convert(inputFile: File, outputFile: File) = withContext(Dispatchers.IO) {
        outputFile.delete()
        outputFile.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Geçici klasör oluşturulamadı.") }

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: Lame? = null
        var output: BufferedOutputStream? = null
        var encodedBuffer = ByteArray(0)
        var encodedBytes = 0L

        try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw Mp3ConversionException("İndirilen ses akışı okunamadı.")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw Mp3ConversionException("Ses biçimi tanınamadı.")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            output = BufferedOutputStream(FileOutputStream(outputFile), 64 * 1024)

            fun createEncoder(format: MediaFormat): Lame {
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (channels !in 1..2) {
                    throw Mp3ConversionException("Bu ses akışının kanal sayısı MP3'e çevrilemiyor.")
                }
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (sampleRate !in 8_000..48_000) {
                    throw Mp3ConversionException("Ses örnekleme hızı desteklenmiyor.")
                }
                return Lame(
                    channels,
                    sampleRate,
                    sampleRate,
                    BITRATE_KBPS,
                    Lame.LameModel.JOINT_STEREO,
                    Lame.LameQuality.GOOD
                )
            }

            fun encodeOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo, format: MediaFormat) {
                if (encoder == null) encoder = createEncoder(format)

                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val pcm = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
                    position(info.offset)
                    limit(info.offset + info.size)
                }.slice().order(ByteOrder.LITTLE_ENDIAN)

                val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }

                val samples = when (pcmEncoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> {
                        val shortBuffer = pcm.asShortBuffer()
                        ShortArray(shortBuffer.remaining()).also(shortBuffer::get)
                    }
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        val floatBuffer = pcm.asFloatBuffer()
                        ShortArray(floatBuffer.remaining()) { index ->
                            (floatBuffer.get(index).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                        }
                    }
                    AudioFormat.ENCODING_PCM_8BIT -> ShortArray(pcm.remaining()) { index ->
                        (((pcm.get(index).toInt() and 0xff) - 128) shl 8).toShort()
                    }
                    else -> throw Mp3ConversionException("Ses kod çözücüsü desteklenmeyen PCM biçimi verdi.")
                }

                val frames = samples.size / channels
                if (frames == 0) return
                val activeEncoder = encoder ?: throw Mp3ConversionException("MP3 kodlayıcısı başlatılamadı.")
                val requiredCapacity = activeEncoder.getMp3bufferSize(frames)
                if (encodedBuffer.size < requiredCapacity) encodedBuffer = ByteArray(requiredCapacity)

                val count = if (channels == 1) {
                    activeEncoder.encode(samples, samples, frames, encodedBuffer)
                } else {
                    activeEncoder.encodeInterleaved(samples, frames, encodedBuffer)
                }
                if (count < 0) throw Mp3ConversionException("MP3 kodlaması sırasında hata oluştu.")
                if (count > 0) {
                    output.write(encodedBuffer, 0, count)
                    encodedBytes += count
                }
            }

            var inputEnded = false
            var outputEnded = false
            var idleRounds = 0
            val info = MediaCodec.BufferInfo()

            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                var madeProgress = false

                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: throw Mp3ConversionException("Ses çözücüsü giriş arabelleği vermedi.")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> madeProgress = true
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            val flags = info.flags
                            if (info.size > 0 && flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val outputFormat = decoder.outputFormat
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?: throw Mp3ConversionException("Ses çözücüsü çıkış arabelleği vermedi.")
                                encodeOutput(outputBuffer, info, outputFormat)
                            }
                            outputEnded = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                        madeProgress = true
                    }
                }

                idleRounds = if (madeProgress) 0 else idleRounds + 1
                if (idleRounds > MAX_IDLE_ROUNDS) {
                    throw Mp3ConversionException("Ses çözücüsü yanıt vermedi.")
                }
            }

            val activeEncoder = encoder ?: throw Mp3ConversionException("Ses verisi çözülemedi.")
            val flushBuffer = ByteArray(activeEncoder.getMp3bufferSize())
            val flushBytes = activeEncoder.flush(flushBuffer)
            if (flushBytes < 0) throw Mp3ConversionException("MP3 dosyası tamamlanamadı.")
            if (flushBytes > 0) {
                output.write(flushBuffer, 0, flushBytes)
                encodedBytes += flushBytes
            }
            output.flush()

            if (encodedBytes == 0L || outputFile.length() == 0L) {
                throw Mp3ConversionException("MP3 dosyası boş oluştu.")
            }
        } catch (cancelled: CancellationException) {
            outputFile.delete()
            throw cancelled
        } catch (error: Mp3ConversionException) {
            outputFile.delete()
            throw error
        } catch (error: Exception) {
            outputFile.delete()
            throw Mp3ConversionException("Ses MP3 biçimine dönüştürülemedi.", error)
        } catch (error: LinkageError) {
            outputFile.delete()
            throw Mp3ConversionException("MP3 kodlayıcısı bu cihazda başlatılamadı.", error)
        } finally {
            runCatching { output?.close() }
            runCatching { encoder?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }
}
