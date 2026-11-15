import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/update_service.dart';
import '../services/youtube_service.dart';
import '../widgets/update_banner.dart';
import '../widgets/download_options_sheet.dart';
import 'home_screen.dart';
import 'downloads_screen.dart';
import 'settings_screen.dart';

class MainNavigationScreen extends StatefulWidget {
  const MainNavigationScreen({super.key});

  @override
  State<MainNavigationScreen> createState() => _MainNavigationScreenState();
}

class _MainNavigationScreenState extends State<MainNavigationScreen> with WidgetsBindingObserver {
  int _currentIndex = 0;
  final UpdateService _updateService = UpdateService();
  final YouTubeService _ytService = YouTubeService();
  String? _lastDetectedClip;

  final List<Widget> _pages = const [
    HomeScreen(),
    DownloadsScreen(),
    SettingsScreen(),
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    // Check for updates on startup
    _updateService.checkForUpdates();

    // Check clipboard for YouTube links
    _checkClipboardForYouTubeLink();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkClipboardForYouTubeLink();
    }
  }

  void _checkClipboardForYouTubeLink() async {
    try {
      final clipData = await Clipboard.getData(Clipboard.kTextPlain);
      final text = clipData?.text?.trim() ?? '';

      if (text.isNotEmpty && text != _lastDetectedClip && _ytService.isYouTubeUrl(text)) {
        _lastDetectedClip = text;
        if (mounted) {
          _showClipboardDetectedSheet(text);
        }
      }
    } catch (_) {}
  }

  void _showClipboardDetectedSheet(String url) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: const Color(0xFF1E2026),
        behavior: SnackBarBehavior.floating,
        duration: const Duration(seconds: 8),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: Color(0xFFE50914), width: 1),
        ),
        content: Row(
          children: [
            const Icon(Icons.link_rounded, color: Color(0xFFE50914)),
            const SizedBox(width: 10),
            const Expanded(
              child: Text(
                'Panodan YouTube bağlantısı algılandı!',
                style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 13),
              ),
            ),
            TextButton(
              onPressed: () async {
                ScaffoldMessenger.of(context).hideCurrentSnackBar();
                final videoId = _ytService.extractVideoId(url);
                if (videoId != null) {
                  final video = await _ytService.getVideoById(videoId);
                  if (video != null && mounted) {
                    showModalBottomSheet(
                      context: context,
                      isScrollControlled: true,
                      backgroundColor: Colors.transparent,
                      builder: (_) => FractionallySizedBox(
                        heightFactor: 0.85,
                        child: DownloadOptionsSheet(video: video),
                      ),
                    );
                  }
                }
              },
              child: const Text('İndir', style: TextStyle(color: Color(0xFFE50914), fontWeight: FontWeight.bold)),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            // In-app Update Banner (if new release on GitHub)
            AnimatedBuilder(
              animation: _updateService,
              builder: (context, _) {
                final update = _updateService.availableUpdate;
                if (update != null && update.hasUpdate) {
                  return UpdateBannerWidget(
                    updateInfo: update,
                    onDismiss: () => setState(() {}),
                  );
                }
                return const SizedBox.shrink();
              },
            ),
            // Current Selected Page
            Expanded(
              child: IndexedStack(
                index: _currentIndex,
                children: _pages,
              ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: _currentIndex,
        onTap: (index) => setState(() => _currentIndex = index),
        selectedItemColor: const Color(0xFFE50914),
        unselectedItemColor: const Color(0xFF8E8E93),
        backgroundColor: const Color(0xFF121316),
        type: BottomNavigationBarType.fixed,
        selectedLabelStyle: const TextStyle(fontWeight: FontWeight.bold, fontSize: 12),
        unselectedLabelStyle: const TextStyle(fontWeight: FontWeight.normal, fontSize: 12),
        items: const [
          BottomNavigationBarItem(
            icon: Icon(Icons.home_filled),
            label: 'Ana Sayfa',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.file_download_outlined),
            activeIcon: Icon(Icons.file_download),
            label: 'İndirilenler',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.settings_outlined),
            activeIcon: Icon(Icons.settings),
            label: 'Ayarlar',
          ),
        ],
      ),
    );
  }
}
