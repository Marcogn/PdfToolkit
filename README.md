# PdfToolkit

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![minSdk 26](https://img.shields.io/badge/minSdk-26-brightgreen.svg)](app/build.gradle.kts)

App Android per leggere e modificare PDF sul telefono. Tutto gira sul dispositivo: niente account,
niente cloud, nessuna connessione di rete.

## Stato

Il progetto è alla Fase 0 del piano (`SPEC-1.md` §13): c'è lo scheletro dell'app con Home, menu
laterale, tema chiaro/scuro e lingua italiana/inglese. Viewer e strumenti di modifica arrivano nelle
fasi successive; per ora i pulsanti portano a una schermata provvisoria.

## Funzioni

Previste per la prima versione:

- lettura con zoom, scorrimento continuo o a pagina singola, scrubber e miniature
- ricerca nel testo
- unione di più PDF
- aggiunta di pagine (da un altro PDF, vuote, da immagini), rimozione, riordino, rotazione
- compilazione di moduli e firma, con un archivio di firme salvate sul telefono

Pianificate per dopo: scansione con OCR, caricamento su cloud (WebDAV), evidenziazione, disegno a
mano libera, esportazione in formato OpenDocument. Compaiono già nella Home come "Presto".

## Requisiti

Android 8.0 (API 26) o successivo.

## Come compilare

```bash
./gradlew assembleDebug       # APK debug
./gradlew testDebugUnitTest   # test unitari JVM (Robolectric per quelli di UI)
./gradlew lintDebug           # Android Lint
./gradlew assembleRelease     # APK release
```

Serve l'Android SDK con `compileSdk 36` e JDK 17 o successivo.

La build release si firma solo se trova il keystore. Le credenziali non stanno nel repository:
arrivano da quattro variabili d'ambiente, `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` e `RELEASE_KEY_PASSWORD`. Senza, l'APK release esce non firmato. Su GitHub
Actions il keystore è il secret `RELEASE_KEYSTORE_BASE64` (in base64), decodificato dai workflow
"Build APK" e "Release"; il secret `RELEASE_PUSH_TOKEN` serve a "Release" per committare il cambio
di versione su `main`.

## Struttura del progetto

```
app/src/main/java/com/marcogn/pdftoolkit/
  ui/        schermate Compose, navigazione e tema
  domain/    modelli senza dipendenze Android
  data/      preferenze (DataStore), più avanti Room e accesso ai file
  pdf/       rendering e modifica dei PDF (dalle prossime fasi)
docs/
  plan.md    piano e allineamento ai progetti di riferimento
  adr/       decisioni di architettura
SPEC-1.md    specifica funzionale e tecnica
```

## Privacy

L'app non dichiara il permesso `INTERNET`: i file restano sul telefono e niente esce dal
dispositivo. La CI controlla a ogni build che il permesso non rientri tramite una dipendenza.

## Librerie e licenze

Kotlin, AndroidX, Jetpack Compose e Dagger Hilt, tutte con licenza Apache 2.0. Dalle prossime fasi
si aggiunge PdfBox-Android (Apache 2.0) per le modifiche; il viewer usa `PdfRenderer` di Android.
Le motivazioni sono in [`docs/adr/`](docs/adr/).

## Documentazione

- [`SPEC-1.md`](SPEC-1.md): specifica e piano di sviluppo
- [`docs/plan.md`](docs/plan.md): cosa si riprende da ThePatientGamerHelper e KartLog, e i punti aperti
- [`docs/adr/`](docs/adr/): decisioni di architettura
- [`CHANGELOG.md`](CHANGELOG.md): modifiche per versione
- [`CLAUDE.md`](CLAUDE.md): note operative per chi sviluppa

## Sviluppo

L'app è sviluppata con l'aiuto di strumenti di intelligenza artificiale (Claude Code), con revisione
e test dell'autore.

## Licenza

MIT, vedi [`LICENSE`](LICENSE).
