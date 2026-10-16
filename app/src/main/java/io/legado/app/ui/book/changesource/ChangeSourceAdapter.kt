package io.legado.app.ui.book.changesource

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemChangeSourceBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.widget.PopupAction
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onLongClick

/**
 * 换源列表 adapter（书换源/章换源共用,R1 由两份近似拷贝合一）。
 * 点击语义由 [CallBack.onSelect] 承载:书换源=changeTo(防重入 if 在 Dialog 实现),章换源=openToc(当前源也放行)。
 */
class ChangeSourceAdapter(
    context: Context,
    val callBack: CallBack
) : DiffRecyclerAdapter<SearchBook, ItemChangeSourceBinding>(context) {

    override val diffItemCallback = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.bookUrl == newItem.bookUrl
        }

        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.originName == newItem.originName
                    && oldItem.getDisplayLastChapterTitle() == newItem.getDisplayLastChapterTitle()
                    && oldItem.chapterWordCountText == newItem.chapterWordCountText
                    && oldItem.respondTime == newItem.respondTime
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemChangeSourceBinding {
        return ItemChangeSourceBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChangeSourceBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            if (payloads.isEmpty()) {
                tvOrigin.text = item.originName
                tvAuthor.text = item.author
                tvLast.text = item.getDisplayLastChapterTitle()
                tvCurrentChapterWordCount.text = getChapterInfoText(item)
                tvRespondTime.text = context.getString(R.string.respondTime, item.respondTime)
                bindGroup(binding, item)
                if (callBack.oldBookUrl == item.bookUrl) {
                    ivChecked.visible()
                } else {
                    ivChecked.invisible()
                }
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvOrigin.text = item.originName
                            "latest" -> tvLast.text = item.getDisplayLastChapterTitle()
                            "quality" -> {
                                tvCurrentChapterWordCount.text = getChapterInfoText(item)
                                bindGroup(binding, item)
                            }
                            "upCurSource" -> if (callBack.oldBookUrl == item.bookUrl) {
                                ivChecked.visible()
                            } else {
                                ivChecked.invisible()
                            }
                        }
                    }
                }
            }
            val score = callBack.getBookScore(item)
            if (score > 0) {
                binding.ivBad.gone()
                binding.ivGood.visible()
                tintPraise(binding.ivGood, active = true)
                tintDislike(binding.ivBad, active = false)
            } else if (score < 0) {
                binding.ivGood.gone()
                binding.ivBad.visible()
                tintPraise(binding.ivGood, active = false)
                tintDislike(binding.ivBad, active = true)
            } else {
                binding.ivGood.visible()
                binding.ivBad.visible()
                tintPraise(binding.ivGood, active = false)
                tintDislike(binding.ivBad, active = false)
            }

            val loadContent = AppConfig.changeSourceLoadWordCount || AppConfig.changeSourceCheckQuality
            if (loadContent && !tvCurrentChapterWordCount.text.isNullOrBlank()) {
                tvCurrentChapterWordCount.visible()
            } else {
                tvCurrentChapterWordCount.gone()
            }

            if (loadContent && item.respondTime >= 0) {
                tvRespondTime.visible()
            } else {
                tvRespondTime.gone()
            }
        }
    }

    private fun getChapterInfoText(item: SearchBook): String? {
        val quality = if (AppConfig.changeSourceCheckQuality) callBack.getQualityText(item) else null
        return when {
            quality == null -> item.chapterWordCountText
            item.chapterWordCountText.isNullOrBlank() -> quality
            else -> item.chapterWordCountText + "\n" + quality
        }
    }

    private fun bindGroup(binding: ItemChangeSourceBinding, item: SearchBook) = binding.run {
        val group = if (AppConfig.changeSourceCheckQuality) callBack.getGroupItem(item) else null
        root.setPaddingRelative(if (group != null && !group.isHead) 24.dpToPx() else 0, 0, 0, 0)
        when {
            group == null -> tvGroup.gone()
            group.isHead -> {
                tvGroup.visible()
                tvGroup.isClickable = true
                tvGroup.text = context.getString(
                    if (group.expanded) R.string.same_source_group_expanded
                    else R.string.same_source_group_collapsed,
                    group.size
                )
            }

            else -> {
                tvGroup.visible()
                tvGroup.isClickable = false
                tvGroup.setText(R.string.same_source_group_member)
            }
        }
    }

    /** 质量分与疑似同源分组依赖其他书源的结果，列表内容未变时也需要刷新 */
    fun notifyQualityChanged() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount, Bundle().apply { putBoolean("quality", true) })
        }
    }

    /** 赞踩语义色集中染色:active=A200 饱和,否则 100 淡 */
    private fun tintPraise(iv: ImageView, active: Boolean) {
        DrawableCompat.setTint(
            iv.drawable,
            appCtx.getCompatColor(if (active) R.color.praise_active else R.color.praise_inactive)
        )
    }

    private fun tintDislike(iv: ImageView, active: Boolean) {
        DrawableCompat.setTint(
            iv.drawable,
            appCtx.getCompatColor(if (active) R.color.dislike_active else R.color.dislike_inactive)
        )
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChangeSourceBinding) {
        binding.ivGood.setOnClickListener {
            if (binding.ivBad.isVisible) {
                tintPraise(binding.ivGood, active = true)
                binding.ivBad.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 1)
                }
            } else {
                tintPraise(binding.ivGood, active = false)
                binding.ivBad.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        binding.ivBad.setOnClickListener {
            if (binding.ivGood.isVisible) {
                tintDislike(binding.ivBad, active = true)
                binding.ivGood.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, -1)
                }
            } else {
                tintDislike(binding.ivBad, active = false)
                binding.ivGood.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        binding.tvGroup.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (callBack.getGroupItem(it)?.isHead == true) {
                    callBack.toggleGroup(it)
                }
            }
        }
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.onSelect(it)
            }
        }
        holder.itemView.onLongClick {
            showMenu(holder.itemView, getItem(holder.layoutPosition))
        }
    }

    private fun showMenu(view: View, searchBook: SearchBook?) {
        searchBook ?: return
        PopupAction(context).apply {
            setVertical(true)
            setDangerValues(setOf("disableSource", "deleteSource"))
            setItems(
                listOf(
                    SelectItem(context.getString(R.string.to_top), "topSource"),
                    SelectItem(context.getString(R.string.to_bottom), "bottomSource"),
                    SelectItem(context.getString(R.string.edit_source), "editSource"),
                    SelectItem(context.getString(R.string.disable_source), "disableSource"),
                    SelectItem(context.getString(R.string.delete_source), "deleteSource")
                )
            )
            onActionClick = { action ->
                when (action) {
                    "topSource" -> callBack.topSource(searchBook)
                    "bottomSource" -> callBack.bottomSource(searchBook)
                    "editSource" -> callBack.editSource(searchBook)
                    "disableSource" -> callBack.disableSource(searchBook)
                    "deleteSource" -> context.alert(R.string.draw) {
                        setMessage(context.getString(R.string.sure_del) + "\n" + searchBook.originName)
                        noButton()
                        yesButton {
                            callBack.deleteSource(searchBook)
                            updateItems(0, itemCount, listOf<Int>())
                        }
                    }
                }
                dismiss()
            }
            showAsDropDown(view, 0, 4.dpToPx())
        }
    }

    interface CallBack {
        val oldBookUrl: String?

        /** item 点击:书换源=changeTo(实现方自行防重入当前源),章换源=openToc(当前源放行) */
        fun onSelect(searchBook: SearchBook)
        fun topSource(searchBook: SearchBook)
        fun bottomSource(searchBook: SearchBook)
        fun editSource(searchBook: SearchBook)
        fun disableSource(searchBook: SearchBook)
        fun deleteSource(searchBook: SearchBook)
        fun setBookScore(searchBook: SearchBook, score: Int)
        fun getBookScore(searchBook: SearchBook): Int
        fun getQualityText(searchBook: SearchBook): String?
        fun getGroupItem(searchBook: SearchBook): SourceGroupItem?
        fun toggleGroup(searchBook: SearchBook)
    }
}
