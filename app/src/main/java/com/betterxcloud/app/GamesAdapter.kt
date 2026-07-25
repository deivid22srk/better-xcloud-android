package com.betterxcloud.app

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.betterxcloud.app.databinding.ItemGameBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

data class GameItem(
    val id: String,
    val title: String,
    val imageUrl: String,
    val publisher: String,
)

class GamesAdapter(
    private val onClick: (GameItem) -> Unit
) : ListAdapter<GameItem, GamesAdapter.GameViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GameItem>() {
            override fun areItemsTheSame(a: GameItem, b: GameItem) = a.id == b.id
            override fun areContentsTheSame(a: GameItem, b: GameItem) = a == b
        }
    }

    inner class GameViewHolder(val binding: ItemGameBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = ItemGameBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GameViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = getItem(position)
        with(holder.binding) {
            tvTitle.text = game.title
            tvPublisher.text = game.publisher

            root.transitionName = "game_${game.id}"

            Glide.with(ivCover)
                .load(game.imageUrl.takeIf { it.isNotBlank() })
                .placeholder(R.drawable.placeholder_game)
                .error(R.drawable.placeholder_game)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(ivCover)

            root.setOnClickListener { onClick(game) }
        }
    }

    /** Convenience for the activity that works with [XcloudGame] directly. */
    fun submitGames(games: List<XcloudGame>) {
        submitList(games.map { GameItem(it.id, it.title, it.imageUrl, it.publisherName) })
    }
}

/**
 * Submits XcloudGame list to the adapter (adapter itself uses GameItem to keep
 * the diff layer decoupled from the bridge's data class).
 */
fun GamesAdapter.submitXcloudGames(games: List<XcloudGame>) = submitGames(games)

/** Grid spacing item decoration. */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val includeEdge: Boolean
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount
        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount
            if (position < spanCount) outRect.top = spacing
            outRect.bottom = spacing
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) outRect.top = spacing
        }
    }
}
