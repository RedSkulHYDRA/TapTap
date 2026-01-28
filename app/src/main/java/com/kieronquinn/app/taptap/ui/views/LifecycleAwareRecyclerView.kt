package com.kieronquinn.app.taptap.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 *  RecyclerView which handles the lifecycle of [ViewHolder]s which are [LifecycleOwner]s,
 *  calling [Lifecycle.Event.ON_RESUME] when bound, [Lifecycle.Event.ON_PAUSE] when recycled,
 *  and [Lifecycle.Event.ON_STOP] and [Lifecycle.Event.ON_DESTROY] when detached from the
 *  RecyclerView. [ViewHolder]s should start with [Lifecycle.Event.ON_CREATE], [Lifecycle.Event.ON_START]
 *  and [Lifecycle.Event.ON_STOP]
 *
 *  **Note: Only supports [LinearLayoutManager], and assumes that the [Adapter] will be un-set when
 *  the fragment is destroyed**
 */
class LifecycleAwareRecyclerView : RecyclerView {

    constructor(context: Context, attributeSet: AttributeSet? = null, defStyleRes: Int):
            super(context, attributeSet, defStyleRes)
    constructor(context: Context, attributeSet: AttributeSet?):
            this(context, attributeSet, 0)
    constructor(context: Context):
            this(context, null, 0)

    abstract class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView), LifecycleOwner {

        private val lifecycleRegistry by lazy { LifecycleRegistry(this@ViewHolder) }
        private var isDestroyed = false

        init {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        override val lifecycle
            get() = lifecycleRegistry

        internal fun handleLifecycleEvent(event: Lifecycle.Event) {
            // Don't process events if already destroyed
            if (isDestroyed && event != Lifecycle.Event.ON_DESTROY) {
                return
            }

            // Track destruction state
            if (event == Lifecycle.Event.ON_DESTROY) {
                isDestroyed = true
            }

            try {
                lifecycleRegistry.handleLifecycleEvent(event)
            } catch (e: IllegalStateException) {
                // Suppress lifecycle state transition errors that can occur during RecyclerView
                // recycling edge cases (e.g., when ViewHolder is destroyed but RecyclerView
                // tries to recycle it again)
            }
        }

        internal fun isDestroyed(): Boolean = isDestroyed

    }

    abstract class Adapter<VH: ViewHolder>(private val recyclerView: RecyclerView): RecyclerView.Adapter<VH>() {

        private val layoutManager
            get() = recyclerView.layoutManager as? LinearLayoutManager

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            super.onBindViewHolder(holder, position, payloads)
            // Only resume if not destroyed
            if (!holder.isDestroyed()) {
                holder.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            // Only pause if not destroyed
            if (!holder.isDestroyed()) {
                holder.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            // Properly destroy all visible ViewHolders in correct lifecycle order
            getCreatedViewHolders().forEach {
                if (!it.isDestroyed()) {
                    it.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    it.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    it.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                }
            }
        }

        private fun getCreatedViewHolders(): List<ViewHolder> {
            val manager = layoutManager ?: return emptyList()
            val firstItem = manager.findFirstVisibleItemPosition()
            val lastItem = manager.findLastVisibleItemPosition()

            if (firstItem == RecyclerView.NO_POSITION || lastItem == RecyclerView.NO_POSITION) {
                return emptyList()
            }

            val viewHolders = ArrayList<ViewHolder>()
            for(i in firstItem..lastItem){
                if(!recyclerView.isAttachedToWindow) continue
                try {
                    val child = recyclerView.getChildAt(i - firstItem) ?: continue
                    val holder = recyclerView.getChildViewHolder(child) as? ViewHolder
                    if (holder != null) {
                        viewHolders.add(holder)
                    }
                }catch (e: Exception){
                    // Not attached or invalid state
                }
            }
            return viewHolders
        }

    }

}