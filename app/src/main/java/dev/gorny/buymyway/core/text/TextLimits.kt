package dev.gorny.buymyway.core.text

/**
 * The longest text each field may hold. `firebase/database.rules.json` refuses anything longer,
 * so the device trims to the same caps before a change is made: the server never rejects a
 * local change for its shape, which would leave the phone and RTDB disagreeing for good
 * (STATE.md decision 56).
 */
object TextLimits {
    const val LIST_NAME = 100
    const val ITEM_NAME = 200
    const val CATEGORY_NAME = 60
    const val UNIT = 20
    const val NOTE = 2000
}
