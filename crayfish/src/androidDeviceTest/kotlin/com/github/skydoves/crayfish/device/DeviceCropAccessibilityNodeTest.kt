/*
 * Designed and developed by 2026 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.skydoves.crayfish.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.test.platform.app.InstrumentationRegistry
import com.github.skydoves.crayfish.decode.CropSource
import com.github.skydoves.crayfish.ui.CropAccessibility
import com.github.skydoves.crayfish.ui.CropAccessibilityAction
import com.github.skydoves.crayfish.ui.CropState
import com.github.skydoves.crayfish.ui.CropStatus
import com.github.skydoves.crayfish.ui.Cropper
import com.github.skydoves.crayfish.ui.defaultCropActionLabel
import com.github.skydoves.crayfish.ui.rememberCropState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What TalkBack actually receives, read out of the real `AccessibilityNodeInfo` tree.
 *
 * Every other accessibility test in this repository, `CropOverlayAccessibilityTest` above all,
 * inspects the Compose **semantics** tree. That tree is the *input* to Android's accessibility
 * pipeline, not its output. What a screen reader consumes is the `AccessibilityNodeInfo` tree that
 * `AndroidComposeViewAccessibilityDelegateCompat` synthesises from those semantics, and between
 * the two sits real translation code with real ways to lose things: a custom action past the
 * 32-action ceiling is dropped, a state description on API 29 and below lands in an extras bundle
 * instead of a field, a node whose semantics are not "important" never becomes a node at all.
 *
 * So this class asks the platform the same question TalkBack asks it. `UiAutomation` is an
 * accessibility service: `rootInActiveWindow` is the same tree, fetched over the same IPC, and
 * `performAction` is the same channel the actions menu uses. That is as close to a screen reader
 * as a test can stand without scripting TalkBack itself.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceCropAccessibilityNodeTest {

  /**
   * The crop frame is a node with a name.
   *
   * A node carrying neither `contentDescription` nor `text` is the node TalkBack announces as
   * "unlabelled", which is WCAG 4.1.2, and it is what the library claims not to ship.
   */
  @Test
  fun theCropFrameIsANodeWithANonEmptyAccessibleName() = runComposeUiTest {
    val automation = connectAsAccessibilityService()
    showCropper()
    val node = awaitCropNode(automation)

    val name = node.contentDescription?.toString() ?: node.text?.toString()
    assertNotNull(name, "the crop node reached TalkBack with neither contentDescription nor text")
    assertTrue(name.isNotBlank(), "the crop node's accessible name is blank")
    assertEquals(CropAccessibility.Default.contentDescription, name, "the name was rewritten")

    assertTrue(node.isVisibleToUser, "the crop node is not visible to a screen reader")
    assertTrue(node.isImportantForAccessibility, "the crop node is not important for a11y")
    val bounds = Rect().also(node::getBoundsInScreen)
    assertTrue(bounds.width() > 0 && bounds.height() > 0, "the crop node has no bounds: $bounds")
  }

  /**
   * All thirteen custom actions survive the translation, by label.
   *
   * This is the WCAG 2.2 SC 2.5.7 claim, that the crop rectangle is operable without a drag, and
   * it is a claim about the actions menu a screen reader builds, which it builds from
   * `AccessibilityNodeInfo.getActionList()`. Proving it on the semantics tree proves the library
   * asked for the actions, not that anyone can reach them.
   *
   * Set equality against [defaultCropActionLabel], not membership: an action that quietly falls
   * out of [CropAccessibilityAction] would make a membership check vacuous.
   */
  @Test
  fun everyCustomActionSurvivesTheTranslationIntoTheNodeTree() = runComposeUiTest {
    val automation = connectAsAccessibilityService()
    showCropper()
    val node = awaitCropNode(automation)

    val custom = node.actionList.filter { it.label != null }
    val labels = custom.map { it.label.toString() }

    // The frozen oracle: the library's own enum, which no part of the pipeline under test writes.
    val expected = CropAccessibilityAction.entries.map(::defaultCropActionLabel)
    assertEquals(
      expected.toSet(),
      labels.toSet(),
      "the actions TalkBack would offer are not the actions the library registers",
    )
    assertEquals(expected.size, labels.size, "a label was duplicated in the node's action list")

    // And the count the semantics tree declares, which is what the translation had to carry over.
    val declared = onNodeWithContentDescription(CropAccessibility.Default.contentDescription)
      .fetchSemanticsNode()
      .config
      .getOrNull(SemanticsActions.CustomActions)
      .orEmpty()
    assertEquals(
      declared.size,
      custom.size,
      "the semantics tree declares ${declared.size} custom actions, the node tree exposes " +
        "${custom.size}: ${declared.map { it.label }} vs $labels",
    )

    val ids = custom.map { it.id }
    assertEquals(ids.size, ids.toSet().size, "two custom actions share an action id: $ids")
    // Compose allocates custom actions from `R.id.accessibility_custom_action_0..31`, which are
    // application resource ids (package byte 0x7f). A framework action id would be a small bitmask
    // (ACTION_CLICK = 0x10) or an `android:` resource (0x0102xxxx), and colliding with one would
    // mean a nudge is delivered as a click.
    ids.forEach { id ->
      assertEquals(
        0x7f,
        id ushr 24,
        "custom action id 0x${Integer.toHexString(id)} is not an application resource id",
      )
    }
    val standard = node.actionList.filter { it.label == null }.map { it.id }.toSet()
    assertTrue(
      ids.none { it in standard },
      "a custom action reuses a standard action id: ${ids.filter { it in standard }}",
    )
  }

  /**
   * The actions work when invoked through the accessibility channel.
   *
   * Not a touch gesture and not the Compose test API: [AccessibilityNodeInfo.performAction] over
   * the same IPC a screen reader uses. It is the strongest proxy available for "someone using
   * TalkBack can move the crop frame", and it is the half of SC 2.5.7 that a label alone does not
   * establish.
   */
  @Test
  fun aNudgeInvokedThroughTheAccessibilityChannelMovesTheCropFrame() = runComposeUiTest {
    val automation = connectAsAccessibilityService()
    val state = showCropper()
    val node = awaitCropNode(automation)

    val label = defaultCropActionLabel(CropAccessibilityAction.MoveRight)
    val action = assertNotNull(
      node.actionList.firstOrNull { it.label?.toString() == label },
      "\"$label\" is not in the action list TalkBack would show",
    )

    val before = state.cropRect
    assertTrue(
      before.right < state.viewportSize.width - 1f,
      "the rectangle already touches the right edge, so a move right cannot be observed: $before",
    )
    assertTrue(
      node.performAction(action.id),
      "the accessibility channel refused action 0x${Integer.toHexString(action.id)}",
    )
    waitUntil(
      conditionDescription = "the crop rectangle moves after \"$label\" was performed",
      timeoutMillis = TIMEOUT,
    ) { state.cropRect != before }

    val after = state.cropRect
    assertTrue(after.left > before.left, "left did not move right: ${before.left} -> ${after.left}")
    assertTrue(
      after.right > before.right,
      "right did not move right: ${before.right} -> ${after.right}",
    )
    assertEquals(before.width, after.width, TOLERANCE, "a move resized the rectangle")
    assertEquals(before.top, after.top, TOLERANCE, "a horizontal move moved the rectangle down")
  }

  /**
   * The state description reaches the node, and it follows the rectangle.
   *
   * A state description that never changes is a status light that is always green: announced,
   * wrong, and unnoticed. It also lands in two different places depending on the platform version,
   * so the field this reads is chosen by API level rather than assumed.
   */
  @Test
  fun theStateDescriptionReachesTheNodeAndFollowsTheCropFrame() = runComposeUiTest {
    val automation = connectAsAccessibilityService()
    val state = showCropper()
    val node = awaitCropNode(automation)

    val before = assertNotNull(
      node.readStateDescription(),
      "no state description on the crop node (API ${Build.VERSION.SDK_INT})",
    )
    assertTrue(before.isNotBlank(), "the state description is blank")
    assertTrue(before.any { it.isDigit() }, "the state description locates nothing: \"$before\"")

    val label = defaultCropActionLabel(CropAccessibilityAction.MoveRight)
    val action = assertNotNull(node.actionList.firstOrNull { it.label?.toString() == label })
    val rect = state.cropRect
    assertTrue(node.performAction(action.id), "the accessibility channel refused \"$label\"")
    waitUntil(
      conditionDescription = "the crop rectangle moves after \"$label\" was performed",
      timeoutMillis = TIMEOUT,
    ) { state.cropRect != rect }

    // `refresh()` is the only read that bypasses the client-side node cache, which nothing
    // invalidates here: Compose emits accessibility events only for a service that appears in
    // `getEnabledAccessibilityServiceList`, and UiAutomation deliberately does not.
    var after: String? = null
    waitUntil(
      conditionDescription = "the node's state description changes from \"$before\"",
      timeoutMillis = TIMEOUT,
    ) {
      node.refresh()
      after = node.readStateDescription()
      after != null && after != before
    }
    assertNotEquals(
      before,
      assertNotNull(after, "the state description disappeared after the move"),
      "the state description did not follow the rectangle",
    )
  }

  /**
   * One crop node, not two.
   *
   * The chrome underneath draws and takes the drag; it is silenced with `clearAndSetSemantics {}`
   * precisely so a screen reader is not offered a full-screen surface it can focus but only drag,
   * next to the node that actually works, with no way to tell them apart. At this layer the claim
   * is countable: the Compose host must publish exactly one child, and exactly one node in the
   * whole window may carry a name or stop a screen reader.
   *
   * **What this check can and cannot see, measured rather than assumed.** Giving the chrome a
   * `contentDescription` of its own puts a second node in the *semantics* tree and leaves this one
   * at six nodes, because the translation drops a semantics node that a later sibling covers
   * completely, and the accessible target is exactly that sibling. So `clearAndSetSemantics {}` is
   * belt and braces here: deleting it alone changes nothing a screen reader can observe. What does
   * reach a screen reader, and what this test turns red for, is a named node the accessible target
   * does *not* cover: shrink the target and the chrome arrives as a second stop.
   */
  @Test
  fun thePointerSurfaceIsNotASecondNodeTheScreenReaderCanReach() = runComposeUiTest {
    val automation = connectAsAccessibilityService()
    showCropper()
    val node = awaitCropNode(automation)
    val tree = flatten(assertNotNull(automation.rootInActiveWindow))

    val named = tree.filter { it.contentDescription != null || it.text != null }
    assertEquals(
      listOf(CropAccessibility.Default.contentDescription),
      named.map { (it.contentDescription ?: it.text).toString() },
      "something other than the crop frame announces itself in this window",
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val stops = tree.filter { it.isScreenReaderFocusable }
      assertEquals(
        1,
        stops.size,
        "a screen reader has ${stops.size} places to stop in this window: " +
          stops.map { "${it.className}/${it.contentDescription}" },
      )
    }

    // The Compose host's only child is the crop node. A chrome node that acquired an accessible
    // identity of its own would appear here as a sibling.
    val parent = assertNotNull(node.parent, "the crop node has no parent")
    assertEquals(
      1,
      parent.childCount,
      "the Compose host publishes ${parent.childCount} nodes, not just the crop frame",
    )
  }

  // ---------------------------------------------------------------------------------------------

  /**
   * Connects the test as an accessibility service and proves the connection took.
   *
   * The flags must be in place before the first `rootInActiveWindow`; set afterwards, the tree
   * that was already fetched comes back without view ids and without the other windows, and the
   * assertion reads a sparse tree as a finding.
   */
  private fun connectAsAccessibilityService(): UiAutomation {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    automation.serviceInfo = automation.serviceInfo.apply {
      flags = flags or
        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
    }
    val applied = automation.serviceInfo.flags
    assertTrue(
      applied and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0 &&
        applied and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0,
      "the accessibility service flags did not take: 0x${Integer.toHexString(applied)}",
    )
    return automation
  }

  private fun ComposeUiTest.showCropper(): CropState {
    var state: CropState? = null
    setContent {
      val cropState = rememberCropState(source())
      state = cropState
      Cropper(state = cropState, modifier = Modifier.fillMaxSize())
    }
    waitUntil(
      conditionDescription = "the cropper reaches CropStatus.Ready and is laid out",
      timeoutMillis = TIMEOUT,
    ) {
      val current = state
      current != null && current.status is CropStatus.Ready && current.viewportSize.width > 0f
    }
    return assertNotNull(state, "the cropper never reached the screen")
  }

  /**
   * The crop node, polled rather than read once.
   *
   * The node tree is built on the app's main thread and delivered over IPC, so it appears some
   * frames after the composition does. A single read is a race that fails on a slow device and
   * passes on a fast one.
   */
  private fun ComposeUiTest.awaitCropNode(automation: UiAutomation): AccessibilityNodeInfo {
    var node: AccessibilityNodeInfo? = null
    waitUntil(
      conditionDescription =
      "a node named \"${CropAccessibility.Default.contentDescription}\" appears in the " +
        "AccessibilityNodeInfo tree",
      timeoutMillis = TIMEOUT,
    ) {
      node = automation.rootInActiveWindow?.let { root ->
        flatten(root).firstOrNull {
          it.contentDescription?.toString() == CropAccessibility.Default.contentDescription
        }
      }
      node != null
    }
    return assertNotNull(node, "no node named \"${CropAccessibility.Default.contentDescription}\"")
  }

  /**
   * Where the state description lives, which is not the same place on every platform version.
   *
   * `AccessibilityNodeInfo.getStateDescription()` only exists from API 30. Below it,
   * `AccessibilityNodeInfoCompat` packs the value into the node's extras under its own key, and
   * reading the field there would report a missing announcement that is in fact present.
   */
  private fun AccessibilityNodeInfo.readStateDescription(): String? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      stateDescription?.toString()
    } else {
      extras?.getCharSequence(COMPAT_STATE_DESCRIPTION_KEY)?.toString()
    }

  private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
    val out = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo) {
      out += node
      for (index in 0 until node.childCount) {
        node.getChild(index)?.let(::walk)
      }
    }
    walk(root)
    return out
  }

  private fun source() = CropSource.FilePath(
    File("/data/local/tmp/crayfish-fixtures", "uhd.png").also {
      check(it.isFile) { "missing device fixture $it: run ./gradlew :crayfish:pushDeviceFixtures" }
    }.absolutePath,
  )

  private companion object {
    const val TIMEOUT = 20_000L
    const val TOLERANCE = 0.5f

    /** `AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY`, which is not public API. */
    const val COMPAT_STATE_DESCRIPTION_KEY =
      "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"
  }
}
