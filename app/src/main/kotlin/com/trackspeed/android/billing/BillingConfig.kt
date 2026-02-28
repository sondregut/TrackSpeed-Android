package com.trackspeed.android.billing

object BillingConfig {
    // RevenueCat entitlement that grants Pro access
    const val ENTITLEMENT_ID = "Track Speed Pro"

    // Product identifiers (must match Google Play Console subscription:basePlan)
    const val PRODUCT_MONTHLY = "trackspeed_pro:monthly"
    const val PRODUCT_YEARLY = "trackspeed_pro:yearly"

    // RevenueCat package identifiers for special offers
    const val PACKAGE_ANNUAL_DISCOUNT = "annual_discount"   // Spin wheel 20% off
    const val PACKAGE_ANNUAL_REFERRAL = "annual_referral"   // Influencer/referral 30-day trial

    // Free tier limits
    const val FREE_SESSION_LIMIT = 10
    const val FREE_SESSION_WARNING_THRESHOLD = 8

    // Pricing display (fallback if RevenueCat unavailable)
    // These are raw prices WITHOUT period suffixes (period shown separately in UI)
    const val MONTHLY_PRICE_DISPLAY = "$8.99"
    const val YEARLY_PRICE_DISPLAY = "$49.99"
    const val YEARLY_MONTHLY_EQUIVALENT = "$4.17"
    const val YEARLY_SAVINGS_PERCENT = 54
}
