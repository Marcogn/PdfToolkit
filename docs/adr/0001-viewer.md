# ADR 0001 — Viewer su PdfRenderer con UI Compose propria

Data: 2026-10-01. Stato: accettata.

## Contesto

Il viewer è la parte più usata dell'app (SPEC §1, §5). Servono modalità continua e a pagina
singola, zoom fino a 5x con re-render della porzione visibile, scrubber, miniature e, più avanti,
overlay per ricerca, compilazione e firma.

Le opzioni considerate sono due: la libreria Jetpack `androidx.pdf` oppure un viewer scritto da noi
in Compose sopra `android.graphics.pdf.PdfRenderer`, che è un'API di piattaforma.

## Decisione

Viewer proprio in Compose sopra `PdfRenderer`.

`androidx.pdf` alla data di oggi è in beta: l'ultima versione è 1.0.0-beta01 del 26 agosto 2026,
senza release stabile. Le note di rilascio di quella versione dicono che `PdfViewer`,
`PdfViewerState`, `EditablePdfViewerFragment`, `AnnotationsView` e `OcrProvider` sono marcati
`@ExperimentalPdfApi` (opt-in obbligatorio). Non offre una modalità a pagina singola paginata, che
è un requisito, e ci toglierebbe il controllo su animazioni e transizioni.

## Conseguenze

- Il rendering è codice nostro: mutex per documento (una sola pagina aperta per volta per istanza
  di `PdfRenderer`), render su `Dispatchers.Default`, cache LRU, tiling oltre una dimensione massima
  di bitmap (SPEC §3.1, §5).
- Sui dispositivi senza le API di Android 15 (o `PdfRendererPreV` via SDK extension) il renderer di
  sistema non disegna annotazioni né valori dei campi modulo. Per questo tutto quello che l'utente
  aggiunge si scrive nel contenuto della pagina (ADR 0002, SPEC §6.5).
- Ricerca e selezione del testo non arrivano gratis: la ricerca passa da PdfBox (SPEC §5.1).
- Da rivalutare quando `androidx.pdf` arriva a stable: potrebbe sostituire il viewer e dare ricerca
  e selezione del testo.

## Fonti

- Note di rilascio di `androidx.pdf`: https://developer.android.com/jetpack/androidx/releases/pdf
  (consultata il 2026-10-01)
- Viewer PDF su Android, `PdfRenderer` e `PdfRendererPreV`:
  https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer
