@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.zwander.lazyspannedgrid

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.scrollableArea
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.lazyLayoutItemAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LazySpannedGrid(
    mainAxisCount: Int,
    crossAxisCount: Int,
    orientation: Orientation,
    modifier: Modifier = Modifier,
    state: LazySpannedGridState = rememberLazySpannedGridState(),
    mainAxisSpacing: Dp = 0.dp,
    crossAxisSpacing: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    userScrollEnabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    content: LazySpannedGridScope.() -> Unit,
) {
    val itemProviderLambda = rememberLazySpannedGridItemProviderLambda(state, content)
    val layoutDirection = LocalLayoutDirection.current
    val placementCache = remember { SpannedGridPlacementCache() }
    val prefetchState = remember { LazyLayoutPrefetchState() }
    state.prefetchState = prefetchState
    val graphicsContext = LocalGraphicsContext.current

    val measurePolicy =
        remember(
            mainAxisCount,
            crossAxisCount,
            mainAxisSpacing,
            crossAxisSpacing,
            contentPadding,
            layoutDirection,
            state,
            graphicsContext,
        ) {
            LazyLayoutMeasurePolicy { constraints ->
                measureSpannedGrid(
                    measureScope = this,
                    itemProvider = itemProviderLambda(),
                    state = state,
                    orientation = orientation,
                    crossAxisCount = crossAxisCount,
                    mainAxisLineCount = mainAxisCount,
                    mainAxisSpacing = mainAxisSpacing,
                    crossAxisSpacing = crossAxisSpacing,
                    contentPadding = contentPadding,
                    layoutDirection = layoutDirection,
                    constraints = constraints,
                    placementCache = placementCache,
                    graphicsContext = graphicsContext,
                )
            }
        }

    LazyLayout(
        itemProvider = itemProviderLambda,
        modifier =
            modifier
                .lazyLayoutItemAnimator(state.itemAnimator)
                .scrollableArea(
                    state = state,
                    orientation = orientation,
                    // While a reorder drag is active (see suppressPlacementAnimationKey's own
                    // KDoc), disable scrollableArea's *own* native touch-drag-then-fling gesture
                    // recognition entirely, rather than leaving it enabled alongside
                    // `.reorderable()`. scrollableArea's ScrollableNode and `.reorderable()`'s own
                    // drag() tracking are two independent pointerInput consumers on the same
                    // modifier chain — nothing stops scrollableArea's gesture detector from also
                    // reacting to the same post-long-press movement, and Compose's Main pass
                    // dispatches to it *before* `.reorderable()` (it's later/innermost in the
                    // chain), so it can consume a move event `.reorderable()`'s own drag() then
                    // sees as already-consumed and treats as an externally-cancelled gesture.
                    // Reported as "the whole grid jumps around mid-drag" specifically once a
                    // page-snapping FlingBehavior was in use — with the default decay fling any
                    // stray scrollableArea-side reaction is a barely-noticeable nudge, but a snap
                    // fling animates a full, very visible jump to the nearest page boundary,
                    // making an otherwise-rare race obvious. This only disables *gesture
                    // recognition*: our own reorder-driven autoscroll calls gridState.scrollBy()
                    // programmatically, which works regardless of `enabled` (that flag only gates
                    // the touch-drag detector, not the underlying ScrollableState API).
                    enabled = userScrollEnabled && state.suppressPlacementAnimationKey == null,
                    reverseScrolling = false,
                    flingBehavior = flingBehavior,
                ),
        prefetchState = prefetchState,
        measurePolicy = measurePolicy,
    )
}
