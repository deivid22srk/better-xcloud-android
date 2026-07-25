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

/**
 * UI-friendly representation of a game card.
 *
 * Carries the [Ownership] classification so the adapter can color the badge
 * chip differently for purchased vs Game Pass vs both. Also carries the
 * [cloudId] used to launch streaming via the modern play.xbox.com deep link.
 */
data class GameItem(
    val id: String,
    val title: String,
    val imageUrl: String,
    val publisher: String,
    val ownership: Ownership = Ownership.GAME_PASS,
    val cloudEnabled: Boolean = true,
    val cloudId: String = "",
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

            // ─── Ownership chip ─────────────────────────────────────────────
            // Hidden when GAME_PASS (the "default" — most cards), shown for
            // PURCHASED, BOTH, or when cloud is disabled (warning badge).
            applyOwnershipChip(chipOwnership, game)

            root.setOnClickListener { onClick(game) }
        }
    }

    /** Convenience for the activity that works with [XcloudGame] directly. */
    fun submitGames(games: List<XcloudGame>) {
        submitList(games.map {
            GameItem(
                id = it.id,
                title = it.title,
                imageUrl = it.imageUrl,
                publisher = it.publisherName,
                ownership = it.ownership,
                cloudEnabled = it.cloudEnabled,
                cloudId = it.cloudId,
            )
        })
    }
}

/**
 * Submits XcloudGame list to the adapter (adapter itself uses GameItem to keep
 * the diff layer decoupled from the bridge's data class).
 */
fun GamesAdapter.submitXcloudGames(games: List<XcloudGame>) = submitGames(games)

/**
 * Applies the correct text + Material 3 color to the ownership chip.
 *
 * Color mapping (Material 3 container roles):
 *   GAME_PASS   → secondaryContainer   (subtle, since it's the default state)
 *   PURCHASED   → primaryContainer     (highlight — the user paid for this)
 *   BOTH        → tertiaryContainer    (combination of both)
 *   NOT_OWNED   → hidden
 *
 * Plus, if cloudEnabled is false, override the chip with an "Sem cloud"
 * warning using errorContainer.
 */
private fun applyOwnershipChip(
    chip: com.google.android.material.chip.Chip,
    game: GameItem,
) {
    val ctx = chip.context
    if (!game.cloudEnabled) {
        chip.visibility = View.VISIBLE
        chip.text = ctx.getString(R.string.chip_no_cloud)
        chip.setChipBackgroundColorResource(R.color.md_theme_errorContainer)
        chip.setTextColor(ctx.getColor(R.color.md_theme_onErrorContainer))
        return
    }
    when (game.ownership) {
        Ownership.PURCHASED -> {
            chip.visibility = View.VISIBLE
            chip.text = ctx.getString(R.string.chip_purchased)
            chip.setChipBackgroundColorResource(R.color.md_theme_primaryContainer)
            chip.setTextColor(ctx.getColor(R.color.md_theme_onPrimaryContainer))
        }
        Ownership.BOTH -> {
            chip.visibility = View.VISIBLE
            chip.text = ctx.getString(R.string.chip_both)
            chip.setChipBackgroundColorResource(R.color.md_theme_tertiaryContainer)
            chip.setTextColor(ctx.getColor(R.color.md_theme_onTertiaryContainer))
        }
        Ownership.GAME_PASS -> {
            // Default state — chip hidden to keep the grid visually clean.
            // Most games in the library are Game Pass, so showing the chip on
            // every card would just add visual noise.
            chip.visibility = View.GONE
        }
        Ownership.NOT_OWNED -> {
            chip.visibility = View.GONE
        }
    }
}

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
