import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:dio/dio.dart';
import 'package:path_provider/path_provider.dart';
import 'package:open_filex/open_filex.dart';
import '../models/app_update.dart';

class UpdateService extends ChangeNotifier {
  static final UpdateService _instance = UpdateService._internal();
  factory UpdateService() => _instance;
  UpdateService._internal();

  static const String currentVersion = '1.0.0';
  static const String currentVersionTag = 'v1.0.0';
  static const String githubRepo = 'kzbs4652/KZBStube';

  AppUpdateInfo? _availableUpdate;
  bool _isChecking = false;
  bool _isDownloading = false;
  double _downloadProgress = 0.0;
  String _downloadStatusText = '';

  AppUpdateInfo? get availableUpdate => _availableUpdate;
  bool get isChecking => _isChecking;
  bool get isDownloading => _isDownloading;
  double get downloadProgress => _downloadProgress;
  String get downloadStatusText => _downloadStatusText;

  /// Checks GitHub Releases API for new version
  Future<AppUpdateInfo?> checkForUpdates({bool isManual = false}) async {
    _isChecking = true;
    notifyListeners();

    try {
      final url = Uri.parse('https://api.github.com/repos/$githubRepo/releases/latest');
      final response = await http.get(url, headers: {
        'Accept': 'application/vnd.github.v3+json',
        'User-Agent': 'KZBStube-App',
      }).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        final tagName = (data['tag_name'] as String?) ?? '';
        final cleanTag = tagName.replaceAll('v', '').trim();
        final title = (data['name'] as String?) ?? 'Yeni Yazılım Güncellemesi';
        final body = (data['body'] as String?) ?? 'Hata düzeltmeleri ve performans iyileştirmeleri.';
        final publishedAtStr = (data['published_at'] as String?) ?? '';

        // Find APK asset
        String apkDownloadUrl = '';
        int apkSize = 0;
        final assets = data['assets'] as List<dynamic>? ?? [];
        for (final asset in assets) {
          final name = (asset['name'] as String?) ?? '';
          if (name.endsWith('.apk')) {
            apkDownloadUrl = (asset['browser_download_url'] as String?) ?? '';
            apkSize = (asset['size'] as int?) ?? 0;
            break;
          }
        }

        final hasNewer = _isVersionNewer(cleanTag, currentVersion);

        final updateInfo = AppUpdateInfo(
          currentVersion: currentVersion,
          latestVersion: cleanTag,
          tagName: tagName,
          title: title,
          releaseNotes: body,
          apkUrl: apkDownloadUrl,
          apkSizeBytes: apkSize,
          publishedAt: DateTime.tryParse(publishedAtStr) ?? DateTime.now(),
          hasUpdate: hasNewer,
        );

        if (hasNewer) {
          _availableUpdate = updateInfo;
        } else {
          _availableUpdate = null;
        }

        notifyListeners();
        return updateInfo;
      }
    } catch (e) {
      debugPrint('Update check error: $e');
    } finally {
      _isChecking = false;
      notifyListeners();
    }
    return null;
  }

  /// Downloads the update APK and prompts package installer
  Future<bool> downloadAndInstallUpdate(AppUpdateInfo updateInfo) async {
    if (updateInfo.apkUrl.isEmpty) {
      _downloadStatusText = 'İndirme bağlantısı bulunamadı.';
      notifyListeners();
      return false;
    }

    _isDownloading = true;
    _downloadProgress = 0.0;
    _downloadStatusText = 'Güncelleme indiriliyor...';
    notifyListeners();

    try {
      final tempDir = await getTemporaryDirectory();
      final filePath = '${tempDir.path}/KZBStube_${updateInfo.latestVersion}.apk';
      final file = File(filePath);

      if (await file.exists()) {
        await file.delete();
      }

      final dio = Dio();
      await dio.download(
        updateInfo.apkUrl,
        filePath,
        onReceiveProgress: (received, total) {
          if (total > 0) {
            _downloadProgress = received / total;
            final recMB = (received / (1024 * 1024)).toStringAsFixed(1);
            final totMB = (total / (1024 * 1024)).toStringAsFixed(1);
            _downloadStatusText = '$recMB MB / $totMB MB (%${(_downloadProgress * 100).toInt()})';
            notifyListeners();
          }
        },
      );

      _downloadStatusText = 'Paket yükleniyor...';
      notifyListeners();

      // Trigger Android APK installation
      final result = await OpenFilex.open(filePath, type: 'application/vnd.android.package-archive');
      _isDownloading = false;
      notifyListeners();

      return result.type == ResultType.done;
    } catch (e) {
      _downloadStatusText = 'Hata: $e';
      _isDownloading = false;
      notifyListeners();
      return false;
    }
  }

  bool _isVersionNewer(String latest, String current) {
    try {
      final latestParts = latest.split('.').map((e) => int.tryParse(e) ?? 0).toList();
      final currentParts = current.split('.').map((e) => int.tryParse(e) ?? 0).toList();

      for (int i = 0; i < 3; i++) {
        final l = i < latestParts.length ? latestParts[i] : 0;
        final c = i < currentParts.length ? currentParts[i] : 0;
        if (l > c) return true;
        if (l < c) return false;
      }
    } catch (_) {}
    return false;
  }
}
