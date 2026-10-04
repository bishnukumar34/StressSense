package com.bishnu.stresswelfarebridge.demo

/**
 * The 11 synthetic demonstration accounts shipped with this repository.
 *
 * > **All accounts and all health data in this repository are synthetic demonstration data and do not
 * > represent real individuals.** No name, address, phone number, service record or medical value
 * > here refers to a real person. The credentials below are intentionally published demo credentials
 * > for a research prototype and are **not** production secrets.
 *
 * ### Why these are modelled as ordinary accounts
 * Nothing here is a special login path. Each demo identity is created through the existing
 * [com.bishnu.stresswelfarebridge.auth.AccountStore.register], is stored by the existing
 * [com.bishnu.stresswelfarebridge.auth.PasswordHasher], and is authorised by the existing
 * [com.bishnu.stresswelfarebridge.auth.Session] role checks. That is deliberate: if demo identities
 * took a different route through the auth layer, the demo would demonstrate something other than the
 * shipped application, and the privacy boundary being demonstrated would not be the real one.
 *
 * ### What is seeded
 * Each personnel account receives synthetic physiological observations which are then scored by the
 * **existing deployed model** ([com.bishnu.stresswelfarebridge.ml.StressEstimator], logistic
 * regression `wesad-loso-logreg-v1`). The seeder never writes a stress score directly. See
 * [DemoDataSeeder].
 */
object DemoAccounts {

    const val COMMANDER_USERNAME = "commander@stresssense.demo"
    const val COMMANDER_PASSWORD = "Demo@Commander2026"

    const val PERSONNEL_PASSWORD = "Demo@Personnel2026"
    const val PERSONNEL_USERNAME_PREFIX = "personnel"
    const val PERSONNEL_USERNAME_SUFFIX = "@stresssense.demo"
    const val PERSONNEL_COUNT = 10

    /** Username for the nth personnel account, 1-based: `personnel01@stresssense.demo`. */
    fun personnelUsername(index: Int): String {
        require(index in 1..PERSONNEL_COUNT) { "personnel index out of range: $index" }
        return PERSONNEL_USERNAME_PREFIX + index.toString().padStart(2, '0') + PERSONNEL_USERNAME_SUFFIX
    }

    /** Display name for the nth personnel account, 1-based: `Personnel 01`. */
    fun personnelDisplayName(index: Int): String {
        require(index in 1..PERSONNEL_COUNT) { "personnel index out of range: $index" }
        return "Personnel " + index.toString().padStart(2, '0')
    }

    /**
     * The intended stress-condition group for each personnel index, 1-based.
     *
     * This is **not** a display rule and is never consulted when showing a zone. It exists only so the
     * seeder knows which *synthetic physiological values* to generate, and it is verified afterwards
     * against what the deployed model actually produced. If the model disagrees, the input data is
     * adjusted and the model re-run - the mapping below is never used to override a result.
     *
     * Target distribution: 3 Low, 4 Moderate, 3 High.
     */
    enum class IntendedGroup { LOW, MODERATE, HIGH }

    fun intendedGroup(index: Int): IntendedGroup = when (index) {
        1, 4, 7 -> IntendedGroup.LOW
        2, 5, 8, 10 -> IntendedGroup.MODERATE
        3, 6, 9 -> IntendedGroup.HIGH
        else -> throw IllegalArgumentException("personnel index out of range: $index")
    }

    /** Synthetic age for each personnel index. Fictional, used only for the demo profile line. */
    fun personnelAge(index: Int): Int = when (index) {
        1 -> 22
        2 -> 21
        3 -> 23
        4 -> 22
        5 -> 24
        6 -> 21
        7 -> 23
        8 -> 22
        9 -> 24
        10 -> 21
        else -> throw IllegalArgumentException("personnel index out of range: $index")
    }

    /** Synthetic unit for each personnel index. Fictional. */
    fun personnelUnit(index: Int): String = when (index) {
        1, 4, 9 -> "Operations"
        2, 6 -> "Communications"
        3, 7, 10 -> "Technical"
        5, 8 -> "Logistics"
        else -> throw IllegalArgumentException("personnel index out of range: $index")
    }

    /** Every personnel index, 1..10. */
    val personnelIndices: List<Int> = (1..PERSONNEL_COUNT).toList()

    /**
     * The service id shown on the commander dashboard, e.g. `DEMO-P01`. Clearly marked as demo.
     */
    fun personnelServiceId(index: Int): String =
        "DEMO-P" + index.toString().padStart(2, '0')
}