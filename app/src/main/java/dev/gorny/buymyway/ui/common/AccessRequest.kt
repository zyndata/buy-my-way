package dev.gorny.buymyway.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.gorny.buymyway.R

/**
 * Where an account the access gate refused asks to be let in (decision 60, revised): the
 * repository's issues. False on a phone with no browser, for the caller to say so.
 */
fun openAccessRequest(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, context.getString(R.string.no_access_contact_url).toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
}
