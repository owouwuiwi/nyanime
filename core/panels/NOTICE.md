# DeepPanel model

The bundled model is from pedrovgs/DeepPanelAndroid, revision
75133803b9615cc37f744f998459d883789fd247, licensed under Apache-2.0.
Copyright Pedro Vicente Gomez Sanchez and contributors.

Original asset: deeppanel/src/main/res/raw/model.tflite
SHA-256: 93347aad3abd846f8f993955764fe3279ff6d347cec401e6c2393856f5a8562e
Size: 12,532,336 bytes. License is distributed beside the model in assets/deeppanel.

Nyanime supplies its own Kotlin segmentation, geometry, cache, isolated worker,
reader and editor. It does not include upstream JNI post-processing.
Inference uses Google LiteRT 1.4.2 (Apache-2.0), CPU, at most two threads.
