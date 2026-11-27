package com.guna.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceIdentityAvailabilityTest {
    @Test
    fun missingSpeakerModelDoesNotBlockSpeechRecognitionBehindEnrollmentGate() {
        assertFalse(VoiceIdentity.requiresEnrollment(modelAvailable = false, enrolled = false))
        assertFalse(VoiceIdentity.requiresEnrollment(modelAvailable = false, enrolled = true))
    }

    @Test
    fun availableSpeakerModelStillRequiresEnrollmentWhenNoProfileExists() {
        assertTrue(VoiceIdentity.requiresEnrollment(modelAvailable = true, enrolled = false))
        assertFalse(VoiceIdentity.requiresEnrollment(modelAvailable = true, enrolled = true))
    }

    @Test
    fun modelLookupRequiresTheExactRootAssetFilename() {
        assertTrue(VoiceIdentity.hasModelAsset(arrayOf(VoiceIdentity.MODEL_ASSET)))
        assertFalse(VoiceIdentity.hasModelAsset(arrayOf("model/${VoiceIdentity.MODEL_ASSET}")))
        assertFalse(VoiceIdentity.hasModelAsset(emptyArray()))
    }
}
