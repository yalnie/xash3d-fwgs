package su.xash.engine.ui.downloader

data class GameDownloadInfo(
	val id: String,
	val name: String,
	val hasHdVersion: Boolean,
	val baseSizeBytes: Long,
	val hdSizeBytes: Long,
	val baseInstalledBytes: Long,
	val hdInstalledBytes: Long,
	var isHdSelected: Boolean = false
)
