# Nyanime brand assets

`current/` contains the transparent icon and Home wordmark supplied on 8 October
2026. Dark and light files are separate, with identical artwork for now; either
variant can be replaced independently later. The original Downloads files remain
unchanged.

Only the white canvas and white hole overlays were removed. The letter holes are
transparent path cutouts, not fills or renderer-dependent masks. Gradients are
preserved. View boxes are trimmed to the artwork with a small transparent margin.

The Android launcher uses a smaller 58 dp vector foreground inside the adaptive
icon's 108 dp canvas. Light appearance has a white background; dark appearance has
a black background. The existing theme controller enables exactly one launcher
entry and selects the app's appearance, including an explicit choice independent
of the device theme. Glyph resources are separate even though their artwork is
currently identical. The launch screen uses matching dark/light vectors. Home
retains its existing layout and selects the corresponding `raw/nyanime_wordmark_*`
SVG. Altro and the shared in-app brand components use matching native vectors,
so their logo appears synchronously without a loading placeholder. Native square
icon viewports preserve the original SVG proportions.

`archive/2026-10-08/` preserves the previous launcher, splash and Home resources at
their original relative paths. They are kept outside Android resources so they do
not increase the APK size and can be restored without reconstructing the artwork.
