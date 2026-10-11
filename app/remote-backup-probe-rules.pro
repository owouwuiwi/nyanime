# Private physical-device verification only, enabled explicitly with remoteBackupProbe.
-keep class it.lorelore.nyanime.backup.remote.** { *; }
-keep class nyanime.telegram.sync.SyncBackupCipher { *; }
-keep class nyanime.telegram.sync.SyncBackupHeader { *; }
-keep,includedescriptorclasses class eu.kanade.tachiyomi.data.backup.BackupFileValidator { *; }
-keep,includedescriptorclasses class eu.kanade.tachiyomi.data.backup.create.BackupCreator { *; }
-keep class eu.kanade.tachiyomi.data.backup.create.BackupOptions { *; }
-keep class eu.kanade.tachiyomi.data.backup.create.BackupOptions$Companion { *; }
