# Changelog

All notable changes to PdfToolkit are documented in this file.
The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versioning follows the app's `versionName` in `app/build.gradle.kts`.

## [Unreleased]

## [1.1.0] - 2026-10-08

- **Edit right on the page.** The viewer has a tools bar (a rail at the side in landscape): Highlight, Draw,
  Eraser, Fill and Pages. Tools work on any page on screen, in continuous and single-page mode, at any zoom.
  Undo and Redo sit at the end of the bar, "Save" appears in the top bar (copy or overwrite), and leaving with
  changes asks to save. A tap on the page hides the bars for full-screen reading. The "Edit" button and the
  edit hub are gone; Highlight, Draw and Fill and sign on Home open the viewer with the tool ready.
- **Fill and sign in the viewer.** Tap Fill: form fields get controls right over them (text, check boxes, radio
  buttons, lists) on every page as you scroll, and the bar offers Text, Date, Tick, Cross and Signature. A
  focused field stays above the keyboard. The date is placed at once, ready to edit. With no saved signature
  "Signature" opens the creation, with one it is ready to place, with several (or a long press) the list. Placed
  items move with a finger, resize and turn with two fingers or from the round handle on their corner, and a
  long press grabs another one. Values you entered stay visible after you put the tool down. "Make final" is
  in the save dialog.
- **Highlight, underline, strike out.** Press and hold a word and drag the handles: with a markup tool armed
  the text is marked when you lift the finger, with an "Undo" message. Saved as standard annotations that
  other readers show and can remove. Without a tool, Copy and Highlight float by the selection.
- **Draw by hand.** Pen and marker, each with its own colours and four sizes (the Style button), with a finger
  or a stylus; two fingers move and zoom; a stylus ignores a resting palm. Drawings are saved as standard ink
  annotations, or written into the page with "make final" in the save dialog.
- **Eraser.** Tap an annotation to remove it, also one made by another app (a highlight from Acrobat, a note, a
  stamp); undo brings it back.
- **Highlights and drawings already in a PDF now show** in the viewer, on turned pages too (Android's renderer
  leaves them out). Notes, stamps and shapes are not drawn yet.
- **One "Organize pages" tool** replaces Remove, Reorder, Add pages and Insert images. Tap pages to select them
  (long press, then tap another, for a range); rotate and delete from the bar; drag a page by its handle; "Add"
  inserts another PDF, blank pages or images after the selected page, or after the page you were reading.
  "Pages" in the viewer opens it, asking first to save or discard changes made in the viewer.
- **Fewer steps.** "Overwrite" in the save dialog no longer asks a second time; Save in the exit dialog closes
  the edit once the file is written; "Page X of N" in the viewer opens "Go to page"; hints are short messages
  over the page; every colour and size is at least 48 dp to touch. "Wallpaper colors" in Settings is now
  "Dynamic colors".
- **Larger app.** The drawing library (`androidx.ink`) brings native code: about 5 MB more in the APK that holds
  every processor type.

## [1.0.0] - 2026-10-04

- **Edit button grows into the edit hub.** Tapping "Edit" in the viewer now morphs the button into
  the edit screen (250 ms) instead of a plain slide.
- **Pages come in one after another.** Thumbnails in the page grids fade in with a light stagger when
  the screen opens (only then, not while scrolling). With the system animations turned off
  (Android's "Remove animations") they appear at once.
- **Screen readers in the viewer.** TalkBack announces "Page 3 of 200" for the document and offers
  "Next page" / "Previous page" actions; before, the page area was silent.
- **Smaller release app.** The release build is now minified and trimmed of unused resources (R8),
  and CI builds it on every change.
- **Saving large merges.** Merging or editing many large PDFs at once (for example 25 files of about
  1 GB in all) no longer fails with "Memoria insufficiente per questo documento.": the working data
  of every open file now goes to temporary files in the app's cache instead of memory.
- **Search in the document.** The magnifier in the viewer's top bar turns it into a search field with
  the count ("3 of 17") and previous / next arrows (the keyboard's search key goes to the next one).
  Results appear while the text is being read, page by page, with a thin progress bar; typing waits
  a quarter of a second, and a new word replaces the previous search. Case and accents don't matter
  ("perche" finds "perché"), and a phrase broken over two lines is found. Every occurrence is
  highlighted on the page (the current one stronger), also on turned pages and in single-page mode,
  and the viewer scrolls to the current one and centres it. Closing the search (X or back) removes
  the highlights; they are never written into the PDF.
- **Documents without text.** A scanned PDF says "This document has no searchable text. It may be a
  scan." instead of finding nothing. Password-protected PDFs are searched with the password you typed.
- **Limits.** Text is read in PdfBox's order, so a phrase split across columns may not be found, and
  a word hyphenated at the end of a line counts as two.
- **Fill and sign.** "Fill and sign" (edit hub and Home) shows the pages one at a time, with zoom
  and pan. Forms with fields get a control over every field (text, check box, radio button, drop-down
  list; on a turned page the control turns with it), and the keyboard's "Next" goes to the next field. On any PDF you can tap to add **text**
  (size adjustable), the **date** (in the app's language, editable), a **tick**, a **cross** and an
  **image as signature**; tap one to edit or delete it. Everything can be undone. When saving, a
  form can be **made final** (on by default when a signature was added): its fields become part of
  the page.
- **Written into the page.** Text, ticks, dates and signatures are written into the page content
  with an embedded Noto Sans font, so accents come out right and they show in any reader, rotated
  pages included; form values get their appearance written too (switching to Noto Sans when the
  form's font lacks a character).
- **My signatures.** The new archive (drawer or Home) keeps your signatures on the phone, private to
  the app and left out of Android's backup. Add one by **drawing** it (full-screen landscape canvas, black or
  blue ink whose line thickens when you slow down, Clear / Undo stroke / Save) or **from an image** (crop
  with the corners, "Remove the background" with a threshold slider, preview on a chequer). Rename,
  mark one as your favourite (listed first) or delete it. The first time, a note says a signature is an
  image, not a certified digital signature.
- **Signature in Fill and sign.** The Signature tool now offers the archive (or "New signature" on
  the spot) instead of any gallery image.
- **Move, resize, rotate.** In "Fill and sign" a selected item follows one finger and is resized and
  turned with two (proportions kept; text changes its font size). **Press and hold** any placed item
  to select it and drag it into place. One gesture is one undo step.
- **Filled forms reopened.** Reopening a form saved with "Fill and sign" no longer shows every value
  twice (the saved value under the field and the editable one on top, slightly apart): the field
  controls now cover what the page draws there.
- **Limits.** XFA forms aren't supported: free filling still works.
- **Add pages.** The edit hub's "Add pages" (also on Home) inserts pages from another PDF (pick the
  file, then select pages with "all", "none" and ranges) or blank pages (1 to 50, sized like the
  neighbouring page, with the size shown). New pages go at the start, at the end, or before/after a
  page number. Every addition can be undone, and the snackbar offers Undo.
- **Insert images.** "Insert images" (also on Home) turns photos or files into pages, picking from
  the system photo picker or from files. **Fit to page** gives each page the size of the neighbouring
  one (turned to follow the image, centred, never cropped); **original size** uses the image's size
  (150 DPI when the file has no usable DPI). Photos are saved as JPEG (quality 85, longest side at most
  3000 px when fitting) and images with transparency stay lossless; the camera's EXIF orientation
  is respected and HEIC/HEIF/AVIF are decoded by Android where the device supports them.
- **Merge PDFs.** Pick two or more PDFs from Home, reorder them by dragging, swipe to remove one, "+" to add more. **Merge** asks
  where to save the new file (`<first name>_unito.pdf`); **Merge and edit** opens the edit hub on the
  merged pages first. Files with form fields get a warning.
- **Save snackbar.** "Open" and "Share" after saving are readable again (they used a colour meant
  for normal backgrounds), the snackbars no longer cover the hub's tool bar, and "N pages added ·
  Undo" closes once the document is saved.
- **Clearer tool flows.** "Add pages" and "Insert images" on Home first say that the PDF is
  chosen before the pages or images. The edit hub no longer has "Merge PDFs": on an open document
  that is "Add pages → From another PDF" (it also did nothing when used from the hub).
- **Edit hub shows the document.** The edit hub now shows the thumbnails of the pages as they are
  (after merges, additions, removals and rotations), with the tools in a bar at the bottom and undo
  and redo at the top, instead of a grid of tools that looked like Home.
- **Where did it go?** After adding pages or images the new pages are highlighted ("New" badge)
  and scrolled into view, so you see which they are and where they went. The image dialog shows a preview strip of the picked
  images and the blank-pages dialog a preview of the page; the source dialogs are now two clear rows
  with icon and description.
- **Fix.** Merge crashed right after picking the files (a saved-state key clashed with the route
  argument of the same name).
- **Limits.** PDFs added to another one can't be password-protected yet. Bookmarks of merged files
  are not kept and their form fields may stop working.
- **Edit pages.** An **Edit** button in the viewer (it hides while you scroll down) opens the edit
  hub, and "Remove pages" and "Reorder pages" on Home pick a PDF and open straight on the tool.
  Remove pages by tapping them (press and hold one, then tap another for a range) with an undo
  from the snackbar; reorder by pressing and holding a page and dragging, or from the menu on each
  page (move to start/end, one step, rotate 90°). Undo and redo for every operation.
- **Save.** Save as a copy (default, suggests `<name>_modificato.pdf`) or overwrite the original when
  the file allows it, after a confirmation. The new file is built in a temporary location and only
  copied over the destination when complete, so a failure leaves the original untouched. The save
  keeps running if you leave the app, with a progress bar that doesn't block the screen; after a
  copy the snackbar offers **Open** and **Share**. Leaving with unsaved changes asks Save / Discard /
  Cancel.
- **Limits.** Password-protected PDFs can't be edited yet. A removed page can still be present in
  the file's data if a bookmark or link points to it: don't use it to hide sensitive content.
- **Permissions.** The app now declares `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` for
  the background save; still no `INTERNET`.
- **Complete viewer.** Besides continuous scrolling there is now a single-page mode (swipe to turn
  the page, each page zooms on its own); the choice is remembered and can also be set in Settings.
  The top bar shows "page X of N" and a menu with reading mode, go to page, share and document
  information. A draggable scrubber on the right edge jumps between pages, and a thumbnail bar
  opens from the top bar. Switching mode keeps the page you are on.
- **Recent files.** Opened PDFs appear on Home (up to 10, with a first-page preview and date) and
  in the "Recent files" screen of the side menu. Press and hold to remove one; files that are no
  longer readable are shown as unavailable. The last page read is saved per file and reopening
  resumes from it. Settings can clear the list and the preview cache.
- **Open from other apps.** PdfToolkit appears in "Open with" for PDFs in file managers, downloads
  and mail, and as a target of the share sheet.
- **Password-protected PDFs.** On Android 15 and later the app asks for the password; on earlier
  versions it explains that the system can't open them.
- **Clearer error screen.** When a file can't be opened the message says why, with a button back to
  Home (and one to remove a missing file from recents).
- **Backup.** The recents database is excluded from Android backup: it holds file addresses whose
  permission doesn't survive a restore.
- **Back without the shrinking effect.** System back (button or gesture) used Navigation's default
  animation, which shrinks the screen towards the centre; it now uses the same short slide and
  fade as the arrow in the top bar.
- **PDF viewer, first part.** "Open PDF" on Home opens the system file picker and shows the
  document in continuous scrolling: pinch zoom up to 5x, double tap to switch between fit width
  and 2.5x, pan with fling. Pages stay sharp at any zoom (only the visible area is re-rendered),
  nearby pages are prepared in advance, and rotating the screen keeps the reading position.
  Files that can't be opened show a short message instead of crashing.
- **App skeleton.** Home with the "Open PDF" card, the Recents section and the tool grid; upcoming
  tools are visible with a "Soon" badge. Side menu with Home, Recent files, My signatures,
  Settings and About. Viewer and tools open a placeholder screen for now.
- **Theme and language.** Light, dark or system theme, wallpaper colours (Android 12+) that can be
  turned on in Settings, Italian and English with a per-app choice.
- **No network.** The app does not declare the `INTERNET` permission; CI checks that no
  dependency brings it back.
- **Build and release.** CI with lint, tests and debug APK; manual workflows for the signed
  release APK and for publishing releases, as in the reference projects.
- **Up-to-date toolchain.** Gradle 9.8, Android Gradle Plugin 9.4, Kotlin 2.4, target Android 17
  (API 37) and the latest stable libraries.
