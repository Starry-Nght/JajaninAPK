package com.jajanin.app.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated validation of JAJANIN's adaptive responsive viewport, typography scaling,
 * dialog/overlay width caps, and 48dp accessible hit-target rules across all target
 * Android device configurations:
 * - 720 x 1280 (HD compact phone, 3-button nav)
 * - 720 x 1600 (HD+ tall phone, punch-hole cutout + gesture nav)
 * - 1080 x 2400 (FHD+ modern phone)
 * - 1440 x 3200 (QHD+ flagship phone)
 * - Compact 320dp phone
 * - Tablet Portrait (1600 x 2560 & 1200 x 1920)
 * - Tablet Landscape (2560 x 1600 & 1920 x 1200)
 * - Phone Landscape (2400 x 1080 with side cutout + 3-button nav)
 */
class ResponsiveLayoutTest {

    private data class DeviceProfile(
        val label: String,
        val safeWidthPx: Int,
        val safeHeightPx: Int,
        val density: Float,
        val expectCentered: Boolean,
    )

    private val matrix = listOf(
        DeviceProfile("720x1280 HD 3-button", 720, 1136, 2.0f, expectCentered = false),
        DeviceProfile("720x1600 HD+ gesture", 720, 1504, 2.0f, expectCentered = false),
        DeviceProfile("1080x2400 FHD+ gesture", 1080, 2262, 2.75f, expectCentered = false),
        DeviceProfile("1440x3200 QHD+ gesture", 1440, 3018, 3.5f, expectCentered = false),
        DeviceProfile("640x1136 Compact 320dp", 640, 1040, 2.0f, expectCentered = false),
        DeviceProfile("1600x2560 Tablet Portrait", 1600, 2464, 2.0f, expectCentered = true),
        DeviceProfile("1200x1920 Tablet Portrait 600dp", 1200, 1824, 2.0f, expectCentered = true),
        DeviceProfile("2560x1600 Tablet Landscape", 2560, 1504, 2.0f, expectCentered = true),
        DeviceProfile("1920x1200 Tablet Landscape", 1920, 1104, 2.0f, expectCentered = true),
        DeviceProfile("2400x1080 Phone Landscape", 2178, 1014, 2.75f, expectCentered = true),
    )

    @Test
    fun allTargetDevicesProduceClipFreeProportionalViewports() {
        for (device in matrix) {
            val vp = ResponsiveLayout.computeAdaptiveViewport(
                safeWidthPx = device.safeWidthPx,
                safeHeightPx = device.safeHeightPx,
                density = device.density,
            )

            assertEquals("${device.label}: centered flag", device.expectCentered, vp.isCenteredColumn)
            assertTrue("${device.label}: hostWidthPx <= safeWidthPx", vp.hostWidthPx <= device.safeWidthPx)
            assertTrue("${device.label}: hostWidthPx positive", vp.hostWidthPx >= 240)
            assertTrue(
                "${device.label}: virtualHeight (${vp.virtualHeight}) >= ${ResponsiveLayout.MIN_VIRTUAL_HEIGHT}",
                vp.virtualHeight >= ResponsiveLayout.MIN_VIRTUAL_HEIGHT - 1f,
            )
            // Header (68) + tallest Seller Hero Dock (112) must leave >= 338 virtual units of visible body.
            val visibleBodyH = vp.virtualHeight - 68f - HERO_TOP_OFFSET
            assertTrue(
                "${device.label}: visibleBodyH ($visibleBodyH) >= 338f",
                visibleBodyH >= 338f,
            )
            val scaleOverDensity = vp.canvasScale / device.density
            assertTrue(
                "${device.label}: scale/density ($scaleOverDensity) must stay <= 1.36x",
                scaleOverDensity <= 1.36f,
            )
        }
    }

    @Test
    fun adaptiveTextScaleHonorsAndroidFontSettingsWithinSafeBounds() {
        assertEquals(1.0f, ResponsiveLayout.adaptiveTextScale(1.0f), 0.0001f)
        val small = ResponsiveLayout.adaptiveTextScale(0.85f)
        assertTrue("small fontScale should shrink gently: $small", small in 0.88f..0.96f)
        val large = ResponsiveLayout.adaptiveTextScale(1.30f)
        assertTrue("large fontScale should grow cleanly: $large", large in 1.10f..1.18f)
        val extreme = ResponsiveLayout.adaptiveTextScale(2.50f)
        assertEquals("extreme fontScale capped at 1.18f to prevent overlap", 1.18f, extreme, 0.0001f)
    }

    @Test
    fun dialogAndOverlayWidthsAdaptFromCompactPhonesToWideTablets() {
        // Compact 720px @ 2.0x (360dp): 92% width = 662px (~331dp)
        val compactDlg = ResponsiveLayout.computeDialogWidthPx(720, 2.0f, 0.92f, 460f)
        assertEquals(662, compactDlg)

        // Tablet Landscape 2560px @ 2.0x (1280dp): capped at 460dp = 920px (not stretched to 2355px!)
        val tabletDlg = ResponsiveLayout.computeDialogWidthPx(2560, 2.0f, 0.92f, 460f)
        assertEquals(920, tabletDlg)

        // Phone 1080px @ 2.75x (~393dp): keeps base 22dp side padding (60.5 -> 61px)
        val phonePad = adaptiveHorizontalPaddingPx(1080, 2.75f, 22f, 500f)
        assertEquals(Math.round(22f * 2.75f), phonePad)

        // Tablet Landscape 2560px @ 2.0x (1280dp): centers 500dp (1000px) -> sidePad = 780px
        val tabletPad = adaptiveHorizontalPaddingPx(2560, 2.0f, 22f, 500f)
        assertEquals(780, tabletPad)
        assertFalse(tabletPad <= 44)
    }

    @Test
    fun accessibleHitTestingExpandsCompactControlsTo48dpWithoutStealingDirectHits() {
        val boxes = listOf(
            // Compact 28x24 stepper buttons separated by 30 units (like Checkout - / +)
            ResponsiveLayout.HitBox("minus", 216f, 100f, 244f, 124f),
            ResponsiveLayout.HitBox("plus", 274f, 100f, 302f, 124f),
            // Compact 32x30 heart icon
            ResponsiveLayout.HitBox("fav", 344f, 200f, 376f, 230f),
        )

        // Direct hits always win immediately
        assertEquals("minus", ResponsiveLayout.resolveAccessibleHit(230f, 112f, boxes))
        assertEquals("plus", ResponsiveLayout.resolveAccessibleHit(288f, 112f, boxes))

        // Slightly outside the 32x30 heart icon (within 48x48 centered at 360,215 -> [336..384, 191..239]) still hits!
        assertEquals("fav", ResponsiveLayout.resolveAccessibleHit(338f, 193f, boxes))

        // Slightly below `minus` (y = 134, within 48dp centered at 112 -> [88..136]) hits `minus`
        assertEquals("minus", ResponsiveLayout.resolveAccessibleHit(230f, 134f, boxes))

        // Far outside any 48dp expanded target returns null
        assertNull(ResponsiveLayout.resolveAccessibleHit(50f, 50f, boxes))
    }
}
