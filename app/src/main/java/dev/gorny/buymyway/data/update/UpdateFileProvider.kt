package dev.gorny.buymyway.data.update

import androidx.core.content.FileProvider
import dev.gorny.buymyway.R

/**
 * Lends the package installer the release APK this app downloaded, and nothing else: its paths
 * are `@xml/update_paths`, one directory. A class of its own because two providers of the same
 * class cannot live in one package, and the camera already has [androidx.core.content.FileProvider].
 */
class UpdateFileProvider : FileProvider(R.xml.update_paths)
