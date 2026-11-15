enum DownloadStatus {
  pending,
  downloading,
  paused,
  completed,
  failed,
  cancelled,
}

class DownloadTask {
  final String id;
  final String videoId;
  final String title;
  final String author;
  final String thumbnailUrl;
  final String format; // 'mp4' or 'mp3'
  final String quality; // '1080p', '720p', '480p', '360p', '320 kbps', etc.
  final String filePath;
  final int totalBytes;
  int downloadedBytes;
  double speedBytesPerSec;
  DownloadStatus status;
  final DateTime createdAt;
  String? error;

  DownloadTask({
    required this.id,
    required this.videoId,
    required this.title,
    required this.author,
    required this.thumbnailUrl,
    required this.format,
    required this.quality,
    required this.filePath,
    required this.totalBytes,
    this.downloadedBytes = 0,
    this.speedBytesPerSec = 0.0,
    this.status = DownloadStatus.pending,
    required this.createdAt,
    this.error,
  });

  double get progress {
    if (totalBytes <= 0) return 0.0;
    return (downloadedBytes / totalBytes).clamp(0.0, 1.0);
  }

  int get progressPercent {
    return (progress * 100).toInt();
  }

  String get speedFormatted {
    if (speedBytesPerSec <= 0) return '0 KB/s';
    if (speedBytesPerSec < 1024 * 1024) {
      return '${(speedBytesPerSec / 1024).toStringAsFixed(1)} KB/s';
    }
    return '${(speedBytesPerSec / (1024 * 1024)).toStringAsFixed(1)} MB/s';
  }

  String get sizeFormatted {
    final downloadedMB = (downloadedBytes / (1024 * 1024)).toStringAsFixed(1);
    final totalMB = (totalBytes / (1024 * 1024)).toStringAsFixed(1);
    return '$downloadedMB MB / $totalMB MB';
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'videoId': videoId,
      'title': title,
      'author': author,
      'thumbnailUrl': thumbnailUrl,
      'format': format,
      'quality': quality,
      'filePath': filePath,
      'totalBytes': totalBytes,
      'downloadedBytes': downloadedBytes,
      'status': status.name,
      'createdAt': createdAt.toIso8601String(),
    };
  }

  factory DownloadTask.fromJson(Map<String, dynamic> json) {
    return DownloadTask(
      id: json['id'] ?? '',
      videoId: json['videoId'] ?? '',
      title: json['title'] ?? '',
      author: json['author'] ?? '',
      thumbnailUrl: json['thumbnailUrl'] ?? '',
      format: json['format'] ?? 'mp4',
      quality: json['quality'] ?? '720p',
      filePath: json['filePath'] ?? '',
      totalBytes: json['totalBytes'] ?? 0,
      downloadedBytes: json['downloadedBytes'] ?? 0,
      status: DownloadStatus.values.firstWhere(
        (e) => e.name == json['status'],
        orElse: () => DownloadStatus.completed,
      ),
      createdAt: DateTime.tryParse(json['createdAt'] ?? '') ?? DateTime.now(),
    );
  }
}
