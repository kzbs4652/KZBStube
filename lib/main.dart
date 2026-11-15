import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'services/storage_service.dart';
import 'services/notification_service.dart';
import 'services/download_service.dart';
import 'theme/app_theme.dart';
import 'screens/splash_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Android System Navigation and Status Bar Style
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      systemNavigationBarColor: Color(0xFF121316),
      systemNavigationBarIconBrightness: Brightness.light,
    ),
  );

  // Initialize core services
  await StorageService().init();
  await NotificationService().init();
  await DownloadService().init();

  runApp(const KZBStubeApp());
}

class KZBStubeApp extends StatelessWidget {
  const KZBStubeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'KZBStube',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.darkTheme,
      home: const SplashScreen(),
    );
  }
}
