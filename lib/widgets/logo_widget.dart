import 'package:flutter/material.dart';
import '../theme/app_theme.dart';

class LogoWidget extends StatelessWidget {
  final double iconSize;
  final double fontSize;
  final bool showSubtitle;
  final bool isAnimated;

  const LogoWidget({
    super.key,
    this.iconSize = 72,
    this.fontSize = 32,
    this.showSubtitle = true,
    this.isAnimated = false,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        // YouTube styled badge with download arrow
        Container(
          width: iconSize * 1.3,
          height: iconSize,
          decoration: BoxDecoration(
            color: AppTheme.primaryRed,
            borderRadius: BorderRadius.circular(iconSize * 0.28),
            boxShadow: [
              BoxShadow(
                color: AppTheme.primaryRed.withOpacity(0.4),
                blurRadius: 20,
                spreadRadius: 2,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: Stack(
            alignment: Alignment.center,
            children: [
              // Play triangle
              Padding(
                padding: EdgeInsets.only(right: iconSize * 0.12),
                child: CustomPaint(
                  size: Size(iconSize * 0.45, iconSize * 0.45),
                  painter: _PlayTrianglePainter(color: Colors.white),
                ),
              ),
              // Download circle arrow badge overlay
              Positioned(
                bottom: iconSize * 0.08,
                right: iconSize * 0.14,
                child: Container(
                  width: iconSize * 0.44,
                  height: iconSize * 0.44,
                  decoration: const BoxDecoration(
                    color: Colors.white,
                    shape: BoxShape.circle,
                  ),
                  child: Center(
                    child: Icon(
                      Icons.arrow_downward_rounded,
                      size: iconSize * 0.3,
                      color: AppTheme.primaryRed,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        // Title KZBStube
        RichText(
          text: TextSpan(
            style: TextStyle(
              fontSize: fontSize,
              fontWeight: FontWeight.w900,
              letterSpacing: 0.5,
              color: Colors.white,
            ),
            children: const [
              TextSpan(text: 'KZBS'),
              TextSpan(
                text: 'tube',
                style: TextStyle(
                  color: AppTheme.primaryRed,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ],
          ),
        ),
        if (showSubtitle) ...[
          const SizedBox(height: 8),
          const Text(
            'YouTube Video ve Müzik İndirici',
            style: TextStyle(
              fontSize: 14,
              color: AppTheme.textSecondary,
              fontWeight: FontWeight.w400,
            ),
          ),
        ],
      ],
    );
  }
}

class _PlayTrianglePainter extends CustomPainter {
  final Color color;
  _PlayTrianglePainter({required this.color});

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.fill;

    final path = Path();
    path.moveTo(size.width * 0.25, size.height * 0.15);
    path.lineTo(size.width * 0.85, size.height * 0.5);
    path.lineTo(size.width * 0.25, size.height * 0.85);
    path.close();

    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
