package org.intergalactictv.client.streaming

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.PresenterSelector

internal object SearchLoadingItem

internal class SearchResultPresenters : PresenterSelector() {
    private val video = StreamCardPresenter()
    private val loading = object : Presenter() {
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = ViewGroup.LayoutParams(320, 220)
                isFocusable = false
                addView(ProgressBar(context), LinearLayout.LayoutParams(56, 56))
                addView(TextView(context).apply {
                    text = "Ищу видео на сайтах…"; textSize = 18f; setTextColor(-1)
                    setPadding(8, 20, 8, 8)
                })
                contentDescription = "Ищу видео на сайтах"
            }
            return ViewHolder(root)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) = Unit
        override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
    }
    override fun getPresenter(item: Any?): Presenter = if (item === SearchLoadingItem) loading else video
    override fun getPresenters(): Array<Presenter> = arrayOf(video, loading)
}
