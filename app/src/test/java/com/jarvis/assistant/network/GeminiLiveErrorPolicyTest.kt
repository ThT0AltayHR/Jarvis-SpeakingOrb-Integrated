package com.jarvis.assistant.network

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiLiveErrorPolicyTest {
    @Test fun generic400IsInvalidRequestNotInvalidApiKey() {
        assertEquals(GeminiLiveErrorPolicy.Category.INVALID_REQUEST, GeminiLiveErrorPolicy.classify(400, "Invalid JSON in setup"))
    }

    @Test fun explicitInvalidKeyMessageIsClassifiedAsKeyError() {
        assertEquals(GeminiLiveErrorPolicy.Category.INVALID_API_KEY, GeminiLiveErrorPolicy.classify(403, "API_KEY_INVALID"))
    }

    @Test fun forbiddenProjectAccessIsNotReportedAsInvalidKey() {
        assertEquals(GeminiLiveErrorPolicy.Category.ACCESS_DENIED, GeminiLiveErrorPolicy.classify(403, "Permission denied for model"))
    }

    @Test fun rateLimitIsQuota() {
        assertEquals(GeminiLiveErrorPolicy.Category.QUOTA, GeminiLiveErrorPolicy.classify(429, "RESOURCE_EXHAUSTED"))
    }

    @Test fun enumStylePermissionDeniedCloseReasonIsAccessDenied() {
        assertEquals(GeminiLiveErrorPolicy.Category.ACCESS_DENIED, GeminiLiveErrorPolicy.classify(0, "PERMISSION_DENIED: project access denied"))
    }

    @Test fun enumStyleInvalidArgumentIsRequestError() {
        assertEquals(GeminiLiveErrorPolicy.Category.INVALID_REQUEST, GeminiLiveErrorPolicy.classify(0, "INVALID_ARGUMENT: malformed setup"))
    }

    @Test fun unknownNetworkFailureIsTransient() {
        assertEquals(GeminiLiveErrorPolicy.Category.TRANSIENT, GeminiLiveErrorPolicy.classify(0, "Socket timeout"))
    }
}
