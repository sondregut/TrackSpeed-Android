package com.trackspeed.android.billing

object BillingConfig {
    // RevenueCat entitlement that grants Pro access
    const val ENTITLEMENT_ID = "Track Speed Pro"

    // RevenueCat product identifiers. Google Play uses subscription ID trackspeed_pro
    // with base plan IDs weekly/monthly/yearly; RevenueCat exposes them as
    // subscription_id:base_plan_id.
    const val PRODUCT_WEEKLY = "trackspeed_pro:weekly"
    const val PRODUCT_WEEKLY_IOS_FALLBACK = "trackspeed_weekly"
    const val PRODUCT_MONTHLY = "trackspeed_pro:monthly"
    const val PRODUCT_YEARLY = "trackspeed_pro:yearly"

    // RevenueCat package identifiers for special offers
    const val PACKAGE_WEEKLY_DEFAULT = "weekly_default"
    const val PACKAGE_ANNUAL_DEFAULT_FULL = "annual_default_full"
    const val PACKAGE_ANNUAL_DEFAULT_DISCOUNT = "annual_default_discount"
    const val PACKAGE_ANNUAL_DISCOUNT = "annual_discount"   // Legacy Android spin wheel 20% off
    const val PACKAGE_ANNUAL_REFERRAL = "annual_referral"   // Influencer/referral 30-day trial

    // Free tier limits
    const val FREE_SESSION_LIMIT = 10
    const val FREE_SESSION_WARNING_THRESHOLD = 8

    // Pricing display (fallback if RevenueCat unavailable)
    // These are raw prices WITHOUT period suffixes (period shown separately in UI)
    const val WEEKLY_PRICE_DISPLAY = "$7.99"
    const val MONTHLY_PRICE_DISPLAY = "$8.99"
    const val YEARLY_PRICE_DISPLAY = "$59.99"
    const val YEARLY_MONTHLY_EQUIVALENT = "$5.00"
    const val YEARLY_SAVINGS_PERCENT = 86
}
