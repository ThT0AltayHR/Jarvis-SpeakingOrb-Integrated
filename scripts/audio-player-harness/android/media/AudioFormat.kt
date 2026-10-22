package android.media

class AudioFormat private constructor() {
    class Builder {
        fun setSampleRate(value: Int) = this
        fun setChannelMask(value: Int) = this
        fun setEncoding(value: Int) = this
        fun build() = AudioFormat()
    }
    companion object {
        const val CHANNEL_OUT_MONO = 4
        const val ENCODING_PCM_16BIT = 2
    }
}
