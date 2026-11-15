import 'dart:convert';
import 'dart:io';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/download_task.dart';

class StorageService {
  static final StorageService _instance = StorageService._internal();
  factory StorageService() => _instance;
  StorageService._internal();

  static const String _keyDownloads = 'kzbstube_downloads';
  static const String _keyDownloadFolder = 'kzbstube_download_folder';
  static const String _keySimultaneous = 'kzbstube_simultaneous_downloads';
  static const String _keyNotifications = 'kzbstube_notifications_enabled';
  static const String _keyDarkMode = 'kzbstube_dark_mode';
  static const String _keySearchHistory = 'kzbstube_search_history';

  late SharedPreferences _prefs;

  Future<void> init() async {
    _prefs = await SharedPreferences.getInstance();
  }

  // --- Downloads persistence ---
  Future<List<DownloadTask>> getCompletedDownloads() async {
    final raw = _prefs.getStringList(_keyDownloads) ?? [];
    return raw.map((item) => DownloadTask.fromJson(jsonDecode(item))).toList();
  }

  Future<void> saveCompletedDownload(DownloadTask task) async {
    final list = await getCompletedDownloads();
    // Remove duplicate if exists
    list.removeWhere((item) => item.id == task.id);
    list.insert(0, task);

    final raw = list.map((item) => jsonEncode(item.toJson())).toList();
    await _prefs.setStringList(_keyDownloads, raw);
  }

  Future<void> removeDownload(String taskId) async {
    final list = await getCompletedDownloads();
    final itemToRemove = list.firstWhere(
      (e) => e.id == taskId,
      orElse: () => null as dynamic,
    );

    if (itemToRemove != null) {
      // Also delete physical file if exists
      try {
        final file = File(itemToRemove.filePath);
        if (await file.exists()) {
          await file.delete();
        }
      } catch (_) {}
    }

    list.removeWhere((item) => item.id == taskId);
    final raw = list.map((item) => jsonEncode(item.toJson())).toList();
    await _prefs.setStringList(_keyDownloads, raw);
  }

  // --- Settings ---
  Future<String> getDownloadDirectory() async {
    final saved = _prefs.getString(_keyDownloadFolder);
    if (saved != null && saved.isNotEmpty) return saved;

    try {
      Directory? baseDir;
      if (Platform.isAndroid) {
        // Standard Android Downloads directory
        baseDir = Directory('/storage/emulated/0/Download/KZBStube');
      } else {
        baseDir = await getApplicationDocumentsDirectory();
      }

      if (!await baseDir.exists()) {
        await baseDir.create(recursive: true);
      }
      return baseDir.path;
    } catch (_) {
      final docDir = await getApplicationDocumentsDirectory();
      return docDir.path;
    }
  }

  Future<void> setDownloadDirectory(String path) async {
    await _prefs.setString(_keyDownloadFolder, path);
  }

  int getSimultaneousDownloads() {
    return _prefs.getInt(_keySimultaneous) ?? 3;
  }

  Future<void> setSimultaneousDownloads(int count) async {
    await _prefs.setInt(_keySimultaneous, count);
  }

  bool getNotificationsEnabled() {
    return _prefs.getBool(_keyNotifications) ?? true;
  }

  Future<void> setNotificationsEnabled(bool enabled) async {
    await _prefs.setBool(_keyNotifications, enabled);
  }

  bool getDarkMode() {
    return _prefs.getBool(_keyDarkMode) ?? true;
  }

  Future<void> setDarkMode(bool dark) async {
    await _prefs.setBool(_keyDarkMode, dark);
  }

  // --- Search History ---
  List<String> getSearchHistory() {
    return _prefs.getStringList(_keySearchHistory) ?? [
      'mavi espor',
      'türkçe pop',
      'lofi remix',
    ];
  }

  Future<void> addSearchHistory(String query) async {
    final clean = query.trim();
    if (clean.isEmpty) return;

    final history = getSearchHistory();
    history.removeWhere((item) => item.toLowerCase() == clean.toLowerCase());
    history.insert(0, clean);
    if (history.length > 20) {
      history.removeRange(20, history.length);
    }
    await _prefs.setStringList(_keySearchHistory, history);
  }

  Future<void> clearSearchHistory() async {
    await _prefs.remove(_keySearchHistory);
  }
}
