/*

 This is the source code of exteraGram for Android.

 We do not and cannot prevent the use of our code,
 but be respectful and credit the original author.

 Copyright @exteraGram, 2022-2026

 Licensed under the GNU GPL v. 3 or later, with the additional terms
 in ADDITIONAL_TERMS.md at the root of the repository.

*/

package com.timegram.messenger.inline

import com.timegram.messenger.AngleUnit
import com.timegram.messenger.MeasureSystem
import com.timegram.messenger.inline.math.MathOptions
import com.timegram.messenger.inline.time.TimeZoneOptions
import com.timegram.messenger.inlineAngleUnit
import com.timegram.messenger.inlineCurrencyRates
import com.timegram.messenger.inlineDigitGrouping
import com.timegram.messenger.inlineMathResult
import com.timegram.messenger.inlineMeasureSystem
import com.timegram.messenger.inlineResults
import com.timegram.messenger.inlineTimeZones
import com.timegram.messenger.inlineUnitConversion

import org.telegram.messenger.LocaleController

import java.time.ZoneId

object InlineResults {

    private val imperialRegions = setOf("US", "LR", "MM")

    fun isImperial(): Boolean = when (inlineMeasureSystem) {
        MeasureSystem.AUTO -> LocaleController.getInstance().systemDefaultLocale?.country in imperialRegions
        MeasureSystem.METRIC -> false
        MeasureSystem.IMPERIAL -> true
    }

    fun isEnabled(): Boolean =
        inlineResults && (inlineMathResult || inlineCurrencyRates || inlineUnitConversion || inlineTimeZones)

    fun options(rates: InlineRates, decimalSeparator: Char, allFeatures: Boolean = false): MathOptions {
        val currencies = allFeatures || inlineCurrencyRates
        return MathOptions(
            decimalSeparator,
            if (currencies) rates else null,
            if (currencies) rates.localCurrency() else null,
            allFeatures || inlineMathResult,
            allFeatures || inlineUnitConversion,
            inlineAngleUnit == AngleUnit.DEGREES,
            inlineDigitGrouping,
            if (allFeatures || inlineTimeZones) TimeZoneOptions(ZoneId.systemDefault(), places = InlineZonePlaces::lookup) else null,
            isImperial()
        )
    }
}
