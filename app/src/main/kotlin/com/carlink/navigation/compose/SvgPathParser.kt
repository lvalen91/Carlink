package com.carlink.navigation.compose

import android.graphics.Path

/**
 * Minimal SVG path-data parser. Accepts the subset Apple's MapKit emits:
 *   M x y       — moveTo absolute
 *   L x y       — lineTo absolute
 *   C x1 y1 x2 y2 x3 y3 — cubic bezier absolute
 *   Z           — closePath
 *
 * Spaces separate tokens. Commands are uppercase only (Apple's output convention). Decimal
 * numbers may be negative. No relative commands, no `H/V/S/T/A/Q` — Apple doesn't use them.
 *
 * Public for testability; called by the [ManeuverComposer] dispatcher to convert the
 * [AppleManeuverPaths] SVG path strings into android.graphics.Path objects.
 */
internal object SvgPathParser {
    fun parse(svgPath: String): Path {
        val path = Path()
        val tokens = svgPath.trim().split(Regex("\\s+"))
        var i = 0
        while (i < tokens.size) {
            when (val cmd = tokens[i]) {
                "M" -> {
                    val x = tokens[i + 1].toFloat()
                    val y = tokens[i + 2].toFloat()
                    path.moveTo(x, y)
                    i += 3
                }
                "L" -> {
                    val x = tokens[i + 1].toFloat()
                    val y = tokens[i + 2].toFloat()
                    path.lineTo(x, y)
                    i += 3
                }
                "C" -> {
                    val x1 = tokens[i + 1].toFloat()
                    val y1 = tokens[i + 2].toFloat()
                    val x2 = tokens[i + 3].toFloat()
                    val y2 = tokens[i + 4].toFloat()
                    val x3 = tokens[i + 5].toFloat()
                    val y3 = tokens[i + 6].toFloat()
                    path.cubicTo(x1, y1, x2, y2, x3, y3)
                    i += 7
                }
                "Z", "z" -> {
                    path.close()
                    i += 1
                }
                else -> throw IllegalArgumentException("Unsupported SVG path command: '$cmd' at token $i")
            }
        }
        return path
    }
}
