package org.koitharu.kotatsu.core.os

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppValidator @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	@SuppressLint("InlinedApi")
	val isOriginalApp = suspendLazy(Dispatchers.Default) {
		val certificates = mapOf(CERT_SHA256.hexToByteArray() to PackageManager.CERT_INPUT_SHA256)
		PackageInfoCompat.hasSignatures(context.packageManager, context.packageName, certificates, false)
	}

	private companion object {
		/**
		 * The certificate Mangaki's release APKs are signed with.
		 *
		 * This decides whether the in-app update check runs at all
		 * ([org.koitharu.kotatsu.core.github.AppUpdateRepository.isUpdateSupported]),
		 * the idea being that a build signed by somebody else is not one this app
		 * should be handing new APKs to.
		 *
		 * It kept upstream's fingerprint through the rebrand, which no Mangaki
		 * build can ever match, so the check silently answered "not the original
		 * app" every time and no reader was told about a single release.
		 */
		private const val CERT_SHA256 = "d5558fa43e74d67ac263f1d071631cc53ec327c24731ccc628f28eae4fb17a5d"
	}
}
