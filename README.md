# LazySpannedGrid

This is a Compose implementation of [SpannedGridLayoutManager](https://github.com/zacharee/SpannedGridLayoutManager/), providing components for vertical and horizontal lazy grids that allow specifying both main-axis and cross-axis spans.

## Getting Started

For the base library:

```
implementation("dev.zwander:lazyspannedgrid:VERSION")
```

If you want to support reordering items, there's a pre-made reorderable state for [ComposeReorderable](https://github.com/aclassen/ComposeReorderable/) in:

```
implementation("dev.zwander:lazyspannedgrid-reorderable:VERSION")
```

Both `LazyVerticalSpannedGrid` and `LazyHorizontalSpannedGrid` are available. They work similarly to `Lazy*Grid` but with support for spans on both axes.

## Notes

* This library heavily relied on Claude to create the layout logic. The actual functionality has been tested, and many bugs have been fixed, but there will definitely be things I missed.
* Currently this library relies on the Compose alpha BOM to make use of APIs that were only added recently. This will eventually change once those APIs are released into stable.
