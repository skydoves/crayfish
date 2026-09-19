# Accessibility

## Why a cropper needs this

[WCAG 2.2 SC 2.5.7 Dragging Movements](https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html)
is a Level AA success criterion. It says that any function operated by dragging must also be
operable by a single pointer without dragging, unless dragging is essential.

A crop rectangle is the textbook case. Resizing it by dragging a corner is a dragging movement, and
cropping is not one of the exceptions, so a cropper that only responds to drags fails AA. That
applies to people using a switch, a head pointer, or an eye tracker, and to anyone whose hands do
not do fine motion reliably on a given day.

## What Crayfish exposes

Every crop rectangle carries an accessible name, a live description of where it is, and thirteen
actions that move it without any dragging.

The actions are the `CropAccessibilityAction` entries. Four move the whole rectangle, eight move one
edge in or out, and one resets it:

| Action | Default label |
|---|---|
| `MoveLeft`, `MoveUp`, `MoveRight`, `MoveDown` | Move crop area left, up, right, down |
| `GrowLeft`, `ShrinkLeft` | Move left edge outward, inward |
| `GrowTop`, `ShrinkTop` | Move top edge outward, inward |
| `GrowRight`, `ShrinkRight` | Move right edge outward, inward |
| `GrowBottom`, `ShrinkBottom` | Move bottom edge outward, inward |
| `Reset` | Reset crop area |

Each one moves by `CropAccessibility.step`, which defaults to `16.dp`.

The state description reports the rectangle as percentages rather than pixels, because a pixel count
means nothing read aloud:

```
80% wide and 80% tall, 10% from the left and 10% from the top
```

Before the viewport has been measured it reports `not yet placed` rather than a rectangle of zeros.

## Customising it

`CropAccessibility` holds all four pieces, and you pass it to `CropOverlay`:

```kotlin
CropOverlay(
  state = state,
  accessibility = CropAccessibility(
    step = 24.dp,
    contentDescription = "Profile photo crop area",
    label = { action -> localizedLabel(action) },
    describe = { rect, viewport -> localizedDescription(rect, viewport) },
  ),
)
```

Both `label` and `describe` are functions rather than strings, so you can return a localized
resource for each action and format the description for the reader's locale. The defaults are
`defaultCropActionLabel` and `defaultCropDescription`, which are public, so you can wrap them rather
than reimplement them.

## Keyboard and D-pad

The crop rectangle takes focus and responds to arrow keys, which covers desktop, ChromeOS, and
Android TV. This is the same set of movements as the accessibility actions, routed through key
events instead.

## How it is verified

Semantics tests prove what Crayfish declares. They do not prove what an assistive technology receives,
because between the two sits Compose's own translation layer.

So the on device tests read the real `AccessibilityNodeInfo` tree through `UiAutomation`, which is
the same tree TalkBack consumes, and assert against what is actually in it: that the crop node has a
non empty accessible name, that all thirteen actions survive the translation with their labels, that
the state description is present and changes when the rectangle moves, and that exactly one node is
focusable rather than two competing ones.

The strongest of them invokes an action through the accessibility channel with
`performAction(actionId)` rather than through a touch gesture, and asserts the crop rectangle moved.
That is the closest available proxy for a screen reader user moving the frame.

One honest limitation: no device below API 30 was available, so the pre API 30 branch that packs the
state description into the node's extras is written but has never executed.
