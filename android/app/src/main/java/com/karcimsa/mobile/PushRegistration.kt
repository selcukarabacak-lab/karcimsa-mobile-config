package com.karcimsa.mobile

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging

object PushRegistration {
    const val PREFS_NAME = "karcimsa_mobile"
    const val OLD_TOPIC = "karcimsa_ops"
    const val TOPIC_SALES = "karcimsa_sales"
    const val TOPIC_CEM1 = "karcimsa_cem1"
    const val PREF_NOTIFY_SALES = "notify_sales"
    const val PREF_NOTIFY_CEM1 = "notify_cem1"
    const val PREF_REGISTRATION_OK = "push_registration_ok"
    const val PREF_REGISTRATION_AT = "push_registration_at"
    const val PREF_REGISTRATION_ERROR = "push_registration_error"

    data class SyncResult(
        val success: Boolean,
        val message: String
    )

    fun sync(
        context: Context,
        onComplete: ((SyncResult) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val messaging = FirebaseMessaging.getInstance()

        messaging.isAutoInitEnabled = true

        messaging.token.addOnCompleteListener { tokenTask ->
            val token = tokenTask.result.orEmpty()

            if (!tokenTask.isSuccessful || token.isBlank()) {
                finish(
                    prefs = prefs,
                    result = SyncResult(
                        success = false,
                        message = tokenTask.exception?.localizedMessage
                            ?: "Firebase cihaz kaydı alınamadı."
                    ),
                    onComplete = onComplete
                )
                return@addOnCompleteListener
            }

            val salesEnabled = prefs.getBoolean(PREF_NOTIFY_SALES, true)
            val cem1Enabled = prefs.getBoolean(PREF_NOTIFY_CEM1, true)

            val operations = listOf(
                messaging.unsubscribeFromTopic(OLD_TOPIC),
                topicOperation(messaging, TOPIC_SALES, salesEnabled),
                topicOperation(messaging, TOPIC_CEM1, cem1Enabled)
            )

            completeWhenAll(
                operations = operations,
                onComplete = { success, error ->
                    finish(
                        prefs = prefs,
                        result = SyncResult(
                            success = success,
                            message = if (success) {
                                "Firebase cihaz kaydı ve konu abonelikleri hazır."
                            } else {
                                error ?: "Firebase konu abonelikleri doğrulanamadı."
                            }
                        ),
                        onComplete = onComplete
                    )
                }
            )
        }
    }

    private fun topicOperation(
        messaging: FirebaseMessaging,
        topic: String,
        enabled: Boolean
    ): Task<Void> {
        return if (enabled) {
            messaging.subscribeToTopic(topic)
        } else {
            messaging.unsubscribeFromTopic(topic)
        }
    }

    private fun completeWhenAll(
        operations: List<Task<Void>>,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (operations.isEmpty()) {
            onComplete(true, null)
            return
        }

        val lock = Any()
        var remaining = operations.size
        var allSuccessful = true
        var firstError: String? = null

        operations.forEach { operation ->
            operation.addOnCompleteListener { task ->
                var completed = false
                var success = false
                var error: String? = null

                synchronized(lock) {
                    if (!task.isSuccessful) {
                        allSuccessful = false
                        if (firstError == null) {
                            firstError = task.exception?.localizedMessage
                        }
                    }

                    remaining -= 1
                    if (remaining == 0) {
                        completed = true
                        success = allSuccessful
                        error = firstError
                    }
                }

                if (completed) {
                    onComplete(success, error)
                }
            }
        }
    }

    private fun finish(
        prefs: android.content.SharedPreferences,
        result: SyncResult,
        onComplete: ((SyncResult) -> Unit)?
    ) {
        prefs.edit()
            .putBoolean(PREF_REGISTRATION_OK, result.success)
            .putLong(PREF_REGISTRATION_AT, System.currentTimeMillis())
            .putString(
                PREF_REGISTRATION_ERROR,
                if (result.success) "" else result.message
            )
            .apply()

        onComplete?.invoke(result)
    }
}
