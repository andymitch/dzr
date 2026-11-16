package com.andymitch.dzr.data.repository

import com.google.common.truth.Truth.assertThat
import com.andymitch.dzr.data.repository.UpdateCheckerRepository.Version
import org.junit.jupiter.api.Test

class UpdateCheckerVersionTest {

    @Test
    fun ordersStableAfterItsBetasAndHotfixesAfterStable() {
        val tags = listOf("v0.4.0", "v4-beta.1", "v4-beta.2", "v4", "v4.1", "v5-beta.1", "v5", "v10")
        val versions = tags.map { Version.parse(it)!! }
        assertThat(versions).isInStrictOrder()
    }

    @Test
    fun ignoresOtherPlatformsTags() {
        assertThat(Version.parse("macos-v0.9")).isNull()
        assertThat(Version.parse("android-v0.7".removePrefix("android-"))).isEqualTo(Version.parse("0.7"))
    }

    @Test
    fun treatsMissingComponentsAsZero() {
        assertThat(Version.parse("4")).isEqualTo(Version.parse("v4.0.0"))
    }
}
