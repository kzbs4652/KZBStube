import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class NotificationService {
  static final NotificationService _instance = NotificationService._internal();
  factory NotificationService() => _instance;
  NotificationService._internal();

  final FlutterLocalNotificationsPlugin _notifications = FlutterLocalNotificationsPlugin();
  bool _initialized = false;

  Future<void> init() async {
    if (_initialized) return;

    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');
    const initSettings = InitializationSettings(android: androidSettings);

    await _notifications.initialize(
      initSettings,
      onDidReceiveNotificationResponse: (response) {
        // Handle notification click
      },
    );

    _initialized = true;
  }

  Future<void> showDownloadProgress({
    required int id,
    required String title,
    required int progress,
    required String speed,
  }) async {
    final androidDetails = AndroidNotificationDetails(
      'kzbstube_downloads',
      'İndirmeler',
      channelDescription: 'KZBStube dosya indirme bildirimleri',
      importance: Importance.low,
      priority: Priority.low,
      onlyAlertOnce: true,
      showProgress: true,
      maxProgress: 100,
      progress: progress,
      subText: speed,
    );

    final details = NotificationDetails(android: androidDetails);
    await _notifications.show(
      id,
      title,
      'İndiriliyor: %$progress ($speed)',
      details,
    );
  }

  Future<void> showDownloadComplete({
    required int id,
    required String title,
    required String format,
  }) async {
    final androidDetails = const AndroidNotificationDetails(
      'kzbstube_completed',
      'Tamamlanan İndirmeler',
      channelDescription: 'İndirme tamamlandığında bildirim verir',
      importance: Importance.high,
      priority: Priority.high,
    );

    final details = NotificationDetails(android: androidDetails);
    await _notifications.show(
      id,
      'İndirme Tamamlandı! 🎉',
      '$title ($format) başarıyla indirildi.',
      details,
    );
  }

  Future<void> cancelNotification(int id) async {
    await _notifications.cancel(id);
  }
}
