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

# Tracking fixture shares production SQLite/WorkManager code from a separate instrumentation APK.
-keep class it.lorelore.nyanime.tracking.** { *; }
-keep class kotlin.collections.CollectionsKt* { *; }
-keep class kotlin.collections.MapsKt* { *; }
-keep class kotlin.collections.SetsKt* { *; }

-keep class kotlin.io.FilesKt* { *; }

# Optional image-worker probe; these rules never apply to ordinary builds.
-keep class it.lorelore.nyanime.reader.quality.** { *; }

# Optional novel fixture: exercised only in the isolated probe variant.
-keep class it.lorelore.nyanime.novel.** { *; }
-keep class tachiyomi.core.common.preference.** { *; }
