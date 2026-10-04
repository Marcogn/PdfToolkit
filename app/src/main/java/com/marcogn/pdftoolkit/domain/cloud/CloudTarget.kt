package com.marcogn.pdftoolkit.domain.cloud

import java.io.InputStream

/**
 * Where a finished PDF can be sent (spec §7.3). Product phase 2 only: WebDAV is the first target.
 * Nothing implements it and nothing calls it in product phase 1, which has no `INTERNET` permission;
 * it exists so that phase 2 adds an implementation and a screen, not a change to the save flow.
 *
 * The members are an assumption: the spec names the interface but not its shape.
 */
interface CloudTarget {
    /** Name shown to the user, e.g. the server's host. */
    val displayName: String

    /**
     * Uploads one file. [content] is opened once and closed by the target, so a retry calls it again.
     * Failures come back as a failed [Result], never as an exception the caller has to know about.
     */
    suspend fun upload(fileName: String, mimeType: String, content: () -> InputStream): Result<Unit>
}
