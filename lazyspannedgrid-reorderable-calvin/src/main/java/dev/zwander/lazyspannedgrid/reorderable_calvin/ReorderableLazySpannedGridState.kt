@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE", "EXPOSED_PARAMETER_TYPE")

package dev.zwander.lazyspannedgrid.reorderable_calvin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import dev.zwander.lazyspannedgrid.LazySpannedGridItemInfo
import dev.zwander.lazyspannedgrid.LazySpannedGridItemScope
import dev.zwander.lazyspannedgrid.LazySpannedGridLayoutInfo
import dev.zwander.lazyspannedgrid.LazySpannedGridState
import dev.zwander.lazyspannedgrid.rememberLazySpannedGridState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import sh.calvin.reorderable.AbsolutePixelPadding
import sh.calvin.reorderable.DragGestureDetector
import sh.calvin.reorderable.LazyCollectionItemInfo
import sh.calvin.reorderable.LazyCollectionLayoutInfo
import sh.calvin.reorderable.LazyCollectionState
import sh.calvin.reorderable.ReorderableCollectionItem
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableLazyCollectionDefaults
import sh.calvin.reorderable.ReorderableLazyCollectionState
import sh.calvin.reorderable.ScrollMoveMode
import sh.calvin.reorderable.Scroller
import sh.calvin.reorderable.rememberScroller

// ---------------------------------------------------------------------------------------------
// IMPORTANT — internal-API ceiling this file runs into (discovered while porting it, 2026-08-29):
//
// sh.calvin.reorderable ships as a Kotlin Multiplatform artifact (reorderable-android). With this
// project's Kotlin 2.4.10 toolchain, ANY call our own module emits to an internal INSTANCE MEMBER
// of ReorderableLazyCollectionState — onDrag, onDragStart, onDragStop, isItemDragging,
// draggingItemOffset, previousDraggingItemKey/Offset, reorderableKeys — crashes the K2 compiler's
// Fir2Ir "actualizer" with `IllegalStateException: No override for FUN IR_EXTERNAL_DECLARATION_STUB`,
// regardless of whether the call site is inside this subclass or an unrelated top-level function.
// This is NOT the same class of thing as the file's own @Suppress up top: that suppression covers
// referencing internal *types* (as constructor parameter types) and internal *constructors*, both
// of which compile and run fine — the crash is specific to calling internal *members declared on*
// that one class. Calling the library's own internal top-level composables/functions (like
// ReorderableCollectionItem, or ReorderableCollectionItemScope.draggableHandle's *public* interface
// method, whose private implementation internally calls onDragStart/onDrag/onDragStop) is fine,
// since the internal dispatch then happens inside the library's own already-compiled bytecode,
// never inside ours. This is what lets [Scroller]/[ReorderableCollectionItemScope.draggableHandle]
// below work like the real thing, including real autoscroll — it's the library's own code driving
// it, we just supply the (public) pieces it needs.
//
// Practical effect: this class cannot read draggingItemOffset, isItemDragging, or call onDrag/
// onDragStart/onDragStop directly, and cannot read previousDraggingItemKey/previousDraggingItemOffset
// at all. Where those were needed, this file tracks the same information itself instead:
//   - "is this key currently being dragged" / "which item is being dragged" — tracked directly via
//     beginTrackingDrag/endTrackingDrag, driven off draggableHandle's own public onDragStarted/
//     onDragStopped callbacks (see TrackingReorderableCollectionItemScope below).
//   - the dragged item's live pixel offset (needed for the drag-follow visual) — tracked via an
//     independent, non-consuming pointer observer layered alongside draggableHandle's own gesture
//     detector (see observeRawDragDelta below), mirroring org.burnoutcrew.reorderable's own
//     original signal (raw accumulated pointer delta since drag start) even more closely than a
//     draggingItemOffset-based approach would have.
//   - the post-drag "spring back from wherever it was released" visual (previousDraggingItemKey/
//     previousDraggingItemOffset) is reproduced independently too — ReorderableLazySpannedGridState
//     keeps its own previousDraggingItemKey/previousDraggingItemOffset (an Animatable snapshotted
//     from draggingItemOffsetOrZero at release time and animated back to zero), rather than reading
//     the base class's uncallable copies.
//
// All of the above has been verified on-device (2026-08-29), via the same real-touch-input
// instrumentation used to find and fix the bugs that inspection alone missed — see project memory
// "calvin-reorderable-internal-api-compiler-crash" for the specific findings and how each was
// diagnosed. Re-verify the same way before trusting further changes to this file.
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// Adapters from this library's own grid types to sh.calvin.reorderable's internal collection
// abstraction, mirroring LazyGridItemInfo.toLazyCollectionItemInfo() / toLazyCollectionLayoutInfo()
// / LazyGridState.toLazyCollectionState() in the real library's ReorderableLazyGrid.kt.
// ---------------------------------------------------------------------------------------------

private fun LazySpannedGridItemInfo.toLazyCollectionItemInfo() =
    object : LazyCollectionItemInfo<LazySpannedGridItemInfo> {
        override val index: Int
            get() = this@toLazyCollectionItemInfo.index
        override val key: Any
            get() = this@toLazyCollectionItemInfo.key
        override val offset: IntOffset
            get() = this@toLazyCollectionItemInfo.offset
        override val size: IntSize
            get() = this@toLazyCollectionItemInfo.size
        override val data: LazySpannedGridItemInfo
            get() = this@toLazyCollectionItemInfo
    }

// LazySpannedGridLayoutInfo has no reverseLayout/beforeContentPadding concept of its own (see
// LazySpannedGridState.kt), so these are fixed at their only valid values for this grid.
private fun LazySpannedGridLayoutInfo.toLazyCollectionLayoutInfo() =
    object : LazyCollectionLayoutInfo<LazySpannedGridItemInfo> {
        override val visibleItemsInfo: List<LazyCollectionItemInfo<LazySpannedGridItemInfo>>
            get() = this@toLazyCollectionLayoutInfo.visibleItemsInfo.map { it.toLazyCollectionItemInfo() }
        override val viewportSize: IntSize
            get() = this@toLazyCollectionLayoutInfo.viewportSize
        override val orientation: Orientation
            get() = this@toLazyCollectionLayoutInfo.orientation
        override val reverseLayout: Boolean
            get() = false
        override val beforeContentPadding: Int
            get() = 0
    }

private val LazySpannedGridLayoutInfo.mainAxisViewportSize: Int
    get() = if (orientation == Orientation.Vertical) viewportSize.height else viewportSize.width

private fun LazySpannedGridState.toLazyCollectionState() =
    object : LazyCollectionState<LazySpannedGridItemInfo> {
        override val firstVisibleItemIndex: Int
            get() = this@toLazyCollectionState.firstVisibleItemIndex
        override val firstVisibleItemScrollOffset: Int
            get() = this@toLazyCollectionState.firstVisibleItemScrollOffset
        override val layoutInfo: LazyCollectionLayoutInfo<LazySpannedGridItemInfo>
            get() = this@toLazyCollectionState.layoutInfo.toLazyCollectionLayoutInfo()

        override suspend fun animateScrollBy(value: Float, animationSpec: AnimationSpec<Float>): Float {
            return try {
                (this@toLazyCollectionState as ScrollableState).animateScrollBy(value, animationSpec)
            } catch (_: CancellationException) {
                // A higher-priority user-input scroll can cancel this via ScrollableState's
                // MutatorMutex — the base class's own autoscroll (driven by [Scroller], see the
                // file-level comment) and any real touch scroll compete for the same mutex.
                0f
            }
        }

        override suspend fun requestScrollToItem(index: Int, scrollOffset: Int) {
            try {
                this@toLazyCollectionState.scrollToItem(index, scrollOffset)
            } catch (_: CancellationException) {
                // ReorderableLazyCollectionState.moveItems calls this whenever the move involves
                // the first visible item, independently of the base class's own autoscroll — both
                // compete for the same gridState scroll mutex. Left uncaught, this would propagate
                // out of the *library's* scope.launch and cancel `scope` itself (a plain
                // rememberCoroutineScope, not a supervisor) — silently disabling every future
                // onMove routed through that same branch for the rest of the composition.
            }
        }
    }

/**
 * Mirrors [sh.calvin.reorderable.rememberReorderableLazyGridState]'s own parameter surface as
 * closely as this grid's own concepts allow — real [scrollThreshold]/[scrollThresholdPadding]/
 * [scroller] drive the base class's own autoscroll (see the file-level comment on why that's safe
 * to call into, unlike its other internal members), rather than this file rolling its own.
 */
@Composable
fun rememberReorderableLazySpannedGridState(
    gridState: LazySpannedGridState = rememberLazySpannedGridState(),
    canDragOver: ((draggedOver: LazySpannedGridItemInfo, dragging: LazySpannedGridItemInfo) -> Boolean)? = null,
    onDragEnd: ((startIndex: Int, endIndex: Int) -> (Unit))? = null,
    scrollThresholdPadding: PaddingValues = PaddingValues(0.dp),
    scrollThreshold: Dp = ReorderableLazyCollectionDefaults.ScrollThreshold,
    scroller: Scroller = rememberScroller(
        scrollableState = gridState,
        pixelAmountProvider = { gridState.layoutInfo.mainAxisViewportSize * ScrollAmountMultiplier },
    ),
    scrollMoveMode: ScrollMoveMode = ScrollMoveMode.SWAP,
    onMove: suspend CoroutineScope.(LazySpannedGridItemInfo, LazySpannedGridItemInfo) -> Unit,
): ReorderableLazySpannedGridState {
    val density = LocalDensity.current
    val scrollThresholdPx = with(density) { scrollThreshold.toPx() }
    val scope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val onMoveState = rememberUpdatedState<suspend CoroutineScope.(LazySpannedGridItemInfo, LazySpannedGridItemInfo) -> Unit> { from, to ->
        onMove(from, to)
    }
    val canDragOverState = rememberUpdatedState(canDragOver)
    val absoluteScrollThresholdPadding = AbsolutePixelPadding(
        start = with(density) { scrollThresholdPadding.calculateStartPadding(layoutDirection).toPx() },
        end = with(density) { scrollThresholdPadding.calculateEndPadding(layoutDirection).toPx() },
        top = with(density) { scrollThresholdPadding.calculateTopPadding().toPx() },
        bottom = with(density) { scrollThresholdPadding.calculateBottomPadding().toPx() },
    )
    val state = remember(scope, gridState, scrollThreshold, scrollThresholdPadding, scroller) {
        ReorderableLazySpannedGridState(
            gridState = gridState,
            scope = scope,
            onMoveState = onMoveState,
            canDragOverState = canDragOverState,
            scrollThreshold = scrollThresholdPx,
            scrollThresholdPadding = absoluteScrollThresholdPadding,
            scroller = scroller,
            scrollMoveMode = scrollMoveMode,
            layoutDirection = layoutDirection,
        )
    }

    // Fire the synthesized onDragEnd once a drag that was actually tracked (had a start index)
    // ends, mirroring org.burnoutcrew.reorderable's own onDragEnd(startIndex, endIndex) callback.
    LaunchedEffect(state, onDragEnd) {
        snapshotFlow { state.draggingItemInfo }
            .distinctUntilChanged { old, new -> (old != null) == (new != null) }
            .collect { info ->
                if (info == null) {
                    val start = state.consumeDragStartIndex()
                    if (start != null) {
                        onDragEnd?.invoke(start, state.lastMoveTargetIndex ?: start)
                    }
                }
            }
    }

    // Suppress the dragged item's own animateItem() placement animation for as long as it's being
    // dragged — see the KDoc on LazySpannedGridState.suppressPlacementAnimationKey for why a
    // springing/lagging base position (instead of an instant one) breaks this library's own drag
    // offset math and visibly lurches the dragged item back towards its old slot on every repack.
    LaunchedEffect(state) {
        snapshotFlow { state.draggingItemInfo?.key }
            .collect { key ->
                gridState.suppressPlacementAnimationKey = key
            }
    }

    return state
}

/** Matches sh.calvin.reorderable's own internal `ScrollAmountMultiplier` used by `rememberReorderableLazyGridState`'s default [Scroller]. */
private const val ScrollAmountMultiplier = 0.05f

/**
 * Holds this state's drag-tracking and drop-target-selection logic in a plain object constructed
 * *before* [ReorderableLazySpannedGridState] itself. sh.calvin.reorderable's `shouldItemMove` must
 * be supplied directly to [ReorderableLazyCollectionState]'s superclass constructor call, and
 * Kotlin disallows referencing `this` of the subclass being constructed anywhere in that argument
 * list (`INSTANCE_ACCESS_BEFORE_SUPER_CALL`) — even inside a lambda that wouldn't run until later.
 * Constructor default-parameter expressions, unlike superclass-constructor arguments, are allowed
 * to reference other already-bound constructor parameters, so this is built there instead.
 */
private class DropTargetSelector(
    private val gridState: LazySpannedGridState,
    private val canDragOverState: State<((draggedOver: LazySpannedGridItemInfo, dragging: LazySpannedGridItemInfo) -> Boolean)?>,
) {
    var draggingItemInfo: LazySpannedGridItemInfo? by mutableStateOf(null)
        private set

    /**
     * Raw accumulated pointer delta since the current drag started, tracked entirely by this
     * file's own parallel pointer observer (see [ReorderableLazySpannedGridState.accumulateRawDragDelta]
     * and `observeRawDragDelta` below) — NOT sh.calvin.reorderable's own `draggingItemOffset`,
     * which cannot be called from this module at all (see the file-level comment up top). This is
     * actually closer to org.burnoutcrew.reorderable's original signal (which also used raw
     * pointer delta, not rendered position) than a draggingItemOffset-based approach would be.
     */
    private var rawDragDelta: Offset by mutableStateOf(Offset.Zero)

    /** The dragged item's own [LazySpannedGridItemInfo.offset] at the moment [beginTrackingDrag] fired — see [draggingItemOffsetOrZero]. */
    private var draggingItemInitialOffset: IntOffset = IntOffset.Zero

    fun accumulateRawDelta(delta: Offset) {
        rawDragDelta += delta
    }

    /**
     * `rawDragDelta` alone is only correct as a `graphicsLayer` translation while the dragged
     * item's own *base* rendered position (from the grid's own placement, before this translation
     * is applied on top) stays put at [draggingItemInitialOffset]. The moment a swap confirms, the
     * grid repacks and the item's base position jumps straight to its new slot (no placement
     * *animation* to mask it — see `suppressPlacementAnimationKey` — but the jump itself still
     * happens); stacking the *same* since-drag-start delta on top of that new base position then
     * double-counts however far the item's slot just moved, offsetting it by exactly that amount
     * until the next swap's jump happens to move it back into alignment. Mirrors
     * sh.calvin.reorderable's own `draggingItemOffset` (`draggingItemDraggedDelta +
     * (draggingItemInitialOffset - offset)`), minus its `predictedDraggingItemOffset` handling for
     * the gap between confirming a move and the next remeasure — not needed here since this port's
     * own [gridState].layoutInfo and the item's actual placement are always produced by the same
     * measure pass, never staggered the way the wrapped-adapter version upstream can be.
     */
    val draggingItemOffsetOrZero: Offset
        get() {
            val info = draggingItemInfo ?: return Offset.Zero
            val currentOffset = gridState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == info.key }?.offset ?: draggingItemInitialOffset
            return rawDragDelta + (draggingItemInitialOffset - currentOffset).toOffset()
        }

    private var dragStartIndex: Int? = null
    var lastMoveTargetIndex: Int? = null
        private set

    /** Consumed once by the onDragEnd LaunchedEffect in [rememberReorderableLazySpannedGridState]. */
    fun consumeDragStartIndex(): Int? = dragStartIndex.also { dragStartIndex = null }

    // draggableHandle()/longPressDraggableHandle() (see TrackingReorderableCollectionItemScope
    // below) call the base class's own internal onDragStart/onDrag/onDragStop directly, which
    // this module cannot call itself (see the file-level comment up top) — this state is instead
    // updated in parallel with (not instead of) the base class's own lifecycle, driven from the
    // public onDragStarted/onDragStopped callback params.
    fun beginTrackingDrag(item: LazySpannedGridItemInfo) {
        draggingItemInfo = item
        draggingItemInitialOffset = item.offset
        rawDragDelta = Offset.Zero
        dragStartIndex = item.index
        lastMoveTargetIndex = null
    }

    fun endTrackingDrag() {
        draggingItemInfo = null
    }

    /**
     * Bound as [ReorderableLazyCollectionState]'s `shouldItemMove` constructor argument (base
     * class calls this synchronously from its own private `onDrag`/`moveDraggingItemToEnd`, once
     * per visible item, to decide whether the dragging item should swap with it). Beyond resolving
     * the [LazySpannedGridItemInfo] behind each [Rect] (needed for [canDragOverState], which the
     * base class has no concept of), this intentionally stays as close to the base class's own
     * default (`draggingItem.contains(item.center)`) as this grid's heterogeneous spans allow.
     */
    fun shouldItemMove(draggingItemRect: Rect, itemRect: Rect): Boolean {
        // draggingItemInfo is only refreshed at drag start/stop (see beginTrackingDrag — there's
        // no hook to update it per-frame), so its .offset/.row/.column/.index go stale the instant
        // a move commits mid-drag. Its .key is stable for the whole drag though (dragged item
        // identity doesn't change, only its slot), so re-resolve the current info fresh here,
        // every call, rather than trusting the stale field directly.
        val draggingKey = draggingItemInfo?.key ?: return false
        val dragging = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }
            ?: draggingItemInfo ?: return false
        // Recover the LazySpannedGridItemInfo behind itemRect. The grid is a non-overlapping
        // tiling, so an exact offset+size match uniquely identifies the item; itemRect is always
        // constructed by the base class as Rect(item.offset.toOffset(), item.size.toSize()). The
        // base class's own findTargetItem/moveDraggingItemToEnd already exclude the dragging
        // item's own current slot via their own additionalPredicate before calling shouldItemMove,
        // so the index check here is defense-in-depth, not load-bearing.
        val candidate = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != draggingKey && Rect(it.offset.toOffset(), it.size.toSize()) == itemRect
        } ?: return false

        if (canDragOverState.value?.invoke(candidate, dragging) == false) return false

        // Point-test the dragged item's own current *center* against the candidate's rect,
        // instead of admitting any candidate whose rect merely overlaps (the base class's own
        // default `draggingItem.contains(item.center)` is the same idea, reversed — checking the
        // *dragged* item's center against the candidate ties targeting to "which single cell is
        // the pointer physically over", which is what a drag-to-reorder grid is expected to feel
        // like; with spans this varied — a 1x1 next to a 5x4 — a giant candidate's rect can
        // overlap almost anywhere nearby regardless of how far its actual center is).
        if (!itemRect.contains(draggingItemRect.center)) return false

        lastMoveTargetIndex = candidate.index
        return true
    }
}

/**
 * See the file-level comment at the top of this file for the internal-API ceiling this class runs
 * into: [sh.calvin.reorderable.ReorderableLazyCollectionState]'s own constructor and its
 * [LazyCollectionState]/[AbsolutePixelPadding] parameter types can be referenced fine (via the
 * file's `@Suppress`), but none of its internal *instance members* (`onDragStart`/`onDrag`/
 * `onDragStop`, `draggingItemOffset`, `isItemDragging`, `reorderableKeys`,
 * `previousDraggingItemKey`/`previousDraggingItemOffset`) can be called from this module at all —
 * doing so crashes the Kotlin compiler. [DropTargetSelector] and this class's own tracked state
 * (`draggingItemInfo`, `beginTrackingDrag`/`endTrackingDrag`, `accumulateRawDragDelta`) replace
 * what those would otherwise have provided. Everything else — real autoscroll via [Scroller],
 * [scrollThreshold]/[scrollThresholdPadding], [scrollMoveMode] — is passed straight through to the
 * superclass exactly like [sh.calvin.reorderable.ReorderableLazyGridState] does.
 */
class ReorderableLazySpannedGridState internal constructor(
    val gridState: LazySpannedGridState,
    private val scope: CoroutineScope,
    onMoveState: State<suspend CoroutineScope.(LazySpannedGridItemInfo, LazySpannedGridItemInfo) -> Unit>,
    canDragOverState: State<((draggedOver: LazySpannedGridItemInfo, dragging: LazySpannedGridItemInfo) -> Boolean)?>,
    scrollThreshold: Float,
    scrollThresholdPadding: AbsolutePixelPadding,
    scroller: Scroller,
    scrollMoveMode: ScrollMoveMode,
    layoutDirection: LayoutDirection,
    private val dropTargetSelector: DropTargetSelector = DropTargetSelector(gridState, canDragOverState),
) : ReorderableLazyCollectionState<LazySpannedGridItemInfo>(
    state = gridState.toLazyCollectionState(),
    scope = scope,
    onMoveState = onMoveState,
    scrollThreshold = scrollThreshold,
    scrollThresholdPadding = scrollThresholdPadding,
    scroller = scroller,
    scrollMoveMode = scrollMoveMode,
    layoutDirection = layoutDirection,
    shouldItemMove = dropTargetSelector::shouldItemMove,
) {
    // --- Tracked drag state, replacing the old public draggingItemKey/draggingItemIndex (org.
    // burnoutcrew.reorderable) / draggingItemOffset & isItemDragging (sh.calvin.reorderable), none
    // of which are usable here — see the file-level comment. ---

    val draggingItemInfo: LazySpannedGridItemInfo?
        get() = dropTargetSelector.draggingItemInfo
    val lastMoveTargetIndex: Int?
        get() = dropTargetSelector.lastMoveTargetIndex

    /** Consumed once by the onDragEnd LaunchedEffect in [rememberReorderableLazySpannedGridState]. */
    internal fun consumeDragStartIndex(): Int? = dropTargetSelector.consumeDragStartIndex()

    internal fun beginTrackingDrag(item: LazySpannedGridItemInfo) = dropTargetSelector.beginTrackingDrag(item)

    /**
     * Substitute for the base class's own (uncallable, see the class KDoc)
     * `previousDraggingItemKey`/`previousDraggingItemOffset` — without it, the instant a drag ends
     * the dragged item's [graphicsLayer][androidx.compose.ui.graphics.graphicsLayer] translation
     * (see [draggingItemOffsetOrZero]) is simply removed and replaced by [ReorderableLazySpannedGridItem]'s
     * `animateItemModifier`, which has no idea a drag was ever happening and so has nothing to
     * animate *from* — confirmed on-device (2026-08-29): releasing anywhere off-slot snapped the
     * item straight to its final position instead of visibly settling into place. Snapshotting the
     * exact release offset here and animating it back to zero mirrors the base class's own
     * `onDragStop` (`previousDraggingItemOffset.snapTo(startOffset); previousDraggingItemOffset
     * .animateTo(Offset.Zero, spring(...))`) — [ReorderableLazySpannedGridItem] applies this offset
     * the same way it applies [draggingItemOffsetOrZero] while actively dragging, just for
     * whichever key currently matches [previousDraggingItemKey] instead.
     */
    internal fun endTrackingDrag() {
        val releasedKey = draggingItemInfo?.key
        val releasedOffset = draggingItemOffsetOrZero
        dropTargetSelector.endTrackingDrag()
        if (releasedKey != null) {
            previousDraggingItemKey = releasedKey
            scope.launch {
                previousDraggingItemOffset.snapTo(releasedOffset)
                previousDraggingItemOffset.animateTo(
                    Offset.Zero,
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = Offset.VisibilityThreshold,
                    ),
                )
                previousDraggingItemKey = null
            }
        }
    }

    var previousDraggingItemKey: Any? by mutableStateOf(null)
        private set
    val previousDraggingItemOffset = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Fed by the parallel pointer observer in [TrackingReorderableCollectionItemScope]. */
    internal fun accumulateRawDragDelta(delta: Offset) = dropTargetSelector.accumulateRawDelta(delta)

    /** See [DropTargetSelector.draggingItemOffsetOrZero]. */
    val draggingItemOffsetOrZero: Offset
        get() = dropTargetSelector.draggingItemOffsetOrZero

    internal fun isItemDraggingState(key: Any): State<Boolean> = derivedStateOf { draggingItemInfo?.key == key }
}

/**
 * Watches raw pointer movement for as long as the pointer stays down, without ever calling
 * [androidx.compose.ui.input.pointer.PointerInputChange.consume] — deliberately, so it can be
 * layered alongside `draggableHandle`'s own gesture detector on the same element without
 * interfering with it (see the file-level comment on the internal-API ceiling this works around).
 */
private suspend fun PointerInputScope.observeRawDragDelta(onDelta: (Offset) -> Unit) {
    // Deliberately NOT awaitEachGesture { awaitFirstDown(); while (pressed) { ... } }: confirmed
    // via on-device logcat instrumentation (2026-08-29) that this specific pointerInput's own
    // coroutine gets cancelled and relaunched roughly once per measure pass for as long as the
    // dragged item's own LazySpannedGridItemInfo keeps getting a fresh instance passed down to it
    // (i.e. for the entire drag, not just around a swap) — while the item's own composition never
    // actually leaves the tree (confirmed via a DisposableEffect that never fired onDispose) and
    // the real draggableHandle's own gesture detector, structurally the same kind of call, is
    // unaffected. A gesture-scoped loop dies on every one of those restarts waiting on a fresh
    // awaitFirstDown() that will never come until the finger actually lifts — matching exactly the
    // reported bug ("only works for the first drag, and only partially"): rawDragDelta silently
    // freezes at whatever it last was the moment this coroutine first got torn down, for the rest
    // of that drag. Looping unconditionally instead, with no notion of "gesture start", makes each
    // restart self-heal: the new coroutine instance just waits for the very next pointer event
    // (which is imminent, since the same physical drag is still ongoing) and keeps accumulating
    // from there, at the cost of doing so needlessly often rather than exactly once per drag.
    awaitPointerEventScope {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull() ?: continue
            if (change.pressed) {
                // Not positionChange(): the real draggableHandle's own detectDragGestures always
                // consumes each move before this parallel, never-consuming observer gets to read
                // it (see the file-level comment), and positionChange() deliberately returns
                // Offset.Zero once a change is consumed (androidx.compose.ui.input.pointer.
                // PointerEvent.kt's positionChangeInternal) — confirmed via the same on-device
                // instrumentation: every observed move logged `consumed=true` and a zero delta
                // despite the base class's own (separately-tracked) drag offset advancing
                // normally. positionChangeIgnoreConsumed() reads the same raw position/
                // previousPosition pair regardless of who else already consumed it.
                onDelta(change.positionChangeIgnoreConsumed())
            }
        }
    }
}

/**
 * Per-item composable, analogous to the real library's `LazyGridItemScope.ReorderableItem` (there
 * is no built-in equivalent for a custom grid type). Wires [ReorderableCollectionItemScope]'s
 * public `draggableHandle`/`longPressDraggableHandle` modifiers to this state's tracked drag
 * lifecycle, and applies a drag-offset `graphicsLayer` translation the same way the real
 * `ReorderableItem` does — sourced from this file's own [ReorderableLazySpannedGridState.draggingItemOffsetOrZero]
 * rather than the base class's own (uncallable, see the file-level comment) `draggingItemOffset`.
 */
@Composable
fun LazySpannedGridItemScope.ReorderableLazySpannedGridItem(
    state: ReorderableLazySpannedGridState,
    key: Any,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animateItemModifier: Modifier = Modifier.animateItem(),
    content: @Composable ReorderableCollectionItemScope.(isDragging: Boolean) -> Unit,
) {
    val dragging by state.isItemDraggingState(key)
    val offsetModifier = if (dragging) {
        // Read inside the graphicsLayer block, like the real ReorderableItem does with its own
        // draggingItemOffset — draggingItemOffsetOrZero now depends on gridState.layoutInfo (see
        // its KDoc), which changes on every measure pass, not just every pointer move; reading it
        // here updates the layer directly instead of recomposing this whole composable each time.
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                val offset = state.draggingItemOffsetOrZero
                translationX = offset.x
                translationY = offset.y
            }
    } else if (key == state.previousDraggingItemKey) {
        // See ReorderableLazySpannedGridState.endTrackingDrag — springs the exact release-time
        // offset back to zero instead of jumping straight to animateItemModifier's placement,
        // mirroring the base class's own previousDraggingItemKey/previousDraggingItemOffset.
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                val offset = state.previousDraggingItemOffset.value
                translationX = offset.x
                translationY = offset.y
            }
    } else {
        animateItemModifier
    }

    ReorderableCollectionItem(
        state = state,
        key = key,
        modifier = modifier.then(offsetModifier),
        enabled = enabled,
        dragging = dragging,
    ) { isDragging ->
        val trackingScope = remember(this, key) { TrackingReorderableCollectionItemScope(this, state, key) }
        trackingScope.content(isDragging)
    }
}

/**
 * Wraps the real [ReorderableCollectionItemScope] to hook this file's own drag-lifecycle tracking
 * (see [ReorderableLazySpannedGridState.beginTrackingDrag]/`endTrackingDrag`) onto the same
 * `draggableHandle`/`longPressDraggableHandle` calls the consumer makes, and to layer the raw
 * pointer-delta observer (`observeRawDragDelta`) that stands in for the uncallable
 * `draggingItemOffset` — see the file-level comment for why both of these are necessary here.
 */
private class TrackingReorderableCollectionItemScope(
    private val delegate: ReorderableCollectionItemScope,
    private val state: ReorderableLazySpannedGridState,
    private val key: Any,
) : ReorderableCollectionItemScope {
    // The delegate's own draggableHandle()/longPressDraggableHandle() key their underlying
    // pointerInput on `reorderableLazyCollectionState` (a single instance stable for the whole
    // grid's lifetime, see sh.calvin.reorderable's own ReorderableCollectionItemScopeImpl) — so
    // once that coroutine launches for a given item, it never restarts, and the onDragStarted
    // closure passed to detectDragGesturesAfterLongPress/detectDragGestures is captured exactly
    // once, from whichever TrackingReorderableCollectionItemScope existed at that moment. Reading
    // the constructor's own `item` field inside that closure therefore keeps returning THAT one
    // scope's original `item` value forever — correct for the very first drag (nothing had moved
    // yet), permanently stale for every drag after: confirmed on-device (2026-08-29) via a
    // beginTrackingDrag log showing `passedOffset` frozen at the item's pre-first-drag position
    // while a fresh lookup logged in the same line (`liveOffset`) correctly tracked every
    // subsequent settle. `item.key` itself is stable and never goes stale, so re-resolving the
    // live item by key right when a drag actually starts fixes the anchor without needing the
    // delegate's own pointerInput to restart at all.
    private fun resolveCurrentItem(): LazySpannedGridItemInfo =
        state.gridState.layoutInfo.visibleItemsInfo.first { it.key == key }

    override fun Modifier.draggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (startedPosition: Offset) -> Unit,
        onDragStopped: () -> Unit,
        dragGestureDetector: DragGestureDetector,
    ): Modifier = with(delegate) {
        this@draggableHandle
            .pointerInput(key) { observeRawDragDelta { delta -> state.accumulateRawDragDelta(delta) } }
            .draggableHandle(
                enabled = enabled,
                interactionSource = interactionSource,
                onDragStarted = { position ->
                    state.beginTrackingDrag(resolveCurrentItem())
                    onDragStarted(position)
                },
                onDragStopped = {
                    state.endTrackingDrag()
                    onDragStopped()
                },
                dragGestureDetector = dragGestureDetector,
            )
    }

    override fun Modifier.longPressDraggableHandle(
        enabled: Boolean,
        interactionSource: MutableInteractionSource?,
        onDragStarted: (startedPosition: Offset) -> Unit,
        onDragStopped: () -> Unit,
    ): Modifier = with(delegate) {
        this@longPressDraggableHandle
            .pointerInput(key) { observeRawDragDelta { delta -> state.accumulateRawDragDelta(delta) } }
            .longPressDraggableHandle(
                enabled = enabled,
                interactionSource = interactionSource,
                onDragStarted = { position ->
                    state.beginTrackingDrag(resolveCurrentItem())
                    onDragStarted(position)
                },
                onDragStopped = {
                    state.endTrackingDrag()
                    onDragStopped()
                },
            )
    }
}
