package com.example.android.camera2basic.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.ImageView
import android.widget.RelativeLayout

import com.example.android.camera2basic.R

class FocusView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RelativeLayout(context, attrs) {

    internal lateinit var focus: ImageView

    init {
        init(context)
    }

    private fun init(context: Context) {
        val view = View.inflate(context, R.layout.view_focus, this)
        focus = view.findViewById(R.id.focus)
    }

    fun showFocus(x: Int, y: Int) {

        val width = measuredWidth
        val height = measuredHeight

        val margin = ViewGroup.MarginLayoutParams(layoutParams)
        val newLeftMargin = x - (width / 2f).toInt()
        val newTopMargin = y - (height / 2f).toInt()
        margin.setMargins(newLeftMargin, newTopMargin, 0, 0)
        val layoutParams = RelativeLayout.LayoutParams(margin)
        setLayoutParams(layoutParams)

        visibility = View.VISIBLE
        val scaleAnimation = ScaleAnimation(1.3f, 1.0f, 1.3f, 1.0f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f)
        scaleAnimation.duration = 200
        focus.animation = scaleAnimation
        postDelayed({ visibility = View.GONE }, 1000)
    }
}
