# Piano di PdfToolkit e allineamento ai progetti di riferimento

Scritto in Fase 0 (2026-10-01) dopo aver letto in locale i due progetti di riferimento:
[ThePatientGamerHelper](https://github.com/Marcogn/ThePatientGamerHelper) (TPGH) e
[KartLog](https://github.com/Marcogn/KartLog). KartLog è a sua volta allineato a TPGH ed è il più
recente dei due: dove i due divergono, la tabella dice quale si è seguito e perché. Dove la
specifica (`SPEC-1.md`) chiede esplicitamente qualcosa di diverso, vince la specifica (SPEC §0.4).

## Cosa si riprende e da dove

| Elemento | Riferimento | PdfToolkit |
|---|---|---|
| Gradle root | `settings.gradle.kts` con `FAIL_ON_PROJECT_REPOS`, `google()` + `mavenCentral()`; root `build.gradle.kts` con soli plugin `apply false` (identico nei due) | Identico, `rootProject.name = "PdfToolkit"` |
| Gradle wrapper | Gradle 8.13 (identico nei due) | Copiato da KartLog |
| `gradle.properties` | Identico nei due | Copiato |
| Version catalog | `gradle/libs.versions.toml`: AGP 8.13.0, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, Navigation 2.8.5, Room 2.6.1, Hilt 2.52, DataStore 1.1.1, AppCompat 1.7.0, Robolectric 4.16.1 | Stesse versioni. Tolte le librerie che servono solo ai riferimenti (WorkManager, Credential Manager, Play Services, Coil, Palette). Room è nel catalog ma entra fra le dipendenze in Fase 1 |
| Toolchain | `compileSdk`/`targetSdk` 36, `minSdk` 26, Java/Kotlin 17 | Identico. SPEC §3.3 chiede minSdk come il riferimento e non sotto 26: 26 soddisfa entrambe. Per `targetSdk` SPEC §3.3 chiede l'ultimo stabile supportato dall'AGP in uso: si tiene 36 come i riferimenti, da ricontrollare sulle note di rilascio di AGP a ogni aggiornamento |
| Signing | `signingConfigs.release` da variabili d'ambiente `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`; release non firmata se mancano | Identico, stessi nomi |
| Build types | `release` con `isMinifyEnabled = false` | Identico per ora. R8 si attiva e si prova in Fase 6 con le regole di PdfBox-Android (SPEC §3.2) |
| Package e layering | `com.marcogn.<app>` con `ui/<feature>/`, `data/`, `domain/`, `di/` | `com.marcogn.pdftoolkit`, stesso schema più `pdf/` (SPEC §12). Si creano i package quando arriva il codice che li usa |
| `applicationId` | `com.marcogn.thepatientgamerhelper`, `com.marcogn.kartlog` | `com.marcogn.pdftoolkit` (SPEC §2) |
| Hilt | `@HiltAndroidApp` sull'Application, `@AndroidEntryPoint` su `MainActivity` | Identico |
| Navigazione | `Destination` sealed interface con rotte `@Serializable`, `ModalNavigationDrawer` con `drawerState` a livello di NavGraph, guardia `lifecycleIsResumed()` su ogni navigazione, Home dal drawer con `popUpTo<Home>{inclusive}` (fix di KartLog) | Identico. **Divergenza da spec**: transizioni 250 ms (entrata) / 200 ms (uscita) con slide breve e dissolvenza, invece di 300 ms a tutta larghezza (SPEC §9: 200–250 ms, mai oltre 300) |
| Drawer | TPGH: `ModalDrawerSheet` Material con `NavigationDrawerItem`. KartLog: drawer grafico personalizzato | Material come TPGH, con la voce corrente evidenziata. Voci da SPEC §4: Home, File recenti, Le mie firme, Impostazioni, Informazioni |
| Home | KartLog: hamburger a sinistra, tessere quadrate grandi; TPGH: chooser semplice | Struttura di KartLog con aspetto proprio: card "Apri PDF", Recenti, griglia di pulsanti quadrati (`GridCells.Adaptive(100.dp)`, tre colonne su un telefono da 360 dp), strumenti di Fase 2 in una sezione "In arrivo" con etichetta "Presto" |
| Tema | `ThemeMode` (Sistema/Chiaro/Scuro) su Preferences DataStore, `ThemeViewModel`, `MainActivity` sceglie dark/light | Identico. In più il colore dinamico, spento di default e attivabile in Impostazioni (SPEC §9), salvato nello stesso DataStore |
| Palette | TPGH: palette viola di default di Compose + dynamic color. KartLog: palette custom vivace | Palette propria blu petrolio con accento ambra (SPEC §9, **[ASSUNZIONE]**), isolata in `ui/theme/Color.kt` |
| Lingua | `values/` italiano + `values-en/`, `locales_config.xml`, `AppLanguage` + `setApplicationLocales()`, `autoStoreLocales` nel manifest, `MainActivity : AppCompatActivity`, tema XML AppCompat | Identico |
| Icona | Adaptive icon con foreground raster | Adaptive icon vettoriale (foglio con angolo piegato e penna) con `monochrome` per Android 13+ (SPEC §9 la chiede, i riferimenti non l'hanno) |
| CI | `android-ci.yml`: lint, test, assembleDebug su push/PR verso `main`; KartLog carica anche l'APK debug | Copiato da KartLog (con upload dell'APK debug, richiesto dall'accettazione di Fase 0). In più un controllo che il manifest unito non contenga `INTERNET` |
| Build APK | `build-apk.yml`: manuale, keystore da secret base64, `assembleRelease` | Copiato da KartLog (senza lo step `signingReport` che in TPGH serviva per OAuth Google) |
| Release | `release.yml`: manuale con input `version`, taglia CHANGELOG e versione, build firmata, pubblica la release, poi committa il bump su `main` | Copiato da KartLog, cambiati solo nome dell'asset (`PdfToolkit-x.y.z.apk`) e titolo |
| Pulizia Actions | Solo KartLog: `cleanup-runs.yml` manuale | Copiato |
| Dependabot | Solo TPGH; KartLog l'ha tolto di proposito | Non incluso, come KartLog |
| `.gitignore` | Standard Android + keystore e credenziali | Come KartLog, senza le voci specifiche di seed/Python |
| Robolectric | `sdk=34` in `robolectric.properties` (le shadow dell'SDK 36 richiedono JDK 21, la CI usa 17) | Identico |
| README / CHANGELOG | TPGH in inglese, KartLog in italiano. CHANGELOG Keep a Changelog con `[Unreleased]` e bullet `- **Sintesi.** dettaglio` (lo usa `release.yml`) | **Italiano**, come KartLog e come la specifica. Vedi "Punti aperti". Stessa convenzione del CHANGELOG, obbligata da `release.yml` |
| `CLAUDE.md` | Memoria di progetto aggiornata a ogni sessione | Stesso meccanismo, contenuti da SPEC §11.2 |
| `SECURITY.md`, `LICENSE` | Segnalazioni private via GitHub Security Advisories; MIT | Stesso meccanismo, scope riscritto; MIT come i riferimenti |
| Versionamento | KartLog è partito da `versionCode 1` / `0.1.0` | Stesso punto di partenza |

## Cosa non viene dai riferimenti

- Nessun permesso `INTERNET` (SPEC §1): nel manifest c'è `tools:node="remove"` e la CI controlla
  il manifest impacchettato. Entrambi i riferimenti invece lo dichiarano.
- Regole di backup: `data_extraction_rules.xml` e `backup_rules.xml` escludono già
  `filesDir/signatures/` (SPEC §6.5). Si verificano a fondo in Fase 4, quando le firme esistono.

## Fasi

Ogni fase si chiude con build debug verde, test verdi, README, CLAUDE.md e CHANGELOG aggiornati.
Il dettaglio di cosa contiene ogni fase è in SPEC §13; qui solo le note operative.

| Fase | Contenuto | Note |
|---|---|---|
| 0 | Scheletro: Gradle, Hilt, tema, lingue, navigazione, drawer, Home, signing, CI, documentazione, ADR 0001–0002 | Questa |
| 1 | Viewer, apertura da SAF e intent, recenti (Room), password | Entra Room con `RecentDocument`; `app/schemas/` versionata come in KartLog. Impostazioni: modalità di lettura, cancella recenti, svuota cache miniature |
| 2 | `EditSession` con undo/redo, Hub modifiche, rimozione, riordino, rotazione, salvataggio | Entra PdfBox-Android (ADR 0002) e `PDFBoxResourceLoader.init`. Scelta motivata tra WorkManager e servizio con notifica per il salvataggio (SPEC §6.7). Valutare Reorderable (licenza e compatibilità con Compose BOM 2024.12.01) |
| 3 | Aggiunta pagine (PDF, vuote, immagini) e Unisci PDF | Test sulle dimensioni di pagina |
| 4 | Compila e firma, archivio firme | Font Noto Sans (OFL) incluso; verificare le regole di backup già presenti |
| 5 | Ricerca nel testo | `PageCoordinateMapper` con test su pagine ruotate |
| 6 | Rifinitura | Animazioni (incluso il rispetto della riduzione animazioni di sistema), baseline profile, accessibilità, R8, interfaccia `CloudTarget` (SPEC §7.3) |

## Punti aperti

Scelte fatte in Fase 0 dove la specifica o i riferimenti lasciavano margine. Sono facili da
cambiare se l'autore preferisce diversamente.

1. **Lingua di README e CHANGELOG.** SPEC §11.1 dice "la stessa del README dei progetti di
   riferimento", ma TPGH è in inglese e KartLog in italiano. Scelto l'italiano: è la lingua della
   specifica e del riferimento più recente.
2. **Nome del file di specifica.** SPEC §12 parla di `SPEC.md`, nel repository il file è
   `SPEC-1.md`. Lasciato com'è; i documenti puntano a `SPEC-1.md`.
3. **Nome del repository.** SPEC indica `pdf-toolkit` come slug del repo, il repository è
   `PdfToolkit`. Nessun impatto sul codice.
4. **Segreti GitHub.** `build-apk.yml` e `release.yml` richiedono nel repository i secret
   `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
   `RELEASE_KEY_PASSWORD` e `RELEASE_PUSH_TOKEN`, come nei riferimenti. Se si vuole lo stesso
   keystore dei riferimenti, vanno copiati da lì; altrimenti uno nuovo, generato una volta.
5. **Palette.** Toni scelti a mano sui ruoli Material 3, non generati con Material Theme Builder.
