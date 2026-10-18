# ADR 0007 — All files access, so recents keep PDFs from other apps

Date: 2026-10-18. Status: accepted (author, issue #47). Amends spec §3.4 ("No storage permission").

## Context

A PDF opened from a file manager (Solid Explorer) or a mail attachment arrives as a `content://` URI with a
temporary read grant. Android keeps that grant only while the receiving activity's stack lives: *"Permissions
granted in an Intent remain in effect while the stack of the receiving Activity is active. When the stack
finishes, the permissions are automatically removed."*
([Sharing a file](https://developer.android.com/training/secure-file-sharing/share-file)). Only a provider
that adds `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` lets the app keep it
([persist permissions](https://developer.android.com/training/data-storage/shared/documents-files#persist-permissions));
file managers usually don't. So the entry worked in recents until the app closed, then read "File
unavailable" although the file hadn't moved.

The author ruled out copying the file into app storage and finding it again by hand in the picker; recents
must reopen the file where it is, and say it is unavailable once it has moved.

## Decision

**The app asks for all files access, and opens such a PDF by its path.** Android 11+:
`MANAGE_EXTERNAL_STORAGE`, granted by the user on a system settings page. Android 8–10, where it doesn't exist:
`READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion` 29) and legacy storage on Android 10.

- **When it is asked**: only when a `VIEW`/`SEND` PDF comes with a grant the app can't keep. A dialog explains
  why; "Not now" is remembered and the dialog doesn't come back (back or a tap outside asks again next time).
  Settings has an "All files access" row with the state and a way to change it.
- **The path**: Android has no API from a `FileProvider` URI to a path. The app opens the URI, reads the link of
  the descriptor in `/proc/self/fd`, and keeps a path only if `stat` finds the same file the descriptor has open
  (same device and inode, or same size and modification time, since FUSE and the file system under it may
  report different inodes). `/mnt/user/N/…`, `/mnt/pass_through/N/…`, `/data/media/N/…` and `/sdcard/…` are also
  tried as `/storage/…` (`candidatePaths`). Pipes, sockets and cloud files have no path: they open as before.
- **Then** the viewer opens `file://<path>`: recents store that URI, a moved or deleted file reads "File
  unavailable", "Overwrite" is offered (`File.canWrite()`), and sharing goes through a `FileProvider`
  (`${applicationId}.files`, root `storage/`), since `file://` URIs can't be handed to other apps.
- Without the access nothing changes: the URI opens as it is, and the entry later reads "Access expired"
  (`RecentDocument.temporaryAccess`).

## Alternatives

- **A copy in app storage** at opening: always reopens, but duplicates users' files, and edits would go to the
  copy. Ruled out by the author.
- **Find the file again** in the system picker when an entry has expired: keeps the original, one extra step
  per file, and the picker can't start in the file's folder (the provider's URI isn't a document URI). Ruled out
  by the author.
- **Remove recents for such files**: loses a feature that works for files picked in the app.

## Consequences

- With the access on, the app can read and write all of shared storage, not only the files the user opens. It
  uses it for nothing else, and it adds no network use (ADR 0006 stands).
- Google Play allows `MANAGE_EXTERNAL_STORAGE` for "document management" apps, with a declaration in the Play
  Console ([policy](https://support.google.com/googleplay/android-developer/answer/10467955)). The app is
  distributed through GitHub Releases today; a Play listing would need that declaration.
- The `/proc/self/fd` link is not a documented contract: it has to be checked on devices, with the file managers
  in use (Solid Explorer first). Where it fails, the app falls back to the old behaviour.
