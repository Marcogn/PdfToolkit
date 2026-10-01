# PdfToolkit — Specifica funzionale e tecnica

> Documento di riferimento per l'implementazione con Claude Code.
> Versione 1.1 — 1 ottobre 2026 (aggiunte: ricerca testo, unione PDF; evidenziazione pianificata in Fase 2)
> Nomi: **PdfToolkit** (nome visualizzato), **pdf-toolkit** (repo, slug, artifact), **PT** (prefisso breve per classi/tag dove serve).

---

## 0. Come usare questo documento (per l'agente)

1. Leggi tutto il documento prima di scrivere codice.
2. Leggi i due progetti di riferimento (§2). Se non li trovi nel workspace, **chiedi il percorso o il link del repo**. Non inventare la loro struttura.
3. Lavora per fasi (§13). Ogni fase si chiude solo quando i criteri di accettazione sono soddisfatti, la build debug passa e README, CLAUDE.md e CHANGELOG sono aggiornati.
4. Dove questa specifica è in conflitto con i progetti di riferimento su stack, struttura Gradle, signing, CI e convenzioni, **vincono i progetti di riferimento**. Dove la specifica chiede esplicitamente qualcosa di diverso, vince la specifica.
5. Se un requisito non è chiaro o non è fattibile come scritto, fermati e chiedi. Non risolvere ambiguità in silenzio.
6. Le sezioni marcate **[ASSUNZIONE]** sono scelte di default che l'utente può cambiare. Implementale come scritte, ma tienile isolate e facili da modificare.

**Modelli consigliati:** Opus per la Fase 0 (analisi dei progetti di riferimento e piano), Sonnet per le fasi di implementazione. Se Sonnet si blocca su un problema di architettura, torna a Opus solo per quella decisione.

---

## 1. Cos'è PdfToolkit

App Android per leggere e modificare file PDF in locale, sul telefono, senza account e senza cloud nella prima fase.

Due blocchi principali:

- **Lettura** (la funzione più importante): zoom, scorrimento continuo o a pagina singola, navigazione veloce tra le pagine.
- **Ricerca nel testo** del documento aperto.
- **Modifica**: unione di più PDF, aggiunta di pagine (da un altro PDF, pagine vuote, immagini), rimozione, riordino, compilazione e firma.

Fase 2 (pianificata, non da implementare ora): scansione documenti con OCR, caricamento su cloud, evidenziazione, disegno a mano libera, esportazione in formato OpenDocument.

**Principio guida:** tutto gira sul dispositivo. In Fase 1 l'app **non dichiara il permesso `INTERNET`**.

---

## 2. Progetti di riferimento

| Progetto | Cosa riprendere |
|---|---|
| **ThePatientGamerHelper** | Stack (Kotlin, Jetpack Compose, Room, Hilt, MVVM con StateFlow), struttura Gradle e version catalog, gestione tema chiaro/scuro/sistema, lingua IT/EN per-app, keystore fisso, varianti debug/release |
| **KartLog** (MKW tracker) | Struttura della home a pulsantoni quadrati raggiungibili anche da menu hamburger a sinistra, pattern di navigazione |
| **Entrambi** | Workflow di CI/CD (GitHub Actions o equivalente presente nei repo), script di build e release, gestione di README e CHANGELOG |

Le due app hanno presentazione diversa ma struttura simile. **Mantieni la struttura** (moduli, package, layering, nomenclatura, pipeline), e dai a PdfToolkit un aspetto suo (§9).

Azioni concrete:
- Copia i workflow di CI dai repo di riferimento adattando nomi, applicationId e artifact.
- Riusa la stessa configurazione di signing (keystore fisso, stesso SHA1 tra build). Le credenziali restano fuori dal repo, come nei progetti di riferimento.
- `applicationId`: stesso prefisso dei progetti di riferimento seguito da `.pdftoolkit`. Se il prefisso non è ricavabile, chiedi.

---

## 3. Decisioni tecniche principali

### 3.1 Motore di rendering: `android.graphics.pdf.PdfRenderer` con viewer custom in Compose

Il viewer è scritto da noi in Compose sopra `PdfRenderer` (API di piattaforma).

Perché non la libreria Jetpack `androidx.pdf`:
- È in **beta** (1.0.0-beta01, agosto 2026) e le API Compose (`PdfViewer`, `PdfViewerState`) e `EditablePdfViewerFragment` sono ancora marcate `@ExperimentalPdfApi`.
- Non offre una modalità a pagina singola paginata, che è un requisito.
- Il controllo fine su animazioni e transizioni ci serve per l'obiettivo "moderno e snappy".

Annota questa scelta in `docs/adr/0001-viewer.md`. Va rivalutata quando `androidx.pdf` arriva a stable: potrebbe sostituire il viewer e dare gratis ricerca testo e selezione.

Vincoli di `PdfRenderer` da rispettare:
- **Una sola pagina aperta per volta** per istanza. Serializza l'accesso (un `Mutex` per documento) e chiudi sempre la pagina dopo il render.
- Il render avviene su `Dispatchers.Default`, mai sul main thread.
- Su dispositivi senza le API di Android 15 (o il backport `PdfRendererPreV` via SDK extension), il renderer di sistema **non disegna le annotazioni né i valori dei campi modulo**. Conseguenza pratica: tutto ciò che l'utente aggiunge (firma, testo, timbri) va scritto **nel contenuto della pagina**, non come annotazione. Vedi §6.5.

### 3.2 Motore di modifica: PdfBox-Android

Tutte le scritture passano da **PdfBox-Android** (`com.tom-roush:pdfbox-android`, porting di Apache PDFBox 2.0.x, licenza Apache 2.0).

- Ultima release: 2.0.27.0. Il progetto è poco attivo da qualche anno. Va bene per quello che ci serve (import/rimozione/riordino pagine, immagini, AcroForm, scrittura nel content stream), ma va isolato dietro un'interfaccia (`PdfEditor`) per poterlo sostituire.
- Chiamare `PDFBoxResourceLoader.init(context)` all'avvio.
- Verificare le regole R8/ProGuard nel README della libreria: la build release deve funzionare (testarla).
- **Esclusi** iText e MuPDF: licenza AGPL, incompatibile con l'uso che vogliamo farne.

### 3.3 Stack

Come ThePatientGamerHelper. In assenza di indicazioni diverse dal progetto di riferimento:

- Kotlin, Jetpack Compose, Material 3, Navigation Compose
- Hilt, Room, DataStore (preferenze), Coroutines/Flow, MVVM con `StateFlow`
- `minSdk`: come il progetto di riferimento, ma non sotto 26 **[ASSUNZIONE]**
- `targetSdk` / `compileSdk`: l'ultimo stabile supportato dall'AGP in uso
- Test: JUnit per la logica di modifica (sessione di editing, calcolo dimensioni pagina, mapping coordinate), test Compose UI solo per i flussi principali

### 3.4 Accesso ai file

- Apertura con Storage Access Framework (`ActivityResultContracts.OpenDocument` con `application/pdf`). Nessun permesso di storage.
- Chiamare `takePersistableUriPermission` per poter riaprire i file recenti.
- L'app si registra come visualizzatore PDF: `intent-filter` per `ACTION_VIEW` con `mimeType="application/pdf"` (schema `content` e `file`), e `ACTION_SEND` per ricevere un PDF condiviso.
- Immagini: Photo Picker (`PickVisualMedia`) più SAF per i file non presenti nella galleria.
- PDF protetti da password: chiedere la password e aprirli se le API disponibili lo consentono. Se non è possibile sul dispositivo, messaggio chiaro, nessun crash.

---

## 4. Navigazione e struttura delle schermate

```
                ┌────────────────────┐
  avvio senza ─►│  Home (Strumenti)  │◄── hamburger da ogni schermata
  documento     └─────────┬──────────┘
                          │ "Apri PDF" / tap su uno strumento
                          ▼
  avvio con   ─►┌────────────────────┐   FAB "Modifica"   ┌──────────────────┐
  intent PDF    │       Viewer       │ ─────────────────► │   Hub modifiche  │
                └────────────────────┘                    └────────┬─────────┘
                                                                   ▼
                          Pagine (aggiungi / rimuovi / riordina) · Immagini · Compila e firma
                                                                   │
                                                                   ▼
                                                        Salva (copia / sovrascrivi)
```

Drawer (hamburger a sinistra), sempre raggiungibile: Home, File recenti, Le mie firme, Impostazioni, Informazioni.

### 4.1 Home (avvio senza documento)

Struttura come KartLog: griglia di pulsanti quadrati grandi.

- In alto, card evidente **"Apri PDF"**.
- Sotto, sezione **"Recenti"** (orizzontale, massimo 10, con miniatura prima pagina, nome, data). Swipe o long press per rimuovere dalla lista. Se un file non è più accessibile, va mostrato come tale e si può togliere.
- Griglia strumenti:
  - Unisci PDF
  - Aggiungi pagine
  - Inserisci immagini
  - Rimuovi pagine
  - Riordina pagine
  - Compila e firma
  - Le mie firme
- Gli strumenti di Fase 2 compaiono già, **disabilitati** con etichetta "Presto": Scansiona, Carica su cloud, Evidenzia, Disegna, Esporta in ODF. Tap → snackbar breve, nessuna navigazione.

Tap su uno strumento senza documento aperto: si apre il selettore PDF e, scelto il file, si va **direttamente allo strumento**, non al viewer.

### 4.2 Viewer

- Top bar: nome file (ellissi al centro se lungo), indicatore "pagina X di N", icona **Cerca** (§5.1), menu con: modalità di scorrimento, vai a pagina, condividi, informazioni documento.
- **Modalità continua** (default): scorrimento verticale di tutte le pagine.
- **Modalità pagina singola**: una pagina per schermata, swipe orizzontale, snap alla pagina.
- La modalità scelta si ricorda (preferenza globale) **[ASSUNZIONE]**.
- **Zoom**: pinch, doppio tap (alterna adatta-larghezza ↔ 2,5x centrato sul punto toccato), zoom minimo = adatta pagina, massimo 5x. Pan quando zoomato.
- **Scrubber**: barra laterale trascinabile per saltare velocemente tra le pagine, con fumetto del numero pagina.
- Barra miniature apribile dal basso per saltare a una pagina.
- La pagina di lettura si salva per file e si riprende alla riapertura.
- **FAB "Modifica"**: in basso a destra, si nasconde scorrendo in giù e riappare scorrendo in su o fermandosi. Apre l'Hub modifiche sul documento corrente.

### 4.3 Hub modifiche

Stessi strumenti della Home, legati al documento aperto. Presentazione: schermata a griglia con transizione dal FAB (container transform o equivalente). In alto, nome del documento e numero di pagine.

---

## 5. Lettura: dettagli di rendering

L'obiettivo è che la lettura sia fluida anche su PDF di centinaia di pagine.

- **Render a due livelli**: prima una bitmap alla risoluzione di adatta-larghezza, poi, quando lo zoom si ferma (debounce ~150 ms), un re-render ad alta risoluzione **solo della porzione visibile** (`PdfRenderer.Page.render` con matrice di trasformazione e clip). Mentre si zooma si scala la bitmap esistente.
- **Cache** LRU delle bitmap dimensionata sulla memoria disponibile (`ActivityManager.memoryClass`), con prefetch di ±2 pagine rispetto a quella visibile.
- Dimensione massima di una bitmap limitata (evitare OOM): se il render richiesto supera il limite, si va a tile.
- In lista continua, i placeholder hanno già le proporzioni corrette della pagina (letti all'apertura) così lo scroll non salta.
- Colore di sfondo dietro le pagine diverso tra tema chiaro e scuro. Le pagine restano bianche: **niente** inversione colori in Fase 1.
- Errore di apertura (file corrotto, formato non supportato): schermata d'errore con messaggio comprensibile e pulsante per tornare alla Home.

Criterio di prestazione: apertura di un PDF di 200 pagine e prima pagina visibile in meno di 1 secondo su un dispositivo di fascia media; scroll senza scatti evidenti.

### 5.1 Ricerca nel testo

**Estrazione del testo**
- Implementazione unica con PdfBox: una sottoclasse di `PDFTextStripper` che raccoglie, per ogni pagina, il testo e la posizione dei caratteri (`TextPosition`). Così funziona su tutte le versioni di Android supportate.
- Le API di testo di piattaforma (`PdfRenderer.Page` su API 35, `PdfRendererPreV` sulle versioni precedenti con SDK extension) possono servire come ottimizzazione futura. In Fase 1 non vanno usate: due percorsi di codice da tenere allineati non valgono il guadagno.
- L'indice si costruisce in background, pagina per pagina, la prima volta che l'utente apre la ricerca. Non all'apertura del documento, per non rallentare la lettura.
- L'indice resta in memoria finché il documento è aperto. Una cache su disco (chiave: URI + dimensione + data di modifica) è facoltativa: va aggiunta solo se le misure su PDF grandi la giustificano.

**Interfaccia**
- Tap su **Cerca**: la top bar diventa un campo di ricerca con contatore "3 di 17" e frecce precedente/successivo.
- I risultati arrivano pagina per pagina mentre l'indicizzazione procede. Nessuna attesa bloccante; durante l'indicizzazione si vede un indicatore discreto.
- Debounce sull'input di circa 250 ms. Una nuova query annulla quella in corso.
- Ogni occorrenza è evidenziata sulla pagina con un rettangolo semitrasparente disegnato in overlay (non scritto nel PDF). L'occorrenza corrente ha un colore più marcato. Passando alla successiva, il viewer scorre fino alla pagina e centra il risultato.
- La ricerca ignora maiuscole/minuscole e accenti: "perche" trova "perché". Si normalizza in NFD e si tolgono i diacritici sia dall'indice sia dalla query, mantenendo la corrispondenza con le posizioni originali.
- Un a-capo nel testo estratto vale come spazio, così una frase spezzata su due righe viene trovata.
- Chiudere la ricerca (indietro o X) rimuove le evidenziazioni.

**Limiti da gestire**
- PDF scansionati (solo immagini): nessun testo da cercare. Se l'indice è vuoto, messaggio esplicito: "Questo documento non contiene testo ricercabile. Potrebbe essere una scansione." L'OCR della Fase 2 risolverà questo caso.
- Pagine ruotate e testo con trasformazioni: verificare il sistema di coordinate di `TextPosition` e coprire con test la conversione verso lo schermo tramite `PageCoordinateMapper`, incluse pagine ruotate di 90° e 180°.
- L'ordine di estrazione di PdfBox può non coincidere con l'ordine di lettura (colonne, tabelle). È accettabile: va scritto nei limiti noti del README.
- Ricerca disponibile solo nel viewer. Nelle schermate di modifica no.

---

## 6. Modifica

### 6.1 Sessione di modifica (modello comune)

Tutte le modifiche di pagina lavorano su una **sessione** in memoria, non sul file:

```kotlin
data class EditSession(
    val sourceUri: Uri,
    val pages: List<PageItem>,      // ordine finale
    val history: UndoStack,         // annulla / ripeti
)

sealed interface PageItem {
    val id: String                  // stabile, per animazioni e drag
    data class FromPdf(val docRef: DocRef, val pageIndex: Int, val rotation: Int = 0) : PageItem
    data class Blank(val widthPt: Float, val heightPt: Float) : PageItem
    data class FromImage(val imageUri: Uri, val mode: ImageFit, val widthPt: Float, val heightPt: Float) : PageItem
}
```

- `DocRef` può puntare al documento principale o a un PDF aggiunto.
- Annulla/ripeti per ogni operazione.
- Le overlay di compilazione e firma (§6.5) si legano al `PageItem.id`, così sopravvivono a riordini.
- Uscire con modifiche non salvate chiede conferma (Salva / Scarta / Annulla).
- La sessione sopravvive alla rotazione e alla process death per quanto ragionevole (`SavedStateHandle` per lo stato leggero; per i dati pesanti va bene perderli con avviso).

### 6.2 Aggiungere pagine

Punto di inserimento scelto dall'utente: prima di / dopo la pagina selezionata, in testa, in coda.

**Da un altro PDF**
- Selezione del file, poi griglia di miniature con selezione multipla (tutte / nessuna / intervallo).
- Le pagine mantengono la loro dimensione originale.

**Pagine vuote**
- Numero di pagine da inserire (1–50).
- Dimensione: quella della **pagina adiacente** al punto di inserimento (la precedente; se si inserisce in testa, la successiva) **[ASSUNZIONE]**. Se il documento ha pagine di dimensioni diverse, il dialog mostra la dimensione che verrà usata.

**Da immagini**
- Selezione multipla dal Photo Picker o da file. Ogni immagine diventa una pagina.
- Due modalità, scelte nel dialog e applicate a tutte le immagini selezionate:
  - **Adatta alla pagina**: la pagina ha la dimensione della pagina di riferimento (stessa regola delle pagine vuote); l'immagine è scalata mantenendo le proporzioni e centrata, senza ritagli. Orientamento della pagina che segue quello dell'immagine (una foto orizzontale su pagina A4 diventa A4 orizzontale) **[ASSUNZIONE]**.
  - **Dimensione originale**: la pagina prende le dimensioni dell'immagine. Conversione pixel → punti usando i DPI dei metadati se presenti, altrimenti **150 DPI** **[ASSUNZIONE]** (a 72 DPI una foto da 4000 px darebbe una pagina di oltre 1,4 m).
- Rispettare l'orientamento EXIF.
- HEIC/HEIF/AVIF: PdfBox-Android non li gestisce direttamente. Decodificare sempre con `ImageDecoder` di Android e passare a PdfBox la bitmap.
- Compressione: JPEG qualità 85 per le foto, lossless per immagini con trasparenza. Lato lungo massimo 3000 px in modalità "adatta" per tenere il PDF leggero **[ASSUNZIONE]**.

### 6.3 Rimuovere pagine

- Griglia di miniature, selezione multipla con tap, "seleziona tutto", selezione per intervallo (long press su una pagina, poi tap su un'altra).
- Non si può rimuovere l'ultima pagina rimasta.
- Animazione di uscita delle miniature rimosse; annullabile da snackbar e da undo.

### 6.4 Riordinare pagine

- Griglia di miniature con drag & drop (long press per prendere la pagina, auto-scroll ai bordi).
- Per Compose valutare la libreria **Reorderable** (Calvin Liang); verificare la licenza e la compatibilità con la versione di Compose in uso prima di adottarla. In alternativa, implementazione propria.
- Azioni rapide: sposta in testa, sposta in coda, ruota 90°.
- Feedback aptico alla presa e al rilascio.

### 6.5 Compila e firma

**Modulo con campi (AcroForm)**
- All'apertura dello strumento, PdfBox legge i campi del modulo. Se ci sono, si entra in modalità compilazione: sopra la pagina renderizzata compaiono controlli Compose allineati ai rettangoli dei campi (testo, checkbox, radio, menu a tendina).
- Navigazione tra campi con "Avanti/Indietro" della tastiera.
- Moduli XFA: non supportati. Messaggio esplicito, si può comunque usare la compilazione libera.

**Compilazione libera (PDF senza campi)**
Molti moduli sono PDF "piatti". Strumenti disponibili:
- **Testo**: casella posizionabile, trascinabile, ridimensionabile; dimensione carattere regolabile.
- **Spunta** (✓) e **croce** (✗).
- **Data** (formato della lingua dell'app, modificabile).
- **Firma** (vedi sotto).

**Font**: includere un font TTF con licenza libera (es. Noto Sans, OFL) ed embeddarlo con `PDType0Font`, così lettere accentate e caratteri speciali escono sempre corretti.

**Firma**
- Fonti: disegnata col dito oppure caricata da immagine.
- **Disegno**: canvas a tutto schermo in orizzontale, tratto con spessore che varia leggermente con la velocità, colori nero e blu, pulsanti Pulisci / Annulla tratto / Salva. Per il tratto si può usare `androidx.ink` o un'implementazione Compose propria.
- **Da immagine**: dopo la scelta, ritaglio e opzione "rimuovi sfondo" (soglia sul bianco → trasparente) con anteprima.
- **Archivio firme ("Le mie firme")**: le firme sono PNG con trasparenza salvate nello storage interno dell'app (`filesDir/signatures/`), non accessibile ad altre app. Metadati in Room: nome, tipo, data, preferita. Le firme vanno **escluse dal backup automatico Android** (`dataExtractionRules` / `fullBackupContent`). Si possono rinominare, eliminare, impostare come preferita.
- **Posizionamento**: si sceglie una firma salvata (o se ne crea una al volo), compare sulla pagina, si sposta, ridimensiona (proporzioni bloccate) e ruota con i gesti.

**Salvataggio di compilazione e firma**
- Testo, spunte, date e firme si scrivono **nel contenuto della pagina** (`PDPageContentStream` in modalità append), non come annotazioni. Così sono visibili in qualunque lettore, incluso il nostro renderer su Android meno recenti (§3.1).
- Campi AcroForm: si impostano i valori con generazione delle appearance. Opzione **"Rendi definitivo"** (flatten del modulo), **attiva di default se il documento contiene una firma** **[ASSUNZIONE]**.

**Nota da mostrare nell'app (una volta, nella schermata firma, e nelle Informazioni):** la firma inserita è un'immagine apposta sul documento. Non è una firma digitale con certificato e non ha il valore legale di una firma elettronica qualificata.

### 6.6 Unire PDF

Strumento dedicato, separato da "Aggiungi pagine". Serve a combinare due o più documenti interi in un nuovo file.

**Da Home**
- Selezione multipla di PDF dal selettore (minimo 2). L'utente può aggiungerne altri dopo, con un pulsante "+".
- Lista dei file da unire: miniatura della prima pagina, nome, numero di pagine. Riordino con drag & drop, rimozione con swipe.
- Pulsante **Unisci**: crea una `EditSession` con tutte le pagine nell'ordine dei file e va al salvataggio come copia. Nome proposto: `<nome primo file>_unito.pdf`.
- Pulsante secondario **Unisci e modifica**: crea la stessa sessione ma apre l'Hub modifiche, così si possono togliere o riordinare pagine prima di salvare.

**Dal viewer (Hub modifiche)**
- Voce "Unisci con altro PDF": il documento aperto è il primo della lista, poi si scelgono gli altri. Stesso flusso di sopra.

**Note**
- Nessuna logica di unione dedicata nel motore: lo strumento costruisce una `EditSession` e la scrittura passa da `PdfEditor.applySession`, come per tutte le altre modifiche.
- PDF protetti da password: si chiede la password per ognuno al momento dell'aggiunta alla lista.
- Segnalibri (outline) e campi modulo dei file uniti: in Fase 1 non si preservano. Se un file sorgente ha campi AcroForm, avvisare che nel risultato i campi potrebbero non funzionare e proporre di renderli definitivi prima dell'unione **[ASSUNZIONE]**. Da scrivere tra i limiti noti del README.

### 6.7 Salvataggio

Al termine di qualsiasi modifica:
- **Salva come copia** (default): `CreateDocument`, nome proposto `<nome>_modificato.pdf`.
- **Sovrascrivi**: solo se l'URI concede la scrittura. Conferma esplicita.
- Scrittura sempre prima su file temporaneo in `cacheDir`, poi copia sulla destinazione. Se qualcosa fallisce, l'originale resta intatto.
- Operazione lunga: indicatore di avanzamento non bloccante; per documenti grandi la scrittura deve sopravvivere al passaggio in background (WorkManager o servizio con notifica, a scelta dell'agente motivandola).
- Dopo il salvataggio: snackbar con "Apri" e "Condividi".

---

## 7. Fase 2 (pianificata, non implementare)

Nel codice di Fase 1 servono solo: le voci disabilitate in Home/Hub e le interfacce già pronte dove indicato. Niente dipendenze di Fase 2 nel progetto.

### 7.1 Scansione
- **ML Kit Document Scanner** (Google Play services): flusso UI già pronto, elaborazione interamente sul dispositivo, nessun permesso fotocamera richiesto all'app, ritaglio/filtri/rimozione ombre, output JPEG o PDF.
- Limite: richiede Google Play services. Su dispositivi senza, la voce resta disabilitata con spiegazione.

### 7.2 OCR
- **ML Kit Text Recognition v2**, on-device, script latino (copre l'italiano).
- Uso: creare un PDF "ricercabile" aggiungendo sopra l'immagine un livello di testo invisibile (rendering mode invisibile in PdfBox). Il posizionamento del testo è approssimato per riga; va bene per cercare e copiare, non per riprodurre l'impaginazione.

### 7.3 Caricamento su cloud
- Primo obiettivo: **WebDAV** (Nextcloud, NAS personali), con URL, utente e password salvati cifrati. Upload di PDF e scansioni.
- Google Drive solo come opzione secondaria, eventualmente più avanti.
- Interfaccia `CloudTarget` già definita in Fase 1 (solo interfaccia, nessuna implementazione).
- In Fase 2 si aggiunge il permesso `INTERNET`; va indicato nel CHANGELOG e nelle Informazioni.

### 7.4 Livello annotazioni: evidenziazione e disegno a mano libera

Evidenziazione e disegno condividono la stessa infrastruttura, per questo stanno insieme.

**Prerequisiti**
- Selezione del testo nel viewer (long press e maniglie), costruita sull'indice di testo della ricerca (§5.1).
- Un livello annotazioni nel viewer che legge le annotazioni esistenti con PdfBox e le disegna in overlay. Serve perché il renderer di sistema, sui dispositivi senza le API di Android 15, non le mostra (§3.1).

**Evidenziazione**
- Su testo selezionato: evidenzia (alcuni colori), sottolinea, barra.
- Salvataggio come annotazioni standard di tipo Highlight/Underline/StrikeOut **con appearance stream generato**. Così restano visibili, modificabili e cancellabili anche in altri lettori. A differenza di firme e testo (§6.5), qui l'annotazione è la scelta corretta: un'evidenziazione scritta nel contenuto della pagina non si potrebbe più togliere.
- Gomma per rimuovere evidenziazioni, incluse quelle create da altre app.

**Disegno a mano libera**
- `androidx.ink` per i tratti, con penna, evidenziatore libero, gomma, annulla/ripeti.
- Salvataggio come annotazioni Ink con appearance stream, coerente con l'evidenziazione. Opzione "rendi definitivo" per scriverle nel contenuto della pagina.

### 7.5 Esportazione OpenDocument
Da dire chiaramente: **una conversione fedele PDF → ODT (testo modificabile con impaginazione) sul telefono non è realistica**. Il PDF non contiene paragrafi o struttura, solo glifi posizionati.

Opzioni realistiche, da decidere all'inizio della Fase 2:
- **ODG** (Draw): una pagina per pagina PDF, con l'immagine della pagina. Fedele all'aspetto, non modificabile come testo.
- **ODT** con un'immagine per pagina più il testo estratto (o da OCR) sotto ogni immagine. Utile per recuperare il testo, impaginazione persa.

Il formato ODF è uno ZIP con file XML (il file `mimetype` va scritto per primo e non compresso), quindi si può generare senza librerie pesanti.

---

## 8. Dati persistenti

**Room**
- `RecentDocument(uri PK, displayName, sizeBytes, pageCount, lastPage, lastOpenedAt, thumbnailPath)`
- `Signature(id PK, name, type: DRAWN|IMPORTED, filePath, createdAt, isDefault)`

**DataStore**
- tema (chiaro / scuro / sistema), lingua (IT / EN / sistema), modalità di lettura, ultima scelta "salva come copia / sovrascrivi", ultima modalità immagini.

**File**
- `filesDir/signatures/` — PNG firme (escluse dal backup)
- `cacheDir/thumbnails/` — miniature recenti (rigenerabili)
- `cacheDir/work/` — temporanei di salvataggio, svuotati all'avvio

---

## 9. Aspetto e animazioni

Obiettivo: moderna, professionale, ordinata. Non deve sembrare un'app di utility generica piena di banner.

- Material 3 con colore seed proprio (proposta: blu petrolio profondo; accento ambra per le azioni di firma) **[ASSUNZIONE]**. Dynamic color disattivato di default, attivabile nelle impostazioni.
- Tema chiaro / scuro / sistema, come nei progetti di riferimento.
- Tipografia: un solo font per l'interfaccia, gerarchia chiara, niente testo in maiuscolo diffuso.
- Icona app semplice: foglio con angolo piegato e un elemento che richiami gli strumenti. Adaptive icon e icona monocromatica per Android 13+.
- Pulsanti strumento della Home: quadrati, angoli arrotondati, icona grande e etichetta breve, leggera elevazione; stato premuto con scala 0,96.

**Animazioni** (devono aiutare, non rallentare):
- Transizioni tra schermate: 200–250 ms, fade-through o slide breve. Mai oltre 300 ms.
- FAB → Hub modifiche: container transform.
- Miniature: ingresso a cascata leggero (solo alla prima apparizione), animazione di riposizionamento durante riordino e rimozione (`animateItem`).
- Cambio modalità di lettura: crossfade.
- Rispettare l'impostazione di sistema per ridurre le animazioni.

**Navigazione snappy** — requisiti misurabili:
- Il tap su un pulsante dà feedback visivo entro un frame.
- Nessun lavoro di I/O o render sul main thread durante una transizione.
- Le schermate mostrano subito lo scheletro (layout e placeholder), i contenuti arrivano dopo.
- Baseline profile per i percorsi principali (avvio, apertura viewer, hub modifiche).

**Accessibilità**: content description su tutte le icone, target di tocco ≥ 48 dp, contrasto AA, scrubber e drag & drop utilizzabili anche con azioni alternative (menu "sposta").

---

## 10. Lingue, impostazioni, informazioni

- Italiano e inglese, selezione per-app (come i progetti di riferimento). Nessuna stringa hardcoded.
- Impostazioni: tema, colore dinamico, lingua, modalità di lettura predefinita, cancella cronologia recenti, svuota cache miniature.
- Informazioni: versione, licenze open source delle dipendenze, nota sulla firma (§6.5), disclaimer AI (§11).

---

## 11. Documentazione del repository

L'agente crea e **mantiene sempre aggiornati** questi file. Aggiornarli fa parte di ogni fase: una fase non è chiusa se la documentazione è indietro.

### 11.1 README.md
Lingua: la stessa del README dei progetti di riferimento.

Contenuto:
- Cos'è PdfToolkit in due o tre frasi.
- Funzioni disponibili e funzioni pianificate (Fase 2), elenco breve.
- Requisiti (versione minima di Android).
- Come compilare (debug e release), dove mettere il keystore, variabili necessarie.
- Struttura del progetto in poche righe.
- Privacy: in Fase 1 nessuna connessione di rete, i file restano sul dispositivo.
- Licenze delle librerie principali.
- **Disclaimer**: l'app è stata sviluppata con l'aiuto di strumenti di intelligenza artificiale (Claude Code), con revisione e test dell'autore.

### 11.2 CLAUDE.md
Memoria di progetto per le sessioni future di Claude Code. Corto e operativo:
- comandi di build, test, lint
- architettura in breve e dove sta cosa
- regole non ovvie (una pagina aperta per volta nel renderer, tutte le scritture passano da `PdfEditor`, overlay scritte nel content stream, evidenziazioni della ricerca solo in overlay e mai nel PDF, nessun permesso `INTERNET` in Fase 1)
- convenzioni di naming (prefisso `PT` solo dove serve a evitare collisioni)
- **regola fissa**: a fine di ogni attività aggiornare README, CLAUDE.md e CHANGELOG se qualcosa è cambiato
- link a questa specifica e agli ADR

### 11.3 CHANGELOG.md
Formato come nei progetti di riferimento.

### 11.4 Come devono essere scritti questi documenti

I documenti devono sembrare scritti da una persona, non generati. Regole:
- Frasi semplici e dirette. Prima l'informazione, poi l'eventuale spiegazione.
- Niente aperture del tipo "Benvenuto in…", "Questo progetto è una potente soluzione…".
- Niente aggettivi promozionali (potente, innovativo, all-in-one, seamless, robusto).
- Niente emoji nei titoli né elenchi decorativi.
- Niente riepiloghi finali che ripetono quanto già detto.
- Elenchi solo dove servono davvero (requisiti, comandi). Il resto in prosa breve.
- Tono da sviluppatore che spiega il proprio progetto a un collega.
- Il disclaimer AI è una frase, scritta in modo sobrio.

---

## 12. Architettura e organizzazione del codice

Segui il layering dei progetti di riferimento. Indicativamente:

```
app/
  ui/            # schermate Compose, ViewModel, navigazione, tema
    home/  viewer/  search/  edithub/  merge/  pages/  images/  fillsign/  signatures/  settings/
  domain/        # EditSession, PageItem, use case (nessuna dipendenza Android dove possibile)
  data/          # Room, DataStore, repository, accesso file (SAF)
  pdf/
    render/      # PdfDocumentRenderer: PdfRenderer + mutex + cache + tiling
    edit/        # PdfEditor (interfaccia) + PdfBoxEditor (implementazione)
    forms/       # lettura campi AcroForm, mapping coordinate PDF ↔ schermo
    text/        # estrazione testo con posizioni, indice di ricerca, normalizzazione
  di/            # moduli Hilt
docs/
  adr/
SPEC.md  README.md  CLAUDE.md  CHANGELOG.md
```

Punti da curare:
- **Coordinate**: PDF ha origine in basso a sinistra in punti, lo schermo in alto a sinistra in pixel, con zoom, pan e rotazione pagina di mezzo. Una sola classe (`PageCoordinateMapper`) fa queste conversioni, con test unitari.
- `PdfEditor` espone operazioni di alto livello (`applySession(session, overlays, destination)`), la UI non tocca PdfBox.
- Gestione errori uniforme: errori tipizzati nel domain, messaggi localizzati nella UI.

---

## 13. Piano di implementazione

Ogni fase: build debug verde, test della fase verdi, documentazione aggiornata, commit con messaggio chiaro.

**Fase 0 — Analisi e scheletro** (Opus)
- Lettura dei progetti di riferimento, piano scritto in `docs/plan.md` con quello che si riprende e da dove.
- Progetto Gradle, version catalog, Hilt, tema, lingue, navigazione, drawer, Home con pulsanti (strumenti ancora vuoti), signing, CI copiata e adattata.
- README, CLAUDE.md, CHANGELOG iniziali; ADR 0001 (viewer) e 0002 (PdfBox-Android).
- *Accettazione*: l'app si installa, Home e drawer funzionano, tema e lingua cambiano, la CI produce l'APK debug.

**Fase 1 — Viewer**
- Apertura da SAF e da intent esterni, recenti, renderer con mutex/cache/tiling, modalità continua e singola, zoom, scrubber, miniature, ripresa ultima pagina, password.
- *Accettazione*: criteri di §5, apertura da file manager e da "condividi".

**Fase 2 — Sessione di modifica e gestione pagine**
- `EditSession` con undo/redo, Hub modifiche con transizione dal FAB, rimozione, riordino, rotazione, salvataggio (copia e sovrascrittura) con file temporaneo.
- *Accettazione*: rimuovere/riordinare/ruotare su un PDF di 100 pagine e salvare; il file si apre correttamente anche in un altro lettore.

**Fase 3 — Aggiunta pagine e unione**
- Da altro PDF, pagine vuote, immagini nelle due modalità, EXIF, HEIC, compressione.
- Strumento "Unisci PDF" (§6.6) da Home e da Hub modifiche.
- *Accettazione*: test unitari sul calcolo delle dimensioni; PDF misti (pagine A4 e Letter) gestiti come da §6.2; unione di 3 PDF con riordino dei file, risultato corretto in un altro lettore.

**Fase 4 — Compila e firma**
- Archivio firme (disegno e import), compilazione AcroForm, compilazione libera, posizionamento firma, scrittura nel content stream, flatten, nota legale.
- *Accettazione*: firma e testo visibili dopo il salvataggio sia nell'app sia in un altro lettore; firme escluse dal backup (verificare la configurazione).

**Fase 5 — Ricerca nel testo**
- Estrazione con posizioni, indicizzazione progressiva, UI di ricerca, evidenziazione in overlay, normalizzazione accenti, gestione PDF senza testo (§5.1).
- *Accettazione*: test unitari su normalizzazione e su match a cavallo di un a-capo; su un PDF di 200 pagine i primi risultati compaiono mentre l'indicizzazione è ancora in corso; risultati posizionati correttamente anche su pagine ruotate.

**Fase 6 — Rifinitura**
- Animazioni secondo §9, baseline profile, accessibilità, build release con R8 testata, voci di Fase 2 disabilitate, revisione finale della documentazione.
- *Accettazione*: build release installata e provata sui flussi principali.

---

## 14. Fuori ambito (Fase 1)

Selezione e copia del testo, annotazioni ed evidenziazione (Fase 2, §7.4), note testuali, firma digitale con certificato, cifratura/protezione con password in scrittura, compressione dei PDF esistenti, conservazione di segnalibri e campi modulo nell'unione, tutto ciò che è in §7.

---

## 15. Fonti tecniche verificate (1 ottobre 2026)

- Jetpack PDF (`androidx.pdf`), note di rilascio: https://developer.android.com/jetpack/androidx/releases/pdf
- Android PDF viewer e `PdfRenderer`/`PdfRendererPreV`: https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer
- `PdfRendererPreV` (reference): https://developer.android.com/reference/android/graphics/pdf/PdfRendererPreV
- Android 15 DP2, novità PdfRenderer: https://android-developers.googleblog.com/2024/03/the-second-developer-preview-of-android-15.html
- PdfBox-Android, release: https://github.com/TomRoush/PdfBox-Android/releases
- PdfBox-Android, issue su HEIC/HEIF/AVIF (#564): https://github.com/TomRoush/PdfBox-Android/issues
- ML Kit Document Scanner: https://developers.google.com/ml-kit/vision/doc-scanner
- ML Kit Text Recognition v2: https://developers.google.com/ml-kit/vision/text-recognition/v2
