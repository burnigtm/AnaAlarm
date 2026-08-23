package com.anaalarm.ui.avatar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarsTest {

    @Test
    fun `from accepts the three known species`() {
        assertEquals(Avatars.CHEETAH, Avatars.from(Avatars.CHEETAH))
        assertEquals(Avatars.DINO, Avatars.from(Avatars.DINO))
        assertEquals(Avatars.ZEBRA, Avatars.from(Avatars.ZEBRA))
    }

    @Test
    fun `from falls back to default on null or unknown values`() {
        assertEquals(Avatars.DEFAULT, Avatars.from(null))
        assertEquals(Avatars.DEFAULT, Avatars.from(""))
        assertEquals(Avatars.DEFAULT, Avatars.from("dragon"))
        assertEquals(Avatars.DEFAULT, Avatars.from("CHEETAH"))
        assertEquals(Avatars.DEFAULT, Avatars.from("cheetah "))
        assertEquals(Avatars.DEFAULT, Avatars.DEFAULT)
    }

    @Test
    fun `all lists exactly the supported buddies in display order`() {
        assertEquals(listOf(Avatars.CHEETAH, Avatars.DINO, Avatars.ZEBRA), Avatars.ALL)
        assertTrue(Avatars.ALL.contains(Avatars.DEFAULT))
    }

    @Test
    fun `specs are distinct per species`() {
        val cheetah = avatarSpec(Avatars.CHEETAH)
        val dino = avatarSpec(Avatars.DINO)
        val zebra = avatarSpec(Avatars.ZEBRA)

        assertNotEquals(cheetah.body, dino.body)
        assertNotEquals(dino.body, zebra.body)
        assertNotEquals(cheetah.body, zebra.body)
    }

    @Test
    fun `spec lookup is defensive and matches ids`() {
        assertEquals(Avatars.DINO, avatarSpec(Avatars.DINO).id)
        assertEquals(Avatars.ZEBRA, avatarSpec(Avatars.ZEBRA).id)
        // Unknown ids resolve to the default cheetah spec.
        assertEquals(avatarSpec(Avatars.CHEETAH), avatarSpec("unicorn"))
    }

    @Test
    fun `every mood is part of the rig`() {
        assertEquals(
            listOf(
                AvatarMood.SLEEPY,
                AvatarMood.NEUTRAL,
                AvatarMood.TALKING,
                AvatarMood.LISTENING,
                AvatarMood.THINKING,
                AvatarMood.HAPPY,
                AvatarMood.SAD
            ),
            AvatarMood.entries.toList()
        )
    }
}
