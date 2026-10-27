// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 界面的公共颜色及背景样式；调整公共外观时优先查看此处。

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native palettes. All text colors track the selected palette, including light styles. */
object TvStyle {
    private var theme = FeatureTheme.DARK
    fun apply(theme: FeatureTheme) { this.theme = theme }
    private data class Palette(val background: Int, val surface: Int, val raised: Int, val accent: Int,
        val text: Int, val muted: Int, val outline: Int, val light: Boolean = false)
    private fun color(hex: String) = Color.parseColor("#$hex")
    private fun dark(bg: String, surface: String, raised: String, accent: String) =
        Palette(color(bg), color(surface), color(raised), color(accent), color("F3F4F6"), color("B6C2D1"), color("657488"))
    private fun light(bg: String, surface: String, raised: String, accent: String) =
        Palette(color(bg), color(surface), color(raised), color(accent), color("172332"), color("42536A"), color("8390A0"), true)
    private val palettes = mapOf(
        FeatureTheme.DARK to dark("0B0F15", "171E28", "202A36", "FF8964"),
        FeatureTheme.BLACK to dark("000000", "0C0C0C", "282828", "FF8964"),
        FeatureTheme.HIGH_CONTRAST to dark("000000", "080808", "464646", "FFFF00"),
        FeatureTheme.NAVY to dark("090E20", "151E36", "26344A", "8DB8FF"),
        FeatureTheme.OCEAN to dark("071B26", "102D3C", "214454", "68D8EF"),
        FeatureTheme.FOREST to dark("0B1914", "162E24", "284638", "8BE0AB"),
        FeatureTheme.PLUM to dark("1B1026", "2B1D39", "42304D", "DAA1FF"),
        FeatureTheme.WINE to dark("200E16", "341A26", "4C2B3A", "FF9FB4"),
        FeatureTheme.COFFEE to dark("19130E", "2D231A", "423629", "E8BC8F"),
        FeatureTheme.SLATE to dark("12191E", "232D34", "36434C", "A4C8DC"),
        FeatureTheme.SUNSET to dark("241210", "371F1A", "4C332B", "FFAA80"),
        FeatureTheme.AMBER to dark("1D1809", "302811", "453C25", "F1D26C"),
        FeatureTheme.TEAL to dark("091D1C", "123330", "284844", "79DED0"),
        FeatureTheme.INDIGO to dark("11122A", "20213D", "353650", "B5B2FF"),
        FeatureTheme.ROSE to dark("24131E", "361E2E", "4B3142", "F3B3D2"),
        FeatureTheme.LIGHT to light("F3F6FA", "FFFFFF", "DEE8F4", "9B3F15"),
        FeatureTheme.PAPER to light("F8F5ED", "FFFDF6", "EAE3D4", "855014"),
        FeatureTheme.MINT to light("EDF7F1", "F9FFFB", "D4E9DD", "12603F"),
        FeatureTheme.SKY to light("EDF5FC", "FAFDFF", "D5E6F3", "185B86"),
        FeatureTheme.LAVENDER to light("F6F0FB", "FFFBFF", "E6DAEF", "6C408C"),
    )
    private val palette get() = palettes.getValue(theme)
    val background get() = palette.background
    val surface get() = palette.surface
    val raised get() = palette.raised
    val accent get() = palette.accent
    val text get() = palette.text
    val muted get() = palette.muted
    val outline get() = palette.outline
    val isLight get() = palette.light
    const val POSTER_ASPECT = .7f

    fun shape(context: Context, color: Int, border: Int = Color.TRANSPARENT, radius: Int = 10) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke((2 * context.resources.displayMetrics.density).toInt(), border)
        }

    fun card(context: Context, focused: Boolean) =
        shape(context, if (focused) raised else surface, if (focused) accent else Color.TRANSPARENT)
}
