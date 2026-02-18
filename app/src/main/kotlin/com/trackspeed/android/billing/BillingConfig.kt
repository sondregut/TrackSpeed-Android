package com.trackspeed.android.billing

object BillingConfig {
    // RevenueCat entitlement that grants Pro access
    const val ENTITLEMENT_ID = "Track Speed Pro"

    // Product identifiers (must match Google Play Console)
    const val PRODUCT_MONTHLY = "trackspeed_pro_monthly"
    const val PRODUCT_YEARLY = "trackspeed_pro_yearly"

    // Free tier limits
    const val FREE_SESSION_LIMIT = 10
    const val FREE_SESSION_WARNING_THRESHOLD = 8

    // Pricing display (fallback if RevenueCat unavailable)
    const val MONTHLY_PRICE_DISPLAY = "$8.99/mo"
    const val YEARLY_PRICE_DISPLAY = "$49.99/yr"
    const val YEARLY_MONTHLY_EQUIVALENT = "$4.17/mo"
    const val YEARLY_SAVINGS_PERCENT = 54
}
