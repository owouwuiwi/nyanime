# Contribuire a Nyanime

Nyanime è sviluppato in Kotlin e Jetpack Compose, mantenendo compatibilità e
funzioni della base AniYomi. Le modifiche del fork, la documentazione e le segnalazioni
vanno gestite in questo repository, secondo il [codice di condotta](CODE_OF_CONDUCT.md).

## Segnalazioni e proposte

Prima di aprire una segnalazione, consulta le [FAQ](docs/getting-started.md) e le
[novità](CHANGELOG.md), poi cerca eventuali problemi già registrati nel repository.

Per un bug indica versione completa di Nyanime, modello, versione Android,
passaggi riproducibili, risultato atteso ed effettivo. Per player e stanze specifica:

- avvio da zero, ripresa, seek o uscita dal player;
- visione individuale, stanza come host/ospite oppure Cast;
- modalità Anime4K, qualità e se compare buffering o si congela l'immagine;
- frequenza del problema ed eventuale differenza tra le versioni provate.

Condividi solo l'estratto diagnostico necessario. Codici delle stanze, token,
cookie, URL firmati, dati personali, nomi e materiali delle integrazioni private
restano fuori da issue, allegati e repository. Usa contenuti sintetici per i test.
Un problema del parser di un'estensione va gestito nel suo progetto locale;
qui interessa il comportamento generico dell'app e del contratto.

Per una proposta descrivi il bisogno concreto e il comportamento desiderato,
incluse le interazioni con legacy, manga, incognito, offline, Cast o stanze se pertinenti.
Non occorre indirizzare gli utenti al supporto AniYomi per funzioni di Nyanime.

## Creare un'estensione

Il [template pubblico delle estensioni](https://github.com/owouwuiwi/nyanime-extension-template)
offre una **guida rapida in cinque passi** e un **riferimento tecnico completo**,
entrambi in inglese. Include esempi video, manga, notizie e TV, SDK corrispondente,
strumenti di firma, test e contenuti dimostrativi originali.

Le estensioni dichiarano categorie e sezioni Home tramite contratti pubblici;
l'app non deduce categorie dal nome di una fonte. Endpoint, selettori e parser
rimangono nel progetto dell'estensione. Usa materiali autorizzati e rispetta
il modello [bring your own content](docs/responsible-use.md).

## Preparare l'ambiente

Usa Git, **JDK 17**, Android Studio o gli strumenti Android da riga di comando,
e il wrapper Gradle incluso. Le versioni correnti sono definite in
[AndroidConfig.kt](buildSrc/src/main/kotlin/mihon/buildlogic/AndroidConfig.kt):

| Componente | Configurazione |
| --- | --- |
| Android minimo | API 26, Android 8.0 |
| Compile/target SDK | API 36 |
| Build Tools | 35.0.1 |
| NDK dell'app | 27.1.12297006 |
| Java/Kotlin JVM | 17 |

Configura il percorso SDK in `local.properties` o tramite l'ambiente Android.
Il file è ignorato da Git. Evita nomi di progetto o percorsi locali nei documenti condivisi.

Le dipendenze sono dichiarate nei [cataloghi Gradle](gradle).
Il repository include un [mirror vincolato](vendor/README.md) e la manutenzione
[nativa FFmpeg](tools/native/README.md). La ricompilazione del demuxer ha prerequisiti
propri, distinti dal normale assemblaggio Android. Prima della build ripristina
l’input nativo verificato con `python3 -m scripts.restore_telegram_native`.

## Compilare

Su Windows usa `.\gradlew.bat` al posto di `./gradlew`.

```sh
./gradlew :app:assembleDebug
```

Le varianti sono definite in [app/build.gradle.kts](app/build.gradle.kts):

| Variante | Uso | Package |
| --- | --- | --- |
| debug | Sviluppo locale | `it.owouwuiwi.nyanime.dev` |
| preview | Build ottimizzata distribuita | `it.owouwuiwi.nyanime` |
| release | Build ottimizzata | `it.owouwuiwi.nyanime` |
| benchmark | Misurazioni isolate | `it.owouwuiwi.nyanime.benchmark` |

Gli output sono in `app/build/outputs/apk/<variante>/`, con APK per ABI e universale.
Il marchio Nyanime non richiede di rinominare namespace, chiavi di preferenza o
identificativi Android esistenti: questi possono essere necessari alla compatibilità.

Per preparare la variante distribuita:

```sh
./gradlew :app:assemblePreview
```

La variante `preview` identifica il tipo di build Gradle, non il canale OTA:
una build ottimizzata può essere pubblicata come **Consigliata**. La firma di
produzione non è inclusa nei sorgenti. Per compilare una propria versione,
genera una chiave con `keytool` e imposta `NYANIME_SIGNING_STORE_FILE`,
`NYANIME_SIGNING_STORE_PASSWORD`, `NYANIME_SIGNING_KEY_ALIAS` e
`NYANIME_SIGNING_KEY_PASSWORD` nell'ambiente. Non inserire chiavi o password
nei file versionati. Una firma diversa non può aggiornare un APK di produzione.
Per lo sviluppo usa `assembleDebug`, che non richiede la chiave di produzione.

Il codice usa la [licenza Apache 2.0](LICENSE); conserva copyright e
attribuzioni esistenti nelle modifiche. Consulta [licenze e sorgenti](docs/licensing.md)
prima di aggiornare o aggiungere componenti, soprattutto quelli nativi.

Prima di pubblicare un nuovo APK, incrementa la versione in `release.properties`
con `python3 scripts/release_version.py --bump fix` (oppure `improvement`/`feature`)
e aggiungi le relative note in `CHANGELOG.md`. Canali, numerazione e aggiornamento
delle vecchie installazioni sono descritti nella [guida alle versioni](docs/versioning.md).
Una versione già pubblicata non può essere riutilizzata per codice diverso.

La firma debug prevista dalla build locale non è automaticamente la firma della
copia distribuita. Firma, package e versione devono essere verificati prima della
consegna; un APK compilato non è per questo aggiornabile sopra qualunque installazione.
Le chiavi e le credenziali di firma non appartengono al repository.

## Verificare una modifica

Esegui verifiche proporzionate al comportamento cambiato. Per codice Android:

```sh
./gradlew :app:testDebugUnitTest spotlessCheck
```

Per la UI, i render Compose sul PC non richiedono emulatori:

```sh
./gradlew :app:updateDebugScreenshotTest
```

Esamina le immagini pertinenti: il solo completamento del renderer non dimostra
che il layout sia corretto. Verifica dimensioni, testo grande, orientamenti,
ModernUI/legacy e tema manga quando coinvolti. Le anteprime e l'artwork locale
di prova sono descritti nella [guida UI](docs/nyanime-ui.md).

- Per Anime4K: controlli JVM e Lua indicati nella [guida Smart](docs/anime4k-smart.md).
- Per le stanze: protocollo, inviti, controlli, perdita rete e isolamento della
  visione individuale; i [test di rete](docs/watch-together.md) sono opt-in.
- Per Cast: controlli del protocollo e prove sui ricevitori pertinenti.
- Per player/nativo: [regressioni](docs/stability-regressions.md), ingresso/uscita,
  ripresa e seek sul dispositivo reale quando disponibile.
- Per documentazione: collegamenti locali, immagini, formattazione, accuratezza
  rispetto al codice e assenza di materiale privato. Non serve ricompilare l'APK.

Le prove su emulatore non fanno parte della verifica ordinaria. Un test JVM o un
render non sostituisce il dispositivo: dichiara separatamente build, test,
ispezione visiva e prove reali eseguite, senza attribuire a una release verifiche storiche.

## Confini da conservare

- La logica delle fonti resta nelle estensioni; il codice dell'app usa contratti generici.
- Fixture, screenshot e documenti versionati usano titoli e dati inventati.
  Per materiali locali usa `.local/` o `private/`, già ignorate.
- Librerie, avanzamento, copertine personalizzate e preferenze vanno preservati.
- ModernUI è reversibile; manga mantiene un tema indipendente.
- Stanze inattive non devono interferire con il player individuale.
- Anime4K rimane disabilitato durante la permanenza in una stanza.
- Pausa locale, timer, annullamento e scelta dell'episodio mantengono la propria
  autorità nei flussi sincronizzati.
- Conserva licenze, attribuzioni e provenienza delle dipendenze.

## Preparare una modifica per la revisione

Controlla il diff, includi solo i file pertinenti e aggiorna le guide quando cambia
il comportamento. Nella descrizione spiega problema, risultato e verifiche,
segnalando le prove non eseguite. Le modifiche visive richiedono immagini sintetiche
o prive di materiale privato. Pubblicare APK, cambiare visibilità del repository o
riscrivere la storia Git sono operazioni separate dalla preparazione di una modifica.

Per le stringhe e le traduzioni consulta la [guida i18n](i18n/README.md).
