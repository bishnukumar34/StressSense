package com.bishnu.stresswelfarebridge.welltory

/**
 * Builds the documented Welltory request URIs, and reports what should happen when they are opened.
 *
 * The URI construction is pure so the exact documented form can be asserted in unit tests without a
 * device; [AndroidWelltoryLauncher] performs the actual `startActivity`.
 *
 * Per Welltory's documentation the parameters are UTF-8 URL-encoded and appended after the final
 * slash of `welltory://branch/Measurement/Start/`, with `callback` formatted `<package>/<activity>`.
 */
object WelltoryRequestBuilder {

    /**
     * Builds the encoded parameter string.
     *
     * @param callbackComponent `package/activity`, the exported activity that will receive the result.
     * @param nonce the single-use request nonce echoed back on the result Intent.
     * @param accountId the StressSense account that owns this measurement.
     */
    fun params(
        callbackComponent: String,
        nonce: String,
        accountId: String,
        sourceAppName: String = WelltoryConfig.SOURCE_APP_NAME
    ): String {
        // Raw (unencoded) parameter string, matching the documented example.
        val raw = "source=$sourceAppName" +
            "&callback=$callbackComponent" +
            "&${WelltoryConfig.NONCE_PARAM}=$nonce" +
            "&${WelltoryConfig.ACCOUNT_PARAM}=$accountId"
        return urlEncode(raw)
    }

    /** `welltory://branch/Measurement/Start/<encoded>` - the direct link for subsequent requests. */
    fun measurementUri(callbackComponent: String, nonce: String, accountId: String): String =
        WelltoryConfig.MEASUREMENT_SCHEME_URI +
            params(callbackComponent, nonce, accountId)

    /**
     * The Play Store link carrying the same parameters.
     *
     * Per the documentation this single link is what the first request should use: it opens the
     * measurement when Welltory is installed and falls through to the install page when it is not.
     */
    fun playStoreUri(callbackComponent: String, nonce: String, accountId: String): String =
        WelltoryConfig.PLAY_STORE_URI_TEMPLATE.format(
            params(callbackComponent, nonce, accountId)
        )

    /**
     * Percent-encoding for the parameter string.
     *
     * Uses [java.net.URLEncoder] then repairs two of its differences from `application/x-www-form-
     * urlencoded`, which matter inside a URI path segment:
     *  - `+` must become `%20`, since a literal `+` is not a space in a URI path;
     *  - `*`, `~` and `-_.` are left as-is, per RFC 3986's unreserved set.
     */
    fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    /**
     * Picks which URI to open.
     *
     * Follows the documented behaviour: when Welltory can handle the `welltory://` scheme we use the
     * direct link; otherwise we fall back to the Play link, which either installs Welltory or - if
     * the Play app is missing too - simply fails to resolve, which the caller reports honestly.
     *
     * @param schemeResolvable whether any installed activity handles the Welltory scheme.
     */
    fun uriToOpen(
        schemeResolvable: Boolean,
        callbackComponent: String,
        nonce: String,
        accountId: String
    ): String =
        if (schemeResolvable) measurementUri(callbackComponent, nonce, accountId)
        else playStoreUri(callbackComponent, nonce, accountId)

    /** The install link shown when Welltory is absent. */
    fun installUri(): String =
        WelltoryConfig.PLAY_STORE_URI_TEMPLATE.format("")
}