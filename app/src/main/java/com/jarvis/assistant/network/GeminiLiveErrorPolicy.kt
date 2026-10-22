package com.jarvis.assistant.network

/** Distinguishes credential, permission, quota, malformed-request and transient failures. */
internal object GeminiLiveErrorPolicy {
    enum class Category { INVALID_API_KEY, AUTHENTICATION, ACCESS_DENIED, QUOTA, INVALID_REQUEST, TRANSIENT }

    fun classify(statusCode: Int, message: String): Category {
        // Google may send enum-style codes (PERMISSION_DENIED, INVALID_ARGUMENT,
        // RESOURCE_EXHAUSTED) or human-readable messages. Normalize separators so both
        // wire formats receive the same classification.
        val text = message.lowercase().replace(Regex("[_-]+"), " ")
        if (text.contains("api key invalid") || text.contains("api key not valid") ||
            text.contains("invalid api key") || text.contains("api key is invalid")) {
            return Category.INVALID_API_KEY
        }
        if (statusCode == 429 || text.contains("resource exhausted") ||
            text.contains("quota") || text.contains("rate limit")) {
            return Category.QUOTA
        }
        if (statusCode == 401 || text.contains("unauthenticated") || text.contains("authentication failed")) {
            return Category.AUTHENTICATION
        }
        if (statusCode == 403 || text.contains("permission denied") || text.contains("access denied")) {
            return Category.ACCESS_DENIED
        }
        if (statusCode == 400 || text.contains("invalid argument") || text.contains("model not found") ||
            text.contains("model is not supported") || text.contains("unsupported model")) {
            return Category.INVALID_REQUEST
        }
        return Category.TRANSIENT
    }
}
