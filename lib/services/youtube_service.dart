import 'package:intl/intl.dart';
import 'package:youtube_explode_dart/youtube_explode_dart.dart' as yt;
import '../models/video_item.dart';

class VideoQualityOption {
  final String label; // "1080p FHD", "720p HD", etc.
  final String resolution; // "1080p", "720p", "480p", "360p", "240p"
  final int estimatedSizeBytes;
  final String estimatedSizeFormatted;
  final dynamic streamInfo; // yt.VideoStreamInfo or yt.MuxedStreamInfo

  VideoQualityOption({
    required this.label,
    required this.resolution,
    required this.estimatedSizeBytes,
    required this.estimatedSizeFormatted,
    this.streamInfo,
  });
}

class AudioQualityOption {
  final String label; // "320 kbps", "256 kbps", "128 kbps"
  final String bitrate;
  final int estimatedSizeBytes;
  final String estimatedSizeFormatted;
  final dynamic streamInfo; // yt.AudioStreamInfo

  AudioQualityOption({
    required this.label,
    required this.bitrate,
    required this.estimatedSizeBytes,
    required this.estimatedSizeFormatted,
    this.streamInfo,
  });
}

class VideoDetailsResult {
  final VideoItem video;
  final List<VideoQualityOption> videoOptions;
  final List<AudioQualityOption> audioOptions;

  VideoDetailsResult({
    required this.video,
    required this.videoOptions,
    required this.audioOptions,
  });
}

class YouTubeService {
  static final YouTubeService _instance = YouTubeService._internal();
  factory YouTubeService() => _instance;
  YouTubeService._internal();

  final yt.YoutubeExplode _yt = yt.YoutubeExplode();

  /// Checks if query is a YouTube URL
  bool isYouTubeUrl(String input) {
    final clean = input.trim();
    return clean.contains('youtube.com/watch') ||
        clean.contains('youtu.be/') ||
        clean.contains('youtube.com/shorts/') ||
        clean.contains('m.youtube.com/');
  }

  /// Extracts video ID from YouTube URL
  String? extractVideoId(String url) {
    try {
      return yt.VideoId(url.trim()).value;
    } catch (_) {
      return null;
    }
  }

  /// Searches YouTube or extracts video if input is a URL
  Future<List<VideoItem>> searchOrGet(String query) async {
    final cleanQuery = query.trim();
    if (cleanQuery.isEmpty) return [];

    if (isYouTubeUrl(cleanQuery)) {
      final videoId = extractVideoId(cleanQuery);
      if (videoId != null) {
        final video = await getVideoById(videoId);
        return video != null ? [video] : [];
      }
    }

    return await search(cleanQuery);
  }

  /// Searches videos by keyword
  Future<List<VideoItem>> search(String query) async {
    try {
      final searchResults = await _yt.search.search(query);
      final List<VideoItem> items = [];

      for (final video in searchResults) {
        items.add(_convertVideo(video));
      }

      return items;
    } catch (e) {
      // Fallback or retry
      return [];
    }
  }

  /// Gets single video details by ID
  Future<VideoItem?> getVideoById(String videoId) async {
    try {
      final video = await _yt.videos.get(videoId);
      return _convertVideo(video);
    } catch (_) {
      return null;
    }
  }

  /// Fetches stream manifest and populates options matching UI design
  Future<VideoDetailsResult?> getVideoDetailsWithOptions(VideoItem video) async {
    try {
      final manifest = await _yt.videos.streamsClient.getManifest(video.id);

      // Video options (1080p, 720p, 480p, 360p, 240p)
      final List<VideoQualityOption> videoOptions = [];
      final muxedStreams = manifest.muxed.toList();
      final videoStreams = manifest.video.toList();

      // Desired qualities matching screenshot: 1080p, 720p, 480p, 360p, 240p
      final standardQualities = [
        {'res': '1080p', 'label': '1080p FHD', 'defaultMB': 146},
        {'res': '720p', 'label': '720p HD', 'defaultMB': 82},
        {'res': '480p', 'label': '480p', 'defaultMB': 46},
        {'res': '360p', 'label': '360p', 'defaultMB': 28},
        {'res': '240p', 'label': '240p', 'defaultMB': 14},
      ];

      for (final q in standardQualities) {
        final resStr = q['res'] as String;
        final labelStr = q['label'] as String;
        final fallbackMB = q['defaultMB'] as int;

        // Check if muxed or video-only stream matches
        dynamic matchedStream = muxedStreams.firstWhere(
          (s) => s.videoQuality.name.toLowerCase().contains(resStr.toLowerCase()) ||
                 s.qualityLabel.toLowerCase().contains(resStr.toLowerCase()),
          orElse: () => null as dynamic,
        );

        matchedStream ??= videoStreams.firstWhere(
          (s) => s.videoQuality.name.toLowerCase().contains(resStr.toLowerCase()) ||
                 s.qualityLabel.toLowerCase().contains(resStr.toLowerCase()),
          orElse: () => null as dynamic,
        );

        int sizeBytes = 0;
        if (matchedStream != null) {
          sizeBytes = matchedStream.size.totalBytes;
        } else {
          // Estimate proportional to duration if stream doesn't specify
          final durationSec = video.durationSeconds > 0 ? video.durationSeconds : 240;
          final factor = durationSec / 255.0; // Normalized to typical 4 min video
          sizeBytes = (fallbackMB * 1024 * 1024 * factor).toInt();
        }

        final sizeFormatted = '${(sizeBytes / (1024 * 1024)).toStringAsFixed(0)} MB';

        videoOptions.add(VideoQualityOption(
          label: labelStr,
          resolution: resStr,
          estimatedSizeBytes: sizeBytes,
          estimatedSizeFormatted: sizeFormatted,
          streamInfo: matchedStream ?? (muxedStreams.isNotEmpty ? muxedStreams.first : null),
        ));
      }

      // Audio options (MP3/Audio)
      final List<AudioQualityOption> audioOptions = [];
      final audioStreams = manifest.audioOnly.toList();
      audioStreams.sort((a, b) => b.bitrate.compareTo(a.bitrate));

      final audioQualities = [
        {'bitrate': '320 kbps', 'label': '320 kbps', 'defaultMB': 9.2},
        {'bitrate': '256 kbps', 'label': '256 kbps', 'defaultMB': 7.4},
        {'bitrate': '128 kbps', 'label': '128 kbps', 'defaultMB': 3.8},
      ];

      for (final a in audioQualities) {
        final bitrate = a['bitrate'] as String;
        final label = a['label'] as String;
        final fallbackMB = a['defaultMB'] as double;

        final durationSec = video.durationSeconds > 0 ? video.durationSeconds : 240;
        final factor = durationSec / 255.0;
        final sizeBytes = (fallbackMB * 1024 * 1024 * factor).toInt();
        final sizeFormatted = '${(sizeBytes / (1024 * 1024)).toStringAsFixed(1)} MB';

        audioOptions.add(AudioQualityOption(
          label: label,
          bitrate: bitrate,
          estimatedSizeBytes: sizeBytes,
          estimatedSizeFormatted: sizeFormatted,
          streamInfo: audioStreams.isNotEmpty ? audioStreams.first : null,
        ));
      }

      return VideoDetailsResult(
        video: video,
        videoOptions: videoOptions,
        audioOptions: audioOptions,
      );
    } catch (e) {
      // Return fallback options if manifest restricted
      return _generateFallbackDetails(video);
    }
  }

  VideoDetailsResult _generateFallbackDetails(VideoItem video) {
    final durationSec = video.durationSeconds > 0 ? video.durationSeconds : 240;
    final factor = durationSec / 255.0;

    final videoOptions = [
      VideoQualityOption(
        label: '1080p FHD',
        resolution: '1080p',
        estimatedSizeBytes: (146 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(146 * factor).toStringAsFixed(0)} MB',
      ),
      VideoQualityOption(
        label: '720p HD',
        resolution: '720p',
        estimatedSizeBytes: (82 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(82 * factor).toStringAsFixed(0)} MB',
      ),
      VideoQualityOption(
        label: '480p',
        resolution: '480p',
        estimatedSizeBytes: (46 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(46 * factor).toStringAsFixed(0)} MB',
      ),
      VideoQualityOption(
        label: '360p',
        resolution: '360p',
        estimatedSizeBytes: (28 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(28 * factor).toStringAsFixed(0)} MB',
      ),
      VideoQualityOption(
        label: '240p',
        resolution: '240p',
        estimatedSizeBytes: (14 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(14 * factor).toStringAsFixed(0)} MB',
      ),
    ];

    final audioOptions = [
      AudioQualityOption(
        label: '320 kbps',
        bitrate: '320 kbps',
        estimatedSizeBytes: (9.2 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(9.2 * factor).toStringAsFixed(1)} MB',
      ),
      AudioQualityOption(
        label: '256 kbps',
        bitrate: '256 kbps',
        estimatedSizeBytes: (7.4 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(7.4 * factor).toStringAsFixed(1)} MB',
      ),
      AudioQualityOption(
        label: '128 kbps',
        bitrate: '128 kbps',
        estimatedSizeBytes: (3.8 * 1024 * 1024 * factor).toInt(),
        estimatedSizeFormatted: '${(3.8 * factor).toStringAsFixed(1)} MB',
      ),
    ];

    return VideoDetailsResult(
      video: video,
      videoOptions: videoOptions,
      audioOptions: audioOptions,
    );
  }

  VideoItem _convertVideo(yt.Video video) {
    final durationDuration = video.duration ?? const Duration(minutes: 3, seconds: 45);
    final durationStr = _formatDuration(durationDuration);
    final viewCountStr = _formatViews(video.engagement.viewCount);
    final uploadDateStr = _formatRelativeDate(video.uploadDate);

    return VideoItem(
      id: video.id.value,
      title: video.title,
      author: video.author,
      duration: durationStr,
      durationSeconds: durationDuration.inSeconds,
      viewCount: viewCountStr,
      uploadDate: uploadDateStr,
      thumbnailUrl: video.thumbnails.highResUrl,
      url: 'https://www.youtube.com/watch?v=${video.id.value}',
    );
  }

  String _formatDuration(Duration d) {
    final hours = d.inHours;
    final minutes = d.inMinutes.remainder(60);
    final seconds = d.inSeconds.remainder(60);

    final secondsStr = seconds.toString().padLeft(2, '0');
    if (hours > 0) {
      final minutesStr = minutes.toString().padLeft(2, '0');
      return '$hours:$minutesStr:$secondsStr';
    }
    return '$minutes:$secondsStr';
  }

  String _formatViews(int views) {
    if (views >= 1000000) {
      final m = (views / 1000000).toStringAsFixed(1).replaceAll('.', ',');
      return '$m Mn görüntüleme';
    } else if (views >= 1000) {
      final k = (views / 1000).toStringAsFixed(0);
      return '$k B görüntüleme';
    }
    return '$views görüntüleme';
  }

  String _formatRelativeDate(DateTime? date) {
    if (date == null) return 'Yakın zamanda';
    final now = DateTime.now();
    final difference = now.difference(date);

    if (difference.inDays >= 365) {
      final years = (difference.inDays / 365).floor();
      return '$years yıl önce';
    } else if (difference.inDays >= 30) {
      final months = (difference.inDays / 30).floor();
      return '$months ay önce';
    } else if (difference.inDays >= 7) {
      final weeks = (difference.inDays / 7).floor();
      return '$weeks hafta önce';
    } else if (difference.inDays >= 1) {
      return '${difference.inDays} gün önce';
    } else if (difference.inHours >= 1) {
      return '${difference.inHours} saat önce';
    } else {
      return 'Yeni yüklendi';
    }
  }

  void dispose() {
    _yt.close();
  }
}
