# Licenza e copyright di Nyanime

Il codice originale di Nyanime è distribuito sotto **Apache License 2.0**.
Il testo vincolante è [LICENSE](../LICENSE); il [NOTICE](../NOTICE) identifica
il lavoro originale e conserva le attribuzioni dei progetti da cui deriva.

```text
Copyright (c) 2026 owouwuiwi and Nyanime contributors.
```

Il copyright riguarda le contribuzioni originali di ciascun autore. Non
attribuisce a Nyanime la proprietà del codice ereditato, delle dipendenze,
delle copertine o dei dati di terzi.

## Codice ereditato e componenti inclusi

AniYomi, Mihon e Tachiyomi mantengono i propri autori, copyright e avvisi.
I [crediti](credits.md), le intestazioni dei file e la cronologia dei progetti
conservano la provenienza del codice. Le modifiche Nyanime devono essere
riconoscibili senza cancellare gli avvisi originali.

Apache 2.0 non sostituisce le licenze dei componenti inclusi nell'APK. In
particolare, le build che incorporano componenti GPL o LGPL devono rispettare
anche quelle licenze: avvisi, testi delle licenze, sorgenti corrispondenti,
patch e informazioni di compilazione o ricollegamento applicabili.

La distribuzione di un'opera combinata deve rispettare le condizioni dei
componenti collegati. La licenza Apache delle parti originali non rende una
build GPL una distribuzione esclusivamente Apache.

## Materiali da accompagnare a ogni release

Il codice corrente è consultabile in [owouwuiwi/nyanime](https://github.com/owouwuiwi/nyanime).
Le [release](https://github.com/owouwuiwi/nyanime/releases) forniscono l'archivio
`Nyanime-Source-<versione>.zip`; il relativo SHA-256 e la revisione originale
sono registrati in `releases/<versione>.json`. L'archivio contiene i sorgenti
dell'app, i contratti generici, le licenze e le patch. Credenziali, chiavi di
produzione e file locali di build non sono necessari per compilare una propria
versione e non vengono pubblicati.

Le [istruzioni di compilazione](../CONTRIBUTING.md) descrivono la build Android.
Il [player nativo](../tools/native/README.md) dispone di un archivio completo
con le revisioni delle dipendenze, le patch e le configurazioni di tutte le ABI.
Dalla 0.42.0.2 ogni release include il manifesto nativo, che identifica gli
archivi `Nyanime-Native-<versione>-*.tar.gz` con URL pubblici e SHA-256.
Se i binari nativi non cambiano, il manifesto riutilizza gli archivi immutabili
già pubblicati: non è necessario duplicarli sotto ogni nuova versione dell'app.
Il player e i sorgenti supplementari correnti provengono dalla 0.42.0.2;
l'archivio WebRTC dalla 0.47.0.2. La corrispondenza va controllata rispetto agli APK.
Il codice originale, i testi upstream e le istruzioni per creare una propria
build rimangono disponibili senza registrazione. I binari GPL sono distribuiti
con i sorgenti corrispondenti; i componenti permissivi precompilati conservano
le proprie identità e gli avvisi, senza inventare revisioni interne mancanti.

- Versione, revisione del codice e hash degli APK effettivamente distribuiti.
- Testo Apache 2.0, NOTICE Nyanime e avvisi originali pertinenti.
- Inventario delle dipendenze Java/Kotlin e dei componenti nativi, con le
  versioni e le licenze verificate.
- Testi integrali delle licenze richieste, consultabili anche nell'app.
- Sorgenti corrispondenti dei componenti copyleft, con revisioni, patch e
  script necessari per la build; un semplice collegamento al ramo principale
  di un progetto non dimostra la corrispondenza con il binario incluso.
- Istruzioni per ottenere gratuitamente questi materiali dalla stessa
  pagina che offre gli APK e per compilare una versione modificata con una
  propria chiave. Le chiavi private di produzione non vengono distribuite.

Ogni nuovo componente, asset copiato o aggiornamento di una dipendenza richiede
una verifica della provenienza e della licenza. Un campo mancante non equivale
a una licenza permissiva. La schermata delle licenze e un rapporto automatico
non sostituiscono la verifica dei sorgenti corrispondenti.

## Inventario e testi consultabili offline

In **Altro → Info → Licenze delle librerie** sono disponibili l'inventario
Java/Kotlin e i testi dei componenti inclusi direttamente: player, shader,
audio, riconoscimento dei pannelli, download, Telegram e voce nelle stanze.
La consultazione dei testi non richiede una connessione.

L'elenco usa gli stessi registri controllati dalla verifica automatica:

- `app/src/main/assets/licenses/catalog.json`: componenti e avvisi dell'app,
  con eventuale copyright o NOTICE separato.
- `app/src/main/assets/licenses/native/catalog.json`: componenti nativi.
- `app/config/libraries` e `app/config/licenses`: integrazioni dei metadati Maven.
  La [guida alla manutenzione](../app/config/README.md) spiega come conservare
  gli identificatori e la provenienza dei testi.

I testi Apache e NOTICE del progetto sono copiati dai file canonici alla build.
L'inventario generato è letto attraverso l'ID Android della risorsa, così la
rinomina o l'ottimizzazione delle risorse non dipende da una ricerca per nome.
Un avviso presente in una schermata separata non giustifica una voce senza
licenza nell'inventario delle dipendenze: entrambi devono essere completi.

Prima della consegna, controllare gli **APK effettivi**, non soltanto il JSON
generato dalla build:

```sh
python3 -m unittest scripts.tests.test_license_catalog
python3 scripts/verify_license_catalog.py \
  app/build/generated/aboutLibraries/preview/res/raw/aboutlibraries.json \
  --apk app/build/outputs/apk/preview/app-*-preview.apk
```

Il controllo rileva dichiarazioni o testi mancanti, avvisi vuoti o non leggibili,
percorsi non validi e inventari assenti o diversi negli APK, anche se la risorsa
è stata rinominata dall'ottimizzatore. La CI esegue questa verifica per tutte
le varianti ABI. I controlli dei sorgenti e degli hash nativi rimangono separati:
completare i metadati non certifica da solo licenze, marchi, dati o condizioni API.

## Marchio, immagini e contenuti

Nyanime non promuove né incoraggia la pirateria in alcuna forma. Adotta il modello
**bring your own content**; la posizione del progetto e le condizioni d'uso dei
contenuti sono spiegate in [Uso responsabile](responsible-use.md).

Il nome Nyanime distingue questo progetto dai progetti upstream. Apache 2.0
non concede automaticamente diritti sui nomi e sui marchi, salvo gli usi
previsti dal suo testo. Questa pagina non afferma che il marchio sia registrato
e non aggiunge limitazioni alla licenza del codice.

Le immagini e i dati mostrati dall'app mantengono i diritti e le condizioni
dei rispettivi titolari. La licenza del codice non autorizza a ridistribuirli.
Estensioni e addon sono distribuzioni separate: devono includere i propri
avvisi e soddisfare le licenze dei componenti che incorporano.

## Riferimenti normativi

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- [GNU GPL 3](https://www.gnu.org/licenses/gpl-3.0.html).
- [GNU LGPL 3](https://www.gnu.org/licenses/lgpl-3.0.html).
- [Licenze e condizioni di FFmpeg](https://ffmpeg.org/legal.html).

## English summary

Original Nyanime application contributions are licensed under the Apache
License, Version 2.0. Copyright (c) 2026 owouwuiwi and Nyanime contributors.
This notice covers each contributor's original work only; it does not transfer
ownership of inherited code, dependencies, third-party artwork or data.

The binding license text is provided in LICENSE. NOTICE preserves project
attributions. Separately licensed components retain their own conditions.
GPL/LGPL binary distribution obligations include the applicable license texts
and corresponding source, modifications and build/relinking information.
An Apache-licensed application component does not override those obligations.
Source access must identify the actual distributed revision and remain
available to recipients. Production signing secrets are not published.

## Integrazione ordinaria 0.49.0.0

Il modulo `presentation-player` contiene contributi originali Nyanime Apache-2.0
e un sottoinsieme grafico adattato da FLOW GPL-3.0-only. Il nome del modulo non
cambia la licenza dei singoli file: [provenienza e perimetro](player-presentation.md).
Le due attribuzioni e i relativi testi sono consultabili offline. I motori FLOW,
YouTube e MELD, i rispettivi client e submoduli non fanno parte di questa versione.

L’archivio dell’app comprende il codice modificato del renderer, avvisi originali,
ricette, sottoprogetti e sorgenti del submodulo Sandspiel. Il manifesto nativo
aggiunge lo scambio fonti sulle quattro ABI, con sorgenti Rust, dipendenze Cargo
vincolate e conservate offline, e materiali originali CameraX/MediaPipe con hash.
I binari Google Maven sono dichiarati come precompilati: la versione pubblica dei
sorgenti associati non viene presentata come prova dell’ambiente interno del fornitore.
I componenti nativi GPL già presenti conservano i loro archivi corrispondenti.

Ogni pubblicazione deve verificare l’APK effettivo e consegnare i materiali elencati:
una compilazione riuscita o un inventario delle dipendenze non sono una certificazione
legale. Questa migrazione non rende l’app closed source e non concede diritti sui
contenuti, marchi, dati o servizi consultati dall’utente.
