package pl.lk.papermind.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lk.papermind.model.Box

class BubblePlacementTest {

    @Test
    fun place_placesBelowAnchorWhenThereIsRoom() {
        val anchor = Box(100f, 200f, 400f, 250f)
        val pos = BubblePlacement.place(
            anchor = anchor,
            w = 200f,
            h = 60f,
            viewW = 1080f,
            viewH = 1920f,
            margin = 10f,
            gap = 5f,
        )
        // Expected y: anchor.bottom + gap = 250 + 5 = 255
        assertEquals(255f, pos.y, 0.001f)
        assertEquals(100f, pos.x, 0.001f)
    }

    @Test
    fun place_placesAboveAnchorWhenNoRoomBelow() {
        val anchor = Box(100f, 1800f, 400f, 1880f)
        val pos = BubblePlacement.place(
            anchor = anchor,
            w = 200f,
            h = 80f,
            viewW = 1080f,
            viewH = 1920f,
            margin = 10f,
            gap = 5f,
            safeBottom = 1900f, // limit below anchor
        )
        // below would be 1880 + 5 + 80 = 1965 > safeBottom (1900-10=1890)
        // above is 1800 - 5 - 80 = 1715 >= margin(10)
        assertEquals(1715f, pos.y, 0.001f)
        assertEquals(100f, pos.x, 0.001f)
    }

    @Test
    fun place_clampsHorizontallyWithinViewBounds() {
        // Anchor near right edge
        val anchor = Box(950f, 200f, 1050f, 250f)
        val pos = BubblePlacement.place(
            anchor = anchor,
            w = 300f,
            h = 60f,
            viewW = 1080f,
            viewH = 1920f,
            margin = 10f,
            gap = 5f,
        )
        // maxX = 1080 - 10 - 300 = 770
        assertEquals(770f, pos.x, 0.001f)
    }

    @Test
    fun place_clampsToMarginWhenAnchorTooFarLeft() {
        val anchor = Box(-50f, 200f, 50f, 250f)
        val pos = BubblePlacement.place(
            anchor = anchor,
            w = 200f,
            h = 60f,
            viewW = 1080f,
            viewH = 1920f,
            margin = 10f,
            gap = 5f,
        )
        assertEquals(10f, pos.x, 0.001f)
    }

    @Test
    fun place_respectsSafeBottomInset() {
        val anchor = Box(100f, 1400f, 400f, 1450f)
        // Suppose bottom sheet peek takes 500px, so safeBottom = 1500
        val pos = BubblePlacement.place(
            anchor = anchor,
            w = 200f,
            h = 80f,
            viewW = 1080f,
            viewH = 1920f,
            margin = 10f,
            gap = 5f,
            safeBottom = 1500f,
        )
        // below = 1450 + 5 = 1455. 1455 + 80 = 1535 > (1500 - 10 = 1490) -> cannot place below
        // above = 1400 - 5 - 80 = 1315
        assertEquals(1315f, pos.y, 0.001f)
    }

    @Test
    fun place_respectsSafeTopInset() {
        val anchor = Box(100f, 20f, 400f, 60f)
        val pos = BubblePlacement.place(
            anchor = anchor,
            w = 200f,
            h = 80f,
            viewW = 1080f,
            viewH = 1920f,
            margin = 10f,
            gap = 5f,
            safeTop = 120f,
        )
        // safeTop = 120f, margin = 10f -> topLimit = 130f
        // below = 60 + 5 = 65, which is < 130f. It must clamp to at least topLimit (130f)
        assertTrue("y should be at least topLimit", pos.y >= 130f)
        assertEquals(130f, pos.y, 0.001f)
    }
}
