import 'package:flutter/material.dart';

class RedWavePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    // Layer 1: Dark subtle red curve
    final paint1 = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.bottomLeft,
        end: Alignment.topRight,
        colors: [
          Color(0xFF5A0007),
          Color(0xFF88000C),
        ],
      ).createShader(Rect.fromLTWH(0, 0, size.width, size.height))
      ..style = PaintingStyle.fill;

    final path1 = Path();
    path1.moveTo(0, size.height * 0.75);
    path1.quadraticBezierTo(
      size.width * 0.35,
      size.height * 0.45,
      size.width * 0.7,
      size.height * 0.65,
    );
    path1.quadraticBezierTo(
      size.width * 0.88,
      size.height * 0.75,
      size.width,
      size.height * 0.55,
    );
    path1.lineTo(size.width, size.height);
    path1.lineTo(0, size.height);
    path1.close();
    canvas.drawPath(path1, paint1);

    // Layer 2: Vibrant red front wave with smooth glow
    final paint2 = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.bottomCenter,
        end: Alignment.topCenter,
        colors: [
          Color(0xFFBA0A16),
          Color(0xFFE50914),
        ],
      ).createShader(Rect.fromLTWH(0, 0, size.width, size.height))
      ..style = PaintingStyle.fill;

    final path2 = Path();
    path2.moveTo(0, size.height * 0.9);
    path2.quadraticBezierTo(
      size.width * 0.3,
      size.height * 0.8,
      size.width * 0.55,
      size.height * 0.88,
    );
    path2.quadraticBezierTo(
      size.width * 0.8,
      size.height * 0.96,
      size.width,
      size.height * 0.82,
    );
    path2.lineTo(size.width, size.height);
    path2.lineTo(0, size.height);
    path2.close();
    canvas.drawPath(path2, paint2);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
