package com.example.limbuszhcn

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconManagerTest {

    private class FakeComponentStateStore(
        initialStates: Map<String, Int> = emptyMap()
    ) : ComponentStateStore {
        val states = initialStates.toMutableMap()
        val operations = mutableListOf<Pair<String, Boolean>>()

        override fun getState(aliasClassName: String): Int =
            states[aliasClassName] ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT

        override fun setEnabled(aliasClassName: String, enabled: Boolean) {
            operations += aliasClassName to enabled
            states[aliasClassName] = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        }
    }

    @Test
    fun `presets have unique ids aliases drawables and single default`() {
        val presets = AppIconPreset.entries
        assertEquals(14, presets.size)
        assertEquals(
            listOf(
                "pwie",
                "dante",
                "yi_sang",
                "faust",
                "don_quixote",
                "ryoshu",
                "meursault",
                "hong_lu",
                "heathcliff",
                "ishmael",
                "rodya",
                "sinclair",
                "outis",
                "gregor"
            ),
            presets.map { it.id }
        )
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        assertEquals(presets.size, presets.map { it.aliasClassName }.toSet().size)
        assertEquals(presets.size, presets.map { it.iconRes }.toSet().size)
        assertEquals(1, presets.count { it.isDefault })
        assertEquals(AppIconPreset.PWIE, presets.single { it.isDefault })
        assertEquals(AppIconPreset.PWIE, AppIconPreset.defaultPreset)
    }

    @Test
    fun `initial default manifest state resolves to Pwie and ignores redundant switch`() {
        val store = FakeComponentStateStore()
        assertEquals(AppIconPreset.PWIE, AppIconManager.currentPreset(store))
        assertFalse(AppIconManager.applyPreset(store, AppIconPreset.PWIE))
        assertTrue(store.operations.isEmpty())
    }

    @Test
    fun `switching to sinners enables target alias before disabling others`() {
        val store = FakeComponentStateStore()

        assertTrue(AppIconManager.applyPreset(store, AppIconPreset.YI_SANG))
        assertEquals(AppIconPreset.YI_SANG, AppIconManager.currentPreset(store))
        assertEquals(
            AppIconPreset.YI_SANG.aliasClassName to true,
            store.operations.first()
        )
        val disabledAliases = store.operations.drop(1)
        assertEquals(AppIconPreset.entries.size - 1, disabledAliases.size)
        assertTrue(disabledAliases.all { !it.second })
        assertFalse(disabledAliases.any { it.first == AppIconPreset.YI_SANG.aliasClassName })

        // 切换到格里高尔，同样应先启用目标别名并保留其余 13 项禁用顺序与幂等性
        store.operations.clear()
        assertTrue(AppIconManager.applyPreset(store, AppIconPreset.GREGOR))
        assertEquals(AppIconPreset.GREGOR, AppIconManager.currentPreset(store))
        assertEquals(AppIconPreset.GREGOR.aliasClassName to true, store.operations.first())
        assertEquals(AppIconPreset.entries.size - 1, store.operations.drop(1).size)
        assertFalse(AppIconManager.applyPreset(store, AppIconPreset.GREGOR))

        // 再切换回当期默认噗咿
        store.operations.clear()
        assertTrue(AppIconManager.applyPreset(store, AppIconPreset.PWIE))
        assertEquals(AppIconPreset.PWIE, AppIconManager.currentPreset(store))
        assertEquals(
            AppIconPreset.PWIE.aliasClassName to true,
            store.operations.first()
        )
    }

    @Test
    fun `reconcileAliasStates disables newly introduced default alias when previous choice is active`() {
        val store = FakeComponentStateStore(
            initialStates = mapOf(
                AppIconPreset.FAUST.aliasClassName to PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                AppIconPreset.DANTE.aliasClassName to PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            )
        )

        assertTrue(AppIconManager.reconcileAliasStates(store))
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            store.getState(AppIconPreset.PWIE.aliasClassName)
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            store.getState(AppIconPreset.FAUST.aliasClassName)
        )
        assertEquals(AppIconPreset.FAUST, AppIconManager.currentPreset(store))
        assertFalse(AppIconManager.reconcileAliasStates(store))
    }
}
