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
Il [player nativo](../tools/native/README.md) ha revisioni e istruzioni separate:
gli artefatti upstream devono essere verificati rispetto al binario distribuito,
senza dedurre la licenza dal solo wrapper Java/Kotlin.

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

## Marchio, immagini e contenuti

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
