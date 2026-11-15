class VideoItem {
  final String id;
  final String title;
  final String author;
  final String duration;
  final int durationSeconds;
  final String viewCount;
  final String uploadDate;
  final String thumbnailUrl;
  final String url;

  VideoItem({
    required this.id,
    required this.title,
    required this.author,
    required this.duration,
    required this.durationSeconds,
    required this.viewCount,
    required this.uploadDate,
    required this.thumbnailUrl,
    required this.url,
  });

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'title': title,
      'author': author,
      'duration': duration,
      'durationSeconds': durationSeconds,
      'viewCount': viewCount,
      'uploadDate': uploadDate,
      'thumbnailUrl': thumbnailUrl,
      'url': url,
    };
  }

  factory VideoItem.fromJson(Map<String, dynamic> json) {
    return VideoItem(
      id: json['id'] ?? '',
      title: json['title'] ?? '',
      author: json['author'] ?? '',
      duration: json['duration'] ?? '',
      durationSeconds: json['durationSeconds'] ?? 0,
      viewCount: json['viewCount'] ?? '',
      uploadDate: json['uploadDate'] ?? '',
      thumbnailUrl: json['thumbnailUrl'] ?? '',
      url: json['url'] ?? '',
    );
  }
}
