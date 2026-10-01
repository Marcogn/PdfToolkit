# Changelog

Tutte le modifiche rilevanti a PdfToolkit sono documentate in questo file.
Il formato ricalca liberamente [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
il versionamento segue il `versionName` dell'app in `app/build.gradle.kts`.

## [Unreleased]

- **Scheletro dell'app.** Home con la card "Apri PDF", la sezione Recenti e la griglia degli
  strumenti; gli strumenti in arrivo sono visibili con l'etichetta "Presto". Menu laterale con
  Home, File recenti, Le mie firme, Impostazioni e Informazioni. Viewer e strumenti per ora aprono
  una schermata provvisoria.
- **Tema e lingua.** Tema chiaro, scuro o di sistema, colori dello sfondo (Android 12+) attivabili
  in Impostazioni, italiano e inglese con scelta per-app.
- **Nessuna rete.** L'app non dichiara il permesso `INTERNET`; la CI controlla che nessuna
  dipendenza lo reintroduca.
- **Build e rilascio.** CI con lint, test e APK debug; workflow manuali per l'APK release firmato e
  per la pubblicazione delle release, come nei progetti di riferimento.
