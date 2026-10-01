# ADR 0002 — Modifiche con PdfBox-Android dietro l'interfaccia PdfEditor

Data: 2026-10-01. Stato: accettata.

## Contesto

Tutte le scritture (unione, import/rimozione/riordino pagine, immagini, AcroForm, testo e firme nel
contenuto della pagina) hanno bisogno di una libreria PDF che giri sul dispositivo, con una licenza
compatibile con un'app distribuita come APK.

## Decisione

PdfBox-Android (`com.tom-roush:pdfbox-android`), porting di Apache PDFBox 2.0.x, licenza Apache 2.0.

L'ultima release è la v2.0.27.0, pubblicata il 2 gennaio 2025 su Maven Central. Il progetto è poco
attivo, ma copre quello che serve. Per questo la libreria resta isolata dietro un'interfaccia
`PdfEditor` (package `pdf/edit/`), con `applySession(session, overlays, destination)` come
operazione principale: la UI non tocca mai PdfBox e la libreria si può sostituire.

Escluse iText e MuPDF: entrambe sono AGPL (o licenza commerciale), incompatibili con l'uso previsto.

## Conseguenze

- `PDFBoxResourceLoader.init(context)` va chiamato all'avvio (`PdfToolkitApplication`).
- La build release con R8 va provata con le regole indicate nel README della libreria (Fase 6).
- HEIC/HEIF/AVIF non sono gestiti da PdfBox-Android: le immagini si decodificano sempre con
  `ImageDecoder` e a PdfBox arriva una bitmap (SPEC §6.2).
- Firme, testo, spunte e date si scrivono nel content stream (`PDPageContentStream` in append),
  non come annotazioni, per essere visibili anche con il renderer di sistema dei dispositivi meno
  recenti (ADR 0001).
- La dipendenza entra nel progetto con la prima fase che scrive PDF (Fase 2), non prima.

## Fonti

- Release di PdfBox-Android: https://github.com/TomRoush/PdfBox-Android/releases (consultata il
  2026-10-01)
