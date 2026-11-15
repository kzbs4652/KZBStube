import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:dio/dio.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart' as yt;
import '../models/download_task.dart';
import '../models/video_item.dart';
import 'storage_service.dart';
import 'notification_service.dart';

class DownloadService extends ChangeNotifier {
  static final DownloadService _instance = DownloadService._internal();
  factory DownloadService() => _instance;
  DownloadService._internal();

  final yt.YoutubeExplode _yt = yt.YoutubeExplode();
  final Dio _dio = Dio();
  final Map<String, CancelToken> _cancelTokens = {};

  final List<DownloadTask> _activeTasks = [];
  List<DownloadTask> _completedTasks = [];

  List<DownloadTask> get activeTasks => List.unmodifiable(_activeTasks);
  List<DownloadTask> get completedTasks => List.unmodifiable(_completedTasks);

  Future<void> init() async {
    _completedTasks = await StorageService().getCompletedDownloads();
    notifyListeners();
  }

  /// Starts a new download matching the UI specifications
  Future<DownloadTask> startDownload({
    required VideoItem video,
    required String format, // 'mp4' or 'mp3'
    required String quality, // '1080p', '720p', '320 kbps', etc.
    required int estimatedSizeBytes,
  }) async {
    final downloadDir = await StorageService().getDownloadDirectory();
    final sanitizedTitle = _sanitizeFilename(video.title);
    final ext = format.toLowerCase() == 'mp3' ? 'mp3' : 'mp4';
    final filePath = '$downloadDir/$sanitizedTitle.$ext';

    final task = DownloadTask(
      id: '${video.id}_${DateTime.now().millisecondsSinceEpoch}',
      videoId: video.id,
      title: video.title,
      author: video.author,
      thumbnailUrl: video.thumbnailUrl,
      format: format.toUpperCase(),
      quality: quality,
      filePath: filePath,
      totalBytes: estimatedSizeBytes,
      status: DownloadStatus.downloading,
      createdAt: DateTime.now(),
    );

    _activeTasks.insert(0, task);
    notifyListeners();

    _executeDownload(task);
    return task;
  }

  void _executeDownload(DownloadTask task) async {
    final cancelToken = CancelToken();
    _cancelTokens[task.id] = cancelToken;

    int lastBytes = 0;
    DateTime lastTime = DateTime.now();

    try {
      final manifest = await _yt.videos.streamsClient.getManifest(task.videoId);
      yt.StreamInfo? selectedStream;

      if (task.format == 'MP3') {
        final audioStreams = manifest.audioOnly.toList();
        audioStreams.sort((a, b) => b.bitrate.compareTo(a.bitrate));
        selectedStream = audioStreams.isNotEmpty ? audioStreams.first : null;
      } else {
        // Video MP4: find closest quality
        final muxed = manifest.muxed.toList();
        final videoOnly = manifest.videoOnly.toList();

        final matchMuxed = muxed.firstWhere(
          (s) => s.videoQuality.name.toLowerCase().contains(task.quality.toLowerCase()) ||
                 s.qualityLabel.toLowerCase().contains(task.quality.toLowerCase()),
          orElse: () => null as dynamic,
        );

        selectedStream = matchMuxed ?? (muxed.isNotEmpty ? muxed.first : null);
        if (selectedStream == null && videoOnly.isNotEmpty) {
          selectedStream = videoOnly.first;
        }
      }

      if (selectedStream == null) {
        throw Exception('Uygun yayın akışı bulunamadı.');
      }

      final streamUrl = selectedStream.url.toString();
      final streamSize = selectedStream.size.totalBytes;

      final file = File(task.filePath);
      if (!await file.parent.exists()) {
        await file.parent.create(recursive: true);
      }

      final response = await _dio.get(
        streamUrl,
        cancelToken: cancelToken,
        options: Options(
          responseType: ResponseType.stream,
          headers: {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)',
          },
        ),
      );

      final responseStream = response.data.stream as Stream<List<int>>;
      final sink = file.openWrite();

      final actualTotal = streamSize > 0 ? streamSize : task.totalBytes;

      await for (final chunk in responseStream) {
        if (cancelToken.isCancelled) {
          await sink.close();
          if (await file.exists()) await file.delete();
          return;
        }

        sink.add(chunk);
        task.downloadedBytes += chunk.length;

        // Speed calculation every 500ms
        final now = DateTime.now();
        final elapsed = now.difference(lastTime).inMilliseconds;
        if (elapsed >= 500) {
          final bytesDiff = task.downloadedBytes - lastBytes;
          task.speedBytesPerSec = (bytesDiff / (elapsed / 1000.0));
          lastBytes = task.downloadedBytes;
          lastTime = now;

          if (StorageService().getNotificationsEnabled()) {
            NotificationService().showDownloadProgress(
              id: task.id.hashCode,
              title: task.title,
              progress: task.progressPercent,
              speed: task.speedFormatted,
            );
          }
          notifyListeners();
        }
      }

      await sink.flush();
      await sink.close();

      // Completed successfully
      task.status = DownloadStatus.completed;
      task.speedBytesPerSec = 0;
      _activeTasks.remove(task);
      _completedTasks.insert(0, task);
      await StorageService().saveCompletedDownload(task);

      if (StorageService().getNotificationsEnabled()) {
        NotificationService().cancelNotification(task.id.hashCode);
        NotificationService().showDownloadComplete(
          id: task.id.hashCode,
          title: task.title,
          format: task.format,
        );
      }
      notifyListeners();
    } catch (e) {
      if (cancelToken.isCancelled) {
        task.status = DownloadStatus.cancelled;
      } else {
        task.status = DownloadStatus.failed;
        task.error = e.toString();
      }
      _activeTasks.remove(task);
      notifyListeners();
    } finally {
      _cancelTokens.remove(task.id);
    }
  }

  void pauseDownload(String taskId) {
    final task = _activeTasks.firstWhere(
      (t) => t.id == taskId,
      orElse: () => null as dynamic,
    );
    if (task != null) {
      task.status = DownloadStatus.paused;
      _cancelTokens[taskId]?.cancel();
      _cancelTokens.remove(taskId);
      notifyListeners();
    }
  }

  void resumeDownload(String taskId) {
    final task = _activeTasks.firstWhere(
      (t) => t.id == taskId,
      orElse: () => null as dynamic,
    );
    if (task != null && task.status == DownloadStatus.paused) {
      task.status = DownloadStatus.downloading;
      notifyListeners();
      _executeDownload(task);
    }
  }

  void cancelDownload(String taskId) {
    final task = _activeTasks.firstWhere(
      (t) => t.id == taskId,
      orElse: () => null as dynamic,
    );
    if (task != null) {
      task.status = DownloadStatus.cancelled;
      _cancelTokens[taskId]?.cancel();
      _cancelTokens.remove(taskId);
      _activeTasks.remove(task);
      notifyListeners();
    }
  }

  Future<void> deleteCompletedDownload(String taskId) async {
    _completedTasks.removeWhere((t) => t.id == taskId);
    await StorageService().removeDownload(taskId);
    notifyListeners();
  }

  String _sanitizeFilename(String name) {
    return name
        .replaceAll(RegExp(r'[\\/:*?"<>|]'), '_')
        .replaceAll(' ', '_')
        .replaceAll('__', '_');
  }

  void dispose() {
    _yt.close();
    super.dispose();
  }
}
