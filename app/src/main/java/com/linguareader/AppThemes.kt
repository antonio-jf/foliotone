package com.linguareader

import android.graphics.Color

data class ReaderTheme(
    val id: String, val name: String, val mood: String,
    val background: Int, val surface: Int, val raised: Int,
    val ink: Int, val muted: Int, val accent: Int, val onAccent: Int,
    val paper: Int, val paperInk: Int, val light: Boolean
)

object AppThemes {
    private fun theme(id: String, name: String, mood: String, light: Boolean,
                      background: String, surface: String, raised: String,
                      ink: String, muted: String, accent: String,
                      paper: String = surface, paperInk: String = ink) = ReaderTheme(
        id, name, mood, Color.parseColor(background), Color.parseColor(surface),
        Color.parseColor(raised), Color.parseColor(ink), Color.parseColor(muted),
        Color.parseColor(accent), Color.parseColor(if (light) "#FFFFFF" else "#101820"),
        Color.parseColor(paper), Color.parseColor(paperInk), light
    )

    val all = listOf(
        theme("folio", "Folio", "Dark · sage & parchment", false, "#151A1C", "#252E30", "#344244", "#F1E9D8", "#B3B7AD", "#64B6A4", "#F1E9D8", "#252E30"),
        theme("polar", "Polar", "Light · ice & blue", true, "#ECF4FA", "#FFFFFF", "#D9E8F3", "#142F46", "#4A6378", "#215E91"),
        theme("candy", "Candy", "Light · pink & raspberry", true, "#FFF0F6", "#FFFFFF", "#F8DAE9", "#49223A", "#78536A", "#A62A66"),
        theme("paper", "Paper", "Light · warm ivory", true, "#F5F0E5", "#FFFCF5", "#E9E0CF", "#342F25", "#6C6252", "#765732"),
        theme("midnight", "Midnight", "Dark · deep blue", false, "#0D1528", "#19253E", "#263755", "#E8EEFA", "#AEBED8", "#91B9FF"),
        theme("ocean", "Ocean", "Dark · teal & seafoam", false, "#0C2229", "#15353E", "#254953", "#E4F5F5", "#A5C6CD", "#6DD6CB"),
        theme("forest", "Forest", "Dark · evergreen", false, "#14231B", "#23382B", "#344D3C", "#EBF3E4", "#B1C5AE", "#A6CF87"),
        theme("lavender", "Lavender", "Light · soft violet", true, "#F1EDFA", "#FEFCFF", "#E5DCF4", "#35264F", "#6E5A85", "#7143A3"),
        theme("rose", "Rose", "Dark · dusty pink", false, "#291C25", "#3D2B37", "#543C49", "#FBEAF1", "#D0AFBF", "#F0A8C6"),
        theme("sunset", "Sunset", "Light · peach & terracotta", true, "#FFF0E7", "#FFFBF5", "#F6DBCB", "#4B2C22", "#805C4D", "#A54024"),
        theme("ember", "Ember", "Dark · charcoal & amber", false, "#251D18", "#382B23", "#4F3C2E", "#FFF0DF", "#CFB69D", "#F6B86B"),
        theme("sand", "Sand", "Light · desert gold", true, "#F6EEDB", "#FFFCF1", "#ECDDAD", "#3E3420", "#716344", "#806014"),
        theme("mint", "Mint", "Light · fresh green", true, "#EAF6EE", "#FBFFFC", "#D1E9DB", "#1D3F30", "#4F705F", "#246C49"),
        theme("sky", "Sky", "Light · clear blue", true, "#EAF5FF", "#FFFFFF", "#D3E9FA", "#173650", "#4B6A83", "#17639B"),
        theme("plum", "Plum", "Dark · purple & lilac", false, "#24182E", "#36243F", "#4D3659", "#F6EAFB", "#C6ACD1", "#D9AAF3"),
        theme("cocoa", "Cocoa", "Dark · chocolate & cream", false, "#261E1C", "#3B2E29", "#534038", "#F9EDE1", "#CBB4A3", "#D9B18D"),
        theme("slate", "Slate", "Dark · cool gray", false, "#1A2028", "#29323E", "#3C4858", "#EDF1F7", "#B0BDCE", "#A9C5E8"),
        theme("mono", "Monochrome", "Light · black & white", true, "#F0F0F0", "#FFFFFF", "#DEDEDE", "#181818", "#595959", "#303030"),
        theme("cherry", "Cherry", "Light · cream & red", true, "#FFF1EE", "#FFFCFA", "#F4DBD6", "#482723", "#7A5550", "#AA302F"),
        theme("lemon", "Lemon", "Light · citrus & olive", true, "#F8F8E2", "#FFFFF4", "#E8E9BE", "#373C1E", "#636A43", "#5E681C")
    )

    fun find(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}
