package com.example.posetrack

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val splashDuration = 2500L // 2.5 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Hide action bar
        supportActionBar?.hide()

        // Get views
        val appIcon = findViewById<ImageView>(R.id.appIcon)
        val appName = findViewById<TextView>(R.id.appName)
        val appTagline = findViewById<TextView>(R.id.appTagline)

        // Animate entrance
        animateViews(appIcon, appName, appTagline)

        // Navigate to main activity after delay
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish()
        }, splashDuration)
    }

    private fun animateViews(icon: ImageView, name: TextView, tagline: TextView) {
        // Start invisible
        icon.alpha = 0f
        name.alpha = 0f
        tagline.alpha = 0f

        // Icon scale and fade
        icon.scaleX = 0.5f
        icon.scaleY = 0.5f

        // Icon animation set
        val iconAnimSet = AnimationSet(true).apply {
            interpolator = AccelerateDecelerateInterpolator()

            // Scale animation
            val scaleAnim = ScaleAnimation(
                0.5f, 1.1f, // From scale to scale X
                0.5f, 1.1f, // From scale to scale Y
                Animation.RELATIVE_TO_SELF, 0.5f, // Pivot X
                Animation.RELATIVE_TO_SELF, 0.5f  // Pivot Y
            ).apply {
                duration = 800
                startOffset = 200
            }

            // Fade animation
            val fadeAnim = AlphaAnimation(0f, 1f).apply {
                duration = 800
                startOffset = 200
            }

            addAnimation(scaleAnim)
            addAnimation(fadeAnim)

            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    icon.visibility = View.VISIBLE
                }

                override fun onAnimationEnd(animation: Animation?) {
                    icon.scaleX = 1f
                    icon.scaleY = 1f
                    icon.alpha = 1f
                }

                override fun onAnimationRepeat(animation: Animation?) {}
            })
        }

        // Name animation
        val nameAnim = AlphaAnimation(0f, 1f).apply {
            duration = 600
            startOffset = 600
            interpolator = AccelerateDecelerateInterpolator()

            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    name.visibility = View.VISIBLE
                }

                override fun onAnimationEnd(animation: Animation?) {
                    name.alpha = 1f
                }

                override fun onAnimationRepeat(animation: Animation?) {}
            })
        }

        // Tagline animation
        val taglineAnim = AlphaAnimation(0f, 1f).apply {
            duration = 600
            startOffset = 900
            interpolator = AccelerateDecelerateInterpolator()

            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {
                    tagline.visibility = View.VISIBLE
                }

                override fun onAnimationEnd(animation: Animation?) {
                    tagline.alpha = 1f
                }

                override fun onAnimationRepeat(animation: Animation?) {}
            })
        }

        // Start animations
        icon.startAnimation(iconAnimSet)
        name.startAnimation(nameAnim)
        tagline.startAnimation(taglineAnim)
    }
}