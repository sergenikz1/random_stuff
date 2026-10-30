package org.scryertv.client.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import org.scryertv.client.R

/**
 * Карточка в полке или сетке: картинка (постер 2:3 или кадр 16:9), плашка в углу,
 * полоса прогресса загрузки, название в две строки и подпись.
 */
class MediaCardView(
    context: Context,
    widthRes: Int = R.dimen.poster_width,
    heightRes: Int = R.dimen.poster_height
) : LinearLayout(context) {

    private val poster: FrameLayout
    private val image: ImageView
    private val badge: TextView
    private val progress: ProgressBar
    private val title: TextView
    private val subtitle: TextView

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.card_background)
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        LayoutInflater.from(context).inflate(R.layout.view_media_card, this, true)

        poster = findViewById(R.id.card_poster)
        image = findViewById(R.id.card_image)
        badge = findViewById(R.id.card_badge)
        progress = findViewById(R.id.card_progress)
        title = findViewById(R.id.card_title)
        subtitle = findViewById(R.id.card_subtitle)

        val width = resources.getDimensionPixelSize(widthRes)
        val height = resources.getDimensionPixelSize(heightRes)
        poster.layoutParams = LayoutParams(width, height)
        layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * @param progressPercent прогресс от 0 до 100; null — полоса не показывается.
     * @param badgeStyle фон плашки: [R.drawable.badge_pill] и его варианты.
     */
    fun bind(
        titleText: String,
        subtitleText: String,
        imageUrl: String?,
        badgeText: String? = null,
        badgeStyle: Int = R.drawable.badge_pill,
        progressPercent: Int? = null
    ) {
        title.text = titleText
        subtitle.text = subtitleText

        if (badgeText.isNullOrBlank()) {
            badge.visibility = View.GONE
        } else {
            badge.text = badgeText
            badge.setBackgroundResource(badgeStyle)
            badge.visibility = View.VISIBLE
        }

        if (progressPercent != null) {
            progress.progress = progressPercent.coerceIn(0, 100)
            progress.visibility = View.VISIBLE
        } else {
            progress.visibility = View.GONE
        }

        Glide.with(context)
            .load(imageUrl)
            .centerCrop()
            .placeholder(R.drawable.card_placeholder)
            .error(R.drawable.card_placeholder)
            .into(image)
    }

    /** Карточка-действие: иконка вместо картинки. */
    fun bindAction(actionTitle: String, subtitleText: String, iconRes: Int) {
        title.text = actionTitle
        subtitle.text = subtitleText
        badge.visibility = View.GONE
        progress.visibility = View.GONE
        Glide.with(context).clear(image)
        image.setImageResource(iconRes)
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        image.setBackgroundColor(ContextCompat.getColor(context, R.color.background_dark))
    }

    fun recycle() {
        Glide.with(context).clear(image)
        image.setImageResource(R.drawable.card_placeholder)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.background = null
        badge.visibility = View.GONE
        progress.visibility = View.GONE
    }
}
