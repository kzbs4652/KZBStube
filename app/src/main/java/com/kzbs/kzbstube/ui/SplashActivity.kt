package com.kzbs.kzbstube.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.appcompat.app.AppCompatActivity
import com.kzbs.kzbstube.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Opening animation matching user request
        val animSet = AnimationSet(true)
        val scaleAnim = ScaleAnimation(
            0.6f, 1.0f, 0.6f, 1.0f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
            ScaleAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply { duration = 1200 }

        val alphaAnim = AlphaAnimation(0.0f, 1.0f).apply { duration = 1000 }

        animSet.addAnimation(scaleAnim)
        animSet.addAnimation(alphaAnim)
        binding.logoContainer.startAnimation(animSet)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 1800)
    }
}
