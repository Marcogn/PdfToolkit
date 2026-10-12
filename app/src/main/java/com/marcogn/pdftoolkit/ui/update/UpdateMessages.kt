package com.marcogn.pdftoolkit.ui.update

import android.content.res.Resources
import com.marcogn.pdftoolkit.R
import com.marcogn.pdftoolkit.domain.update.ApkRejection
import com.marcogn.pdftoolkit.domain.update.UpdateFailure
import com.marcogn.pdftoolkit.domain.update.UpdateFrequency

/** A short, user-facing reason for [this]; the detail strings of the system/exception are appended only where useful. */
fun UpdateFailure.toMessage(resources: Resources): String = when (this) {
    is UpdateFailure.Network -> resources.getString(R.string.update_error_network)
    UpdateFailure.RateLimited -> resources.getString(R.string.update_error_rate_limited)
    is UpdateFailure.Http -> resources.getString(R.string.update_error_http, code)
    is UpdateFailure.InvalidResponse -> resources.getString(R.string.update_error_invalid_response)
    is UpdateFailure.UnusableRelease -> resources.getString(R.string.update_error_unusable_release)
    UpdateFailure.Storage -> resources.getString(R.string.update_error_storage)
    UpdateFailure.SizeMismatch, UpdateFailure.ChecksumMismatch -> resources.getString(R.string.update_error_corrupted)
    is UpdateFailure.VerificationFailed -> resources.getString(
        when (reason) {
            ApkRejection.UNREADABLE -> R.string.update_error_unreadable
            ApkRejection.DIFFERENT_PACKAGE -> R.string.update_error_different_package
            ApkRejection.NOT_NEWER -> R.string.update_error_not_newer
            ApkRejection.DIFFERENT_SIGNATURE -> R.string.update_error_different_signature
        },
    )
    is UpdateFailure.InstallFailed ->
        if (message.isNullOrBlank()) resources.getString(R.string.update_error_install)
        else resources.getString(R.string.update_error_install_detail, message)
}

fun UpdateFrequency.labelRes(): Int = when (this) {
    UpdateFrequency.NEVER -> R.string.update_frequency_never
    UpdateFrequency.EVERY_START -> R.string.update_frequency_every_start
    UpdateFrequency.DAILY -> R.string.update_frequency_daily
    UpdateFrequency.WEEKLY -> R.string.update_frequency_weekly
}
