package com.nexus.music.update

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class NexusFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "nexus_release" || message.from == "/topics/nexus_updates") {
            NexusUpdateScheduler.requestFromPush(applicationContext)
        }
    }
}
