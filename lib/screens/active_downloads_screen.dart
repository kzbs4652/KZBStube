import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../models/download_task.dart';
import '../services/download_service.dart';
import '../theme/app_theme.dart';

class ActiveDownloadsScreen extends StatefulWidget {
  final DownloadTask? initialTask;

  const ActiveDownloadsScreen({super.key, this.initialTask});

  @override
  State<ActiveDownloadsScreen> createState() => _ActiveDownloadsScreenState();
}

class _ActiveDownloadsScreenState extends State<ActiveDownloadsScreen> {
  final DownloadService _downloadService = DownloadService();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.background,
      appBar: AppBar(
        backgroundColor: AppTheme.background,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.white),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text(
          'İndiriliyor...',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold, color: Colors.white),
        ),
      ),
      body: AnimatedBuilder(
        animation: _downloadService,
        builder: (context, _) {
          final tasks = _downloadService.activeTasks;

          if (tasks.isEmpty) {
            return Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.check_circle_outline, color: AppTheme.success, size: 56),
                  const SizedBox(height: 14),
                  const Text(
                    'Aktif indirme bulunmuyor.',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Tamamlanan dosyaları "İndirilenler" sekmesinde görebilirsiniz.',
                    style: TextStyle(fontSize: 13, color: AppTheme.textSecondary),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 20),
                  ElevatedButton(
                    onPressed: () => Navigator.of(context).pop(),
                    style: ElevatedButton.styleFrom(backgroundColor: AppTheme.primaryRed),
                    child: const Text('Geri Dön'),
                  ),
                ],
              ),
            );
          }

          return ListView.separated(
            padding: const EdgeInsets.all(16),
            itemCount: tasks.length,
            separatorBuilder: (_, __) => const SizedBox(height: 16),
            itemBuilder: (context, index) {
              final task = tasks[index];
              return _buildActiveDownloadCard(task);
            },
          );
        },
      ),
    );
  }

  Widget _buildActiveDownloadCard(DownloadTask task) {
    final isPaused = task.status == DownloadStatus.paused;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppTheme.card,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppTheme.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Top Row: Thumbnail + Title + Format Badge
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: Stack(
                  children: [
                    CachedNetworkImage(
                      imageUrl: task.thumbnailUrl,
                      width: 100,
                      height: 58,
                      fit: BoxFit.cover,
                      placeholder: (_, __) => Container(
                        color: AppTheme.surface,
                        width: 100,
                        height: 58,
                      ),
                      errorWidget: (_, __, ___) => Container(
                        color: AppTheme.surface,
                        width: 100,
                        height: 58,
                        child: const Icon(Icons.movie, color: Colors.grey),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      task.title,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                        height: 1.25,
                      ),
                    ),
                    const SizedBox(height: 6),
                    // Format badge (e.g. MP4 • 720p)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                      decoration: BoxDecoration(
                        color: AppTheme.surface,
                        borderRadius: BorderRadius.circular(6),
                      ),
                      child: Text(
                        '${task.format} • ${task.quality}',
                        style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.bold,
                          color: AppTheme.textSecondary,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),

          const SizedBox(height: 18),

          // Progress Bar and Percent matching Screenshot 4
          Row(
            children: [
              Expanded(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(6),
                  child: LinearProgressIndicator(
                    value: task.progress > 0 ? task.progress : null,
                    minHeight: 8,
                    backgroundColor: const Color(0xFF2E313A),
                    valueColor: const AlwaysStoppedAnimation<Color>(AppTheme.primaryRed),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Text(
                '%${task.progressPercent}',
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
              ),
            ],
          ),

          const SizedBox(height: 10),

          // Download size and speed metrics (e.g. 47,6 MB / 82 MB • 6,4 MB/s)
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                '${task.sizeFormatted} • ${isPaused ? "Duraklatıldı" : task.speedFormatted}',
                style: const TextStyle(
                  fontSize: 12,
                  color: AppTheme.textSecondary,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),

          const SizedBox(height: 16),

          // Action Buttons: Duraklat / İptal matching Screenshot 4
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () {
                    if (isPaused) {
                      _downloadService.resumeDownload(task.id);
                    } else {
                      _downloadService.pauseDownload(task.id);
                    }
                  },
                  icon: Icon(
                    isPaused ? Icons.play_arrow_rounded : Icons.pause_rounded,
                    color: Colors.white,
                    size: 18,
                  ),
                  label: Text(
                    isPaused ? 'Devam Et' : 'Duraklat',
                    style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
                  ),
                  style: OutlinedButton.styleFrom(
                    side: const BorderSide(color: AppTheme.border),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () => _downloadService.cancelDownload(task.id),
                  icon: const Icon(Icons.close_rounded, color: Colors.white, size: 18),
                  label: const Text(
                    'İptal',
                    style: TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
                  ),
                  style: OutlinedButton.styleFrom(
                    side: const BorderSide(color: AppTheme.border),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    padding: const EdgeInsets.symmetric(vertical: 12),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
