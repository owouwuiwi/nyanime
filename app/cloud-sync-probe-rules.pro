# Isolated real-device SQLite/crypto probe. Not included in ordinary distribution builds.
-keep class eu.kanade.tachiyomi.data.cloud.sync.CloudSyncStore { *; }
-keep class eu.kanade.tachiyomi.data.cloud.sync.CloudSyncStore$* { *; }
-keep class eu.kanade.tachiyomi.data.cloud.sync.CloudSyncPacketFiles { *; }
-keep class nyanime.telegram.sync.** { *; }
-keep class eu.kanade.tachiyomi.data.cloud.sync.** { *; }
-keep class it.lorelore.nyanime.cloud.sync.** { *; }
-keep class eu.kanade.tachiyomi.data.telegram.TelegramClient { *; }
-keep class eu.kanade.tachiyomi.data.telegram.TelegramClient$* { *; }
-keep class eu.kanade.tachiyomi.data.cloud.CloudRepository { *; }
-keep class eu.kanade.tachiyomi.data.cloud.CloudRepository$* { *; }
-keep class nyanime.telegram.** { *; }
# Called from the separate probe APK after app R8 has inlined its own array helpers.
-keep class kotlin.collections.ArraysKt* { *; }
