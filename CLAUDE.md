# PdfToolkit — memoria di progetto

App Android per leggere e modificare PDF in locale. La specifica completa è `SPEC-1.md`: questo file
non la ripete, contiene comandi, regole non ovvie, stato e decisioni.

## Regola fissa
A fine di ogni attività aggiornare `README.md`, `CLAUDE.md` e `CHANGELOG.md` se qualcosa è cambiato.
Ogni modifica visibile all'utente va subito in `CHANGELOG.md` sotto `## [Unreleased]`, con il
formato `- **Sintesi.** dettaglio` (lo legge `release.yml` per le note di rilascio).

## Comandi
```bash
./gradlew assembleDebug        # APK debug
./gradlew testDebugUnitTest    # test JVM, Robolectric per la UI (sdk=34, vedi robolectric.properties)
./gradlew lintDebug            # Android Lint
```
In questo ambiente cloud: Android SDK in `/opt/android-sdk` (installato con `sdkmanager`,
`local.properties` è gitignorato), Gradle con `LC_ALL=C.UTF-8`. Maven Central può rispondere 429:
basta ritentare, meglio con `--max-workers=2`.

## Architettura
Package `com.marcogn.pdftoolkit`, stesso layering di ThePatientGamerHelper e KartLog (dettagli in
`docs/plan.md`):
- `ui/<feature>/` schermate Compose e ViewModel; `ui/navigation/` rotte `@Serializable`
  (`Destination`), NavHost e drawer; `ui/theme/` palette (`Color.kt`) e tema.
- `domain/` modelli senza dipendenze Android (`PdfTool`, `ThemeMode`; poi `EditSession`, `PageItem`).
- `data/` DataStore (`data/settings/ThemePreferences`), poi Room e SAF.
- `pdf/render`, `pdf/edit`, `pdf/forms`, `pdf/text` dalle fasi 1–5 (SPEC §12).
- `di/` moduli Hilt, quando servono.

## Regole non ovvie
- Una sola pagina aperta per volta per istanza di `PdfRenderer`: mutex per documento, render su
  `Dispatchers.Default`, pagina sempre chiusa dopo il render (ADR 0001).
- Tutte le scritture passano da `PdfEditor`; la UI non tocca PdfBox (ADR 0002).
- Firme, testo, spunte e date si scrivono nel content stream della pagina, non come annotazioni.
- Le evidenziazioni della ricerca sono solo overlay, mai scritte nel PDF.
- Nessun permesso `INTERNET` in Fase 1 del prodotto: il manifest lo toglie con `tools:node="remove"`
  e la CI controlla il manifest impacchettato. Non aggiungere dipendenze che ne hanno bisogno.
- Ogni `navigate()`/`popBackStack()` passa dalla guardia `lifecycleIsResumed()` dell'entry che
  possiede la callback (doppio tap durante una transizione). Home dal drawer: `popUpTo<Home>{inclusive}`.
- Transizioni di navigazione 200–250 ms, mai oltre 300 (SPEC §9).
- `MainActivity` è un `AppCompatActivity`: serve a `setApplicationLocales()` per la lingua per-app.
- Nessuna stringa hardcoded: `values/` (italiano) e `values-en/`.

## Convenzioni
- UI e documentazione in italiano, identificatori in inglese.
- Prefisso `PT` solo dove serve a evitare collisioni di nomi.
- Scelte marcate **[ASSUNZIONE]** nella specifica: implementate come scritte, isolate (es. la
  palette in `ui/theme/Color.kt`).
- Se un requisito non è chiaro o non è fattibile, fermarsi e chiedere.

## Riferimenti
- Specifica: `SPEC-1.md`. Piano e allineamento ai riferimenti: `docs/plan.md`.
- ADR: `docs/adr/0001-viewer.md`, `docs/adr/0002-pdfbox-android.md`.

## Stato attuale
<!-- Aggiornare a ogni fine sessione. -->
- **Fase 0 chiusa (2026-10-01)**: scheletro, tema, lingue, navigazione, drawer, Home, signing, CI,
  documentazione, ADR 0001–0002. Verificato con build debug, lint e test JVM/Robolectric. **Non
  verificato a schermo**: nessun emulatore in questo ambiente, va provato su un dispositivo.
- Prossima: Fase 1 (viewer). Punti aperti da confermare con l'autore in `docs/plan.md`.
- Segreti GitHub per `build-apk.yml`/`release.yml` da impostare nel repository.

## Decisioni prese
<!-- Una riga per decisione: data, cosa, perché. Aggiungere, non riscrivere. -->
- 2026-10-01 · Versioni di toolchain e librerie identiche ai riferimenti (AGP 8.13.0, Kotlin 2.0.21,
  Compose BOM 2024.12.01): aggiornarle è un lavoro a parte, non va mescolato alle fasi.
- 2026-10-01 · README e CHANGELOG in italiano (TPGH è in inglese, KartLog in italiano; la specifica è
  in italiano). Punto aperto in `docs/plan.md`.
- 2026-10-01 · Strumenti di Fase 2 in una sezione "In arrivo" separata della Home, con badge "Presto";
  il tap mostra uno snackbar e non naviga. Griglia `GridCells.Adaptive(100.dp)`.
- 2026-10-01 · "Le mie firme" dalla Home usa la stessa navigazione della voce del drawer, per non
  avere due copie della schermata nello stack.
- 2026-10-01 · Regole di backup che escludono `filesDir/signatures/` già in Fase 0 (costo nullo).
- 2026-10-01 · Room nel version catalog ma non fra le dipendenze finché non c'è un'entità (Fase 1).
- 2026-10-01 · Test Compose con Robolectric: locale `it` esplicita in `@Config(qualifiers = "it-...")`,
  altrimenti Robolectric parte in inglese e carica `values-en/`.
- 2026-10-01 · Icona in `mipmap-anydpi-v26/` come nei riferimenti. Lint segnala `ObsoleteSdkInt`
  (con minSdk 26 basterebbe `mipmap-anydpi/`), ma spostandola aapt2 non trova più `@mipmap/ic_launcher`:
  avviso lasciato com'è.
- 2026-10-01 · Gli altri avvisi di lint sono versioni più nuove delle dipendenze: restano allineate
  ai riferimenti finché l'autore non decide un aggiornamento dedicato.
