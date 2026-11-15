import 'package:flutter/material.dart';
import '../services/storage_service.dart';
import '../services/update_service.dart';
import '../theme/app_theme.dart';
import '../widgets/update_dialog.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  final StorageService _storageService = StorageService();
  final UpdateService _updateService = UpdateService();

  String _downloadFolder = 'Dahili Depolama / KZBStube';
  int _simultaneous = 3;
  bool _notifications = true;
  bool _darkMode = true;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  void _loadSettings() async {
    final folder = await _storageService.getDownloadDirectory();
    if (mounted) {
      setState(() {
        _downloadFolder = folder;
        _simultaneous = _storageService.getSimultaneousDownloads();
        _notifications = _storageService.getNotificationsEnabled();
        _darkMode = _storageService.getDarkMode();
      });
    }
  }

  void _manualCheckUpdate() async {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Güncellemeler denetleniyor...'),
        duration: Duration(seconds: 2),
        backgroundColor: AppTheme.cardSelected,
      ),
    );

    final update = await _updateService.checkForUpdates(isManual: true);

    if (!mounted) return;

    if (update != null && update.hasUpdate) {
      showDialog(
        context: context,
        builder: (_) => UpdateDialog(updateInfo: update),
      );
    } else {
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          backgroundColor: AppTheme.card,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          title: const Row(
            children: [
              Icon(Icons.check_circle, color: AppTheme.success, size: 24),
              SizedBox(width: 10),
              Text('Güncelsiniz!', style: TextStyle(color: Colors.white, fontSize: 16)),
            ],
          ),
          content: const Text(
            'En güncel sürümü kullanıyorsunuz (v1.0.0).\nYeni bir güncelleme yayınlandığında burada görünecektir.',
            style: TextStyle(color: AppTheme.textSecondary, fontSize: 13, height: 1.4),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Tamam', style: TextStyle(color: AppTheme.primaryRed)),
            ),
          ],
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        backgroundColor: AppTheme.background,
        elevation: 0,
        title: const Text(
          'Ayarlar',
          style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white),
        ),
      ),
      body: ListView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        children: [
          // İndirme Klasörü matching Screenshot 6
          _settingTile(
            icon: Icons.folder_open_rounded,
            title: 'İndirme Klasörü',
            subtitle: _downloadFolder,
            trailing: const Icon(Icons.chevron_right, color: AppTheme.textMuted),
            onTap: () {
              // Folder dialog
              showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  backgroundColor: AppTheme.card,
                  title: const Text('İndirme Konumu', style: TextStyle(color: Colors.white)),
                  content: Text(
                    'Dosyalarınız:\n$_downloadFolder\nklasörüne kaydedilmektedir.',
                    style: const TextStyle(color: AppTheme.textSecondary),
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: const Text('Tamam', style: TextStyle(color: AppTheme.primaryRed)),
                    ),
                  ],
                ),
              );
            },
          ),
          const Divider(color: AppTheme.divider, height: 1),

          // Eşzamanlı İndirme matching Screenshot 6
          _settingTile(
            icon: Icons.file_download_outlined,
            title: 'Eşzamanlı İndirme',
            subtitle: 'Aynı anda kaç dosya indirilsin?',
            trailing: DropdownButton<int>(
              value: _simultaneous,
              dropdownColor: AppTheme.card,
              underline: const SizedBox(),
              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
              items: [1, 2, 3, 5].map((val) {
                return DropdownMenuItem<int>(
                  value: val,
                  child: Text('$val'),
                );
              }).toList(),
              onChanged: (newVal) {
                if (newVal != null) {
                  setState(() => _simultaneous = newVal);
                  _storageService.setSimultaneousDownloads(newVal);
                }
              },
            ),
          ),
          const Divider(color: AppTheme.divider, height: 1),

          // Bildirimler matching Screenshot 6
          _settingTile(
            icon: Icons.notifications_none_rounded,
            title: 'Bildirimler',
            subtitle: 'İndirme durumu bildirimi',
            trailing: Switch(
              value: _notifications,
              onChanged: (val) {
                setState(() => _notifications = val);
                _storageService.setNotificationsEnabled(val);
              },
            ),
          ),
          const Divider(color: AppTheme.divider, height: 1),

          // Koyu Tema matching Screenshot 6
          _settingTile(
            icon: Icons.dark_mode_outlined,
            title: 'Koyu Tema',
            subtitle: 'Uygulama temasını dark yap',
            trailing: Switch(
              value: _darkMode,
              onChanged: (val) {
                setState(() => _darkMode = val);
                _storageService.setDarkMode(val);
              },
            ),
          ),
          const Divider(color: AppTheme.divider, height: 1),

          // Güncellemeleri Denetle (Otomatik In-App Update Butonu!)
          _settingTile(
            icon: Icons.system_update_alt_rounded,
            title: 'Güncellemeleri Denetle',
            subtitle: 'GitHub (kzbs4652/KZBStube) üzerinden kontrol et',
            trailing: const Icon(Icons.refresh, color: AppTheme.primaryRed),
            onTap: _manualCheckUpdate,
          ),
          const Divider(color: AppTheme.divider, height: 1),

          // Hakkında matching Screenshot 6
          _settingTile(
            icon: Icons.info_outline_rounded,
            title: 'Hakkında',
            subtitle: 'KZBStube v1.0.0',
            trailing: const Icon(Icons.chevron_right, color: AppTheme.textMuted),
            onTap: () {
              showAboutDialog(
                context: context,
                applicationName: 'KZBStube',
                applicationVersion: 'v1.0.0',
                applicationIcon: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: AppTheme.primaryRed,
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(Icons.play_arrow, color: Colors.white, size: 24),
                ),
                children: const [
                  SizedBox(height: 10),
                  Text(
                    'YouTube Video ve Müzik İndirici.\nKişisel kullanım için geliştirilmiştir.',
                    style: TextStyle(color: AppTheme.textSecondary, fontSize: 13),
                  ),
                  SizedBox(height: 8),
                  Text(
                    'GitHub: kzbs4652/KZBStube',
                    style: TextStyle(color: AppTheme.primaryRed, fontSize: 12, fontWeight: FontWeight.bold),
                  ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  Widget _settingTile({
    required IconData icon,
    required String title,
    required String subtitle,
    Widget? trailing,
    VoidCallback? onTap,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 4),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: AppTheme.card,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(icon, color: Colors.white, size: 22),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            if (trailing != null) trailing,
          ],
        ),
      ),
    );
  }
}
