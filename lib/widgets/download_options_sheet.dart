import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import '../models/video_item.dart';
import '../services/youtube_service.dart';
import '../services/download_service.dart';
import '../theme/app_theme.dart';
import '../screens/active_downloads_screen.dart';

class DownloadOptionsSheet extends StatefulWidget {
  final VideoItem video;

  const DownloadOptionsSheet({super.key, required this.video});

  @override
  State<DownloadOptionsSheet> createState() => _DownloadOptionsSheetState();
}

class _DownloadOptionsSheetState extends State<DownloadOptionsSheet> {
  final YouTubeService _ytService = YouTubeService();
  final DownloadService _downloadService = DownloadService();

  bool _isLoading = true;
  VideoDetailsResult? _details;
  String _selectedFormat = 'MP4'; // 'MP4' or 'MP3'
  int _selectedQualityIndex = 1; // Default: 720p HD

  @override
  void initState() {
    super.initState();
    _loadOptions();
  }

  void _loadOptions() async {
    final res = await _ytService.getVideoDetailsWithOptions(widget.video);
    if (mounted) {
      setState(() {
        _details = res;
        _isLoading = false;
        // Default select 720p (index 1) if available
        if (_details != null && _details!.videoOptions.length > 1) {
          _selectedQualityIndex = 1;
        } else {
          _selectedQualityIndex = 0;
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppTheme.background,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Top Bar: Back arrow and title
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.arrow_back, color: Colors.white),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  const SizedBox(width: 8),
                  const Text(
                    'İndirme Seçenekleri',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                ],
              ),
            ),
            const Divider(color: AppTheme.divider, height: 1),

            Expanded(
              child: _isLoading
                  ? const Center(
                      child: CircularProgressIndicator(color: AppTheme.primaryRed),
                    )
                  : SingleChildScrollView(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          // Video Preview Card
                          Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              ClipRRect(
                                borderRadius: BorderRadius.circular(8),
                                child: Stack(
                                  children: [
                                    CachedNetworkImage(
                                      imageUrl: widget.video.thumbnailUrl,
                                      width: 120,
                                      height: 70,
                                      fit: BoxFit.cover,
                                      placeholder: (_, __) => Container(
                                        color: AppTheme.surface,
                                        width: 120,
                                        height: 70,
                                      ),
                                      errorWidget: (_, __, ___) => Container(
                                        color: AppTheme.surface,
                                        width: 120,
                                        height: 70,
                                        child: const Icon(Icons.videocam_off, color: Colors.grey),
                                      ),
                                    ),
                                    Positioned(
                                      bottom: 4,
                                      right: 4,
                                      child: Container(
                                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                                        decoration: BoxDecoration(
                                          color: Colors.black.withOpacity(0.8),
                                          borderRadius: BorderRadius.circular(4),
                                        ),
                                        child: Text(
                                          widget.video.duration,
                                          style: const TextStyle(fontSize: 10, color: Colors.white, fontWeight: FontWeight.bold),
                                        ),
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
                                      widget.video.title,
                                      maxLines: 2,
                                      overflow: TextOverflow.ellipsis,
                                      style: const TextStyle(
                                        fontSize: 14,
                                        fontWeight: FontWeight.bold,
                                        color: Colors.white,
                                      ),
                                    ),
                                    const SizedBox(height: 4),
                                    Text(
                                      widget.video.author,
                                      style: const TextStyle(fontSize: 12, color: AppTheme.textSecondary),
                                    ),
                                    Text(
                                      '${widget.video.viewCount} • ${widget.video.uploadDate}',
                                      style: const TextStyle(fontSize: 11, color: AppTheme.textMuted),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 20),

                          // Format Seçin
                          const Text(
                            'Format Seçin',
                            style: TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: _formatButton(
                                  title: 'Video (MP4)',
                                  icon: Icons.video_file_outlined,
                                  isSelected: _selectedFormat == 'MP4',
                                  onTap: () {
                                    setState(() {
                                      _selectedFormat = 'MP4';
                                      _selectedQualityIndex = 1;
                                    });
                                  },
                                ),
                              ),
                              const SizedBox(width: 12),
                              Expanded(
                                child: _formatButton(
                                  title: 'Ses (MP3)',
                                  icon: Icons.music_note_outlined,
                                  isSelected: _selectedFormat == 'MP3',
                                  onTap: () {
                                    setState(() {
                                      _selectedFormat = 'MP3';
                                      _selectedQualityIndex = 0;
                                    });
                                  },
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 24),

                          // Quality Selection
                          Text(
                            _selectedFormat == 'MP4' ? 'Video Kalitesi' : 'Ses Kalitesi',
                            style: const TextStyle(
                              fontSize: 15,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                          const SizedBox(height: 12),

                          if (_selectedFormat == 'MP4' && _details != null)
                            ...List.generate(_details!.videoOptions.length, (index) {
                              final opt = _details!.videoOptions[index];
                              final isSelected = _selectedQualityIndex == index;
                              return _qualityRadioItem(
                                label: opt.label,
                                sizeFormatted: opt.estimatedSizeFormatted,
                                isSelected: isSelected,
                                onTap: () => setState(() => _selectedQualityIndex = index),
                              );
                            }),

                          if (_selectedFormat == 'MP3' && _details != null)
                            ...List.generate(_details!.audioOptions.length, (index) {
                              final opt = _details!.audioOptions[index];
                              final isSelected = _selectedQualityIndex == index;
                              return _qualityRadioItem(
                                label: opt.label,
                                sizeFormatted: opt.estimatedSizeFormatted,
                                isSelected: isSelected,
                                onTap: () => setState(() => _selectedQualityIndex = index),
                              );
                            }),
                        ],
                      ),
                    ),
            ),

            // Start Download Button
            Padding(
              padding: const EdgeInsets.all(16),
              child: SizedBox(
                width: double.infinity,
                height: 52,
                child: ElevatedButton.icon(
                  onPressed: _startDownloadAction,
                  icon: const Icon(Icons.arrow_downward_rounded, color: Colors.white),
                  label: const Text(
                    'İndirmeyi Başlat',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppTheme.primaryRed,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                    elevation: 4,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _formatButton({
    required String title,
    required IconData icon,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 12),
        decoration: BoxDecoration(
          color: isSelected ? AppTheme.primaryRed : AppTheme.card,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: isSelected ? AppTheme.primaryRed : AppTheme.border,
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: Colors.white, size: 20),
            const SizedBox(width: 8),
            Text(
              title,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.bold,
                fontSize: 14,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _qualityRadioItem({
    required String label,
    required String sizeFormatted,
    required bool isSelected,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 4),
        child: Row(
          children: [
            Icon(
              isSelected ? Icons.radio_button_checked : Icons.radio_button_off,
              color: isSelected ? AppTheme.primaryRed : Colors.grey.shade600,
              size: 22,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                label,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.w500,
                  color: isSelected ? Colors.white : AppTheme.textSecondary,
                ),
              ),
            ),
            Text(
              sizeFormatted,
              style: TextStyle(
                fontSize: 14,
                color: isSelected ? Colors.white : AppTheme.textMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _startDownloadAction() async {
    if (_details == null) return;

    String qualityLabel = '';
    int sizeBytes = 0;

    if (_selectedFormat == 'MP4') {
      final opt = _details!.videoOptions[_selectedQualityIndex];
      qualityLabel = opt.resolution;
      sizeBytes = opt.estimatedSizeBytes;
    } else {
      final opt = _details!.audioOptions[_selectedQualityIndex];
      qualityLabel = opt.bitrate;
      sizeBytes = opt.estimatedSizeBytes;
    }

    final task = await _downloadService.startDownload(
      video: widget.video,
      format: _selectedFormat,
      quality: qualityLabel,
      estimatedSizeBytes: sizeBytes,
    );

    if (mounted) {
      Navigator.of(context).pop();
      // Navigate to active downloads screen
      Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ActiveDownloadsScreen(initialTask: task),
        ),
      );
    }
  }
}
