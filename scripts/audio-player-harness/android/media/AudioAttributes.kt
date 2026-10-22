package android.media

class AudioAttributes private constructor() {
    class Builder {
        fun setUsage(value: Int) = this
        fun setContentType(value: Int) = this
        fun build() = AudioAttributes()
    }
    companion object {
        const val USAGE_VOICE_COMMUNICATION = 2
        const val CONTENT_TYPE_SPEECH = 1
    }
}
