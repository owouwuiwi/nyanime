-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.MangaSource { public protected *; }

-keep class eu.kanade.tachiyomi.animesource.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.** extends eu.kanade.tachiyomi.animesource.AnimeSource { public protected *; }

# Implementations arrive from extension APKs and are invisible to whole-program optimization.
-keep interface eu.kanade.tachiyomi.animesource.AnimeCatalogIdResolver { *; }
-keep interface eu.kanade.tachiyomi.animesource.RelatedMangaLinks { *; }
-keep interface eu.kanade.tachiyomi.source.MangaCatalogIdResolver { *; }
-keep interface eu.kanade.tachiyomi.source.MangaCatalogLinkResolver { *; }

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }
