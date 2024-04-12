package com.greenart7c3.nostrsigner.service

import android.content.Intent
import android.util.Log
import android.util.LruCache
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.greenart7c3.nostrsigner.LocalPreferences
import com.greenart7c3.nostrsigner.service.NotificationUtils.getOrCreateDMChannel
import com.vitorpamplona.quartz.events.Event
import com.vitorpamplona.quartz.events.GiftWrapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.time.measureTimedValue

class Notifications : FirebaseMessagingService() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val eventCache = LruCache<String, String>(100)

    override fun getStartCommandIntent(originalIntent: Intent?): Intent {
        return super.getStartCommandIntent(originalIntent)
    }

    override fun handleIntent(intent: Intent?) {
        Log.d("Time", "Intent received $intent")
        super.handleIntent(intent)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        scope.launch(Dispatchers.IO) {
            val (value, elapsed) =
                measureTimedValue { parseMessage(remoteMessage.data)?.let { receiveIfNew(it) } }
            Log.d("Time", "Notification processed in $elapsed")
        }
        super.onMessageReceived(remoteMessage)
    }

    private suspend fun parseMessage(params: Map<String, String>): GiftWrapEvent? {
        params["encryptedEvent"]?.let { eventStr ->
            (Event.fromJson(eventStr) as? GiftWrapEvent)?.let {
                return it
            }
        }
        return null
    }

    private suspend fun receiveIfNew(event: GiftWrapEvent) {
        if (eventCache.get(event.id) == null) {
            eventCache.put(event.id, event.id)
            getOrCreateDMChannel(applicationContext)
            EventNotificationConsumer(applicationContext).consume(event)
        }
    }

    override fun onNewToken(token: String) {
        scope.launch(Dispatchers.IO) {
            RegisterAccounts(LocalPreferences.allSavedAccounts()).go(token)
        }
        super.onNewToken(token)
    }

    override fun onDestroy() {
        Log.d("Lifetime Event", "PushNotificationReceiverService.onDestroy")

        scope.cancel()
        super.onDestroy()
    }
}