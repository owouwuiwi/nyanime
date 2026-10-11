# Localized text fallback

English is the canonical text in the Android `values` resources and the two
Moko `base` resource directories. Each available locale keeps its translations.
Missing strings, plural resources and string arrays receive the corresponding
English resource during resource preparation. Blank translations, including
escaped whitespace, are replaced individually. Blank plural quantities keep
their quantity and use the matching English form, or English `other`.

`EnglishFallbackResourcesTask` copies resources into a generated build directory
before Moko/Android resource compilation. Translation source files are not
rewritten. The app incurs no resource-copying, XML parsing or extra network work
at runtime. Android configuration qualifiers such as night, API level and screen
width are excluded from locale completion. Non-translatable identifiers and
other resource kinds are left intact.

Tests cover missing/blank entries, formatting, arrays, plural forms, regional
languages, escaped whitespace and configuration qualifiers. Cloud uses the same
activity base as the rest of the app so the selected application locale applies.
