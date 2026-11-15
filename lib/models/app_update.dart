class AppUpdateInfo {
  final String currentVersion;
  final String latestVersion;
  final String tagName;
  final String title;
  final String releaseNotes;
  final String apkUrl;
  final int apkSizeBytes;
  final DateTime publishedAt;
  final bool hasUpdate;

  AppUpdateInfo({
    required this.currentVersion,
    required this.latestVersion,
    required this.tagName,
    required this.title,
    required this.releaseNotes,
    required this.apkUrl,
    required this.apkSizeBytes,
    required this.publishedAt,
    required this.hasUpdate,
  });

  String get formattedSize {
    if (apkSizeBytes <= 0) return '';
    return '${(apkSizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
