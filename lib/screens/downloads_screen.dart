import 'dart:io';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:open_filex/open_filex.dart';
import '../models/download_task.dart';
import '../services/download_service.dart';
import '../theme/app_theme.dart';
import 'active_downloads_screen.dart';

class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  final DownloadService _downloadService = DownloadService();
  String _activeFilter = 'VIDEO'; // 'VIDEO' or 'AUDIO'

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        backgroundColor: AppTheme.background,
        elevation: 0,
        title: const Text(
          'İndirilenler',
          style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold, color: Colors.white),
        ),
        actions: [
          AnimatedBuilder(
            animation: _downloadService,
            builder: (context, _) {
              final activeCount = _downloadService.activeTasks.length;
              if (activeCount == 0) return const SizedBox.shrink();

              return TextButton.icon(
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const ActiveDownloadsScreen()),
                  );
                },
                icon: const SizedBox(
                  width: 14,
                  height: 14,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    valueColor: AlwaysStoppedAnimation<Color>(AppTheme.primaryRed),
                  ),
                ),
                label: Text(
                  'İndiriliyor ($activeCount)',
                  style: const TextStyle(color: AppTheme.primaryRed, fontWeight: FontWeight.bold, fontSize: 13),
                ),
              );
            },
          ),
        ],
      ),
      body: AnimatedBuilder(
        animation: _downloadService,
        builder: (context, _) {
          final completed = _downloadService.completedTasks;
          final videos = completed.where((t) => t.format == 'MP4').toList();
          final audios = completed.where((t) => t.format == 'MP3').toList();

          final currentList = _activeFilter == 'VIDEO' ? videos : audios;

          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Filter Buttons matching Screenshot 5
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Row(
                  children: [
                    _filterPill(
                      title: 'Videolar (${videos.length})',
                      icon: Icons.video_library_rounded,
                      isActive: _activeFilter == 'VIDEO',
                      onTap: () => setState(() => _activeFilter = 'VIDEO'),
                    ),
                    const SizedBox(width: 12),
                    _filterPill(
                      title: 'Müzikler (${audios.length})',
                      icon: Icons.music_note_rounded,
                      isActive: _activeFilter == 'AUDIO',
                      onTap: () => setState(() => _activeFilter = 'AUDIO'),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 8),

              // Media Items List
              Expanded(
                child: currentList.isEmpty
                    ? _buildEmptyState()
                    : ListView.separated(
                        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                        itemCount: currentList.length,
                        separatorBuilder: (_, __) => const SizedBox(height: 12),
                        itemBuilder: (context, index) {
                          final task = currentList[index];
                          return _buildDownloadItem(task);
                        },
                      ),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _filterPill({
    required String title,
    required IconData icon,
    required bool isActive,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        decoration: BoxDecoration(
          color: isActive ? AppTheme.primaryRed : AppTheme.card,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: isActive ? AppTheme.primaryRed : AppTheme.border,
          ),
        ),
        child: Row(
          children: [
            Icon(icon, size: 16, color: Colors.white),
            const SizedBox(width: 6),
            Text(
              title,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 13,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDownloadItem(DownloadTask task) {
    final isVideo = task.format == 'MP4';
    final totalMB = (task.totalBytes / (1024 * 1024)).toStringAsFixed(1);

    return InkWell(
      onTap: () => _openMediaFile(task.filePath),
      borderRadius: BorderRadius.circular(10),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            // Thumbnail or Music Icon matching Screenshot 5
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: isVideo
                  ? CachedNetworkImage(
                      imageUrl: task.thumbnailUrl,
                      width: 90,
                      height: 52,
                      fit: BoxFit.cover,
                      placeholder: (_, __) => Container(color: AppTheme.card, width: 90, height: 52),
                      errorWidget: (_, __, ___) => Container(
                        color: AppTheme.card,
                        width: 90,
                        height: 52,
                        child: const Icon(Icons.movie, color: Colors.grey),
                      ),
                    )
                  : Container(
                      width: 52,
                      height: 52,
                      decoration: BoxDecoration(
                        color: const Color(0xFF2A1517),
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: AppTheme.primaryRed.withOpacity(0.3)),
                      ),
                      child: const Center(
                        child: Icon(Icons.music_note, color: AppTheme.primaryRed, size: 28),
                      ),
                    ),
            ),
            const SizedBox(width: 12),

            // Metadata: Title and Size/Quality
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    isVideo ? '${task.title}.mp4' : '${task.title}.mp3',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '$totalMB MB • ${task.quality}',
                    style: const TextStyle(
                      fontSize: 12,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),

            // 3 Dots Menu Button matching Screenshot 5
            PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert, color: AppTheme.textSecondary),
              color: AppTheme.card,
              onSelected: (val) {
                if (val == 'open') {
                  _openMediaFile(task.filePath);
                } else if (val == 'delete') {
                  _downloadService.deleteCompletedDownload(task.id);
                }
              },
              itemBuilder: (context) => [
                const PopupMenuItem(
                  value: 'open',
                  child: Row(
                    children: [
                      Icon(Icons.play_circle_outline, color: Colors.white, size: 18),
                      SizedBox(width: 10),
                      Text('Oynat', style: TextStyle(color: Colors.white)),
                    ],
                  ),
                ),
                const PopupMenuItem(
                  value: 'delete',
                  child: Row(
                    children: [
                      Icon(Icons.delete_outline, color: AppTheme.primaryRed, size: 18),
                      SizedBox(width: 10),
                      Text('Sil', style: TextStyle(color: AppTheme.primaryRed)),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  void _openMediaFile(String path) async {
    final file = File(path);
    if (await file.exists()) {
      await OpenFilex.open(path);
    } else {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Dosya cihazda bulunamadı (silinmiş olabilir).'),
            backgroundColor: AppTheme.primaryRed,
          ),
        );
      }
    }
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(
            _activeFilter == 'VIDEO' ? Icons.video_library_outlined : Icons.library_music_outlined,
            size: 64,
            color: AppTheme.textMuted,
          ),
          const SizedBox(height: 16),
          Text(
            _activeFilter == 'VIDEO' ? 'Henüz indirilmiş video yok.' : 'Henüz indirilmiş müzik yok.',
            style: const TextStyle(color: AppTheme.textSecondary, fontSize: 15),
          ),
          const SizedBox(height: 8),
          const Text(
            'Ana sayfadan video arayıp kolayca indirebilirsiniz.',
            style: TextStyle(color: AppTheme.textMuted, fontSize: 12),
          ),
        ],
      ),
    );
  }
}
