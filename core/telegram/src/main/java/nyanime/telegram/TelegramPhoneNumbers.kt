package nyanime.telegram

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

data class TelegramPhoneCountry(val region: String, val callingCode: Int)
data class TelegramPhoneInput(val region: String, val nationalNumber: String)

/** National numbers require the country explicitly selected in the form; never infer it from IP or SIM. */
object TelegramPhoneNumbers {
    private val phoneUtil by lazy { PhoneNumberUtil.getInstance() }
    val countries: List<TelegramPhoneCountry> by lazy {
        phoneUtil.supportedRegions.sorted().map { TelegramPhoneCountry(it, phoneUtil.getCountryCodeForRegion(it)) }
    }

    fun normalize(value: String, region: String? = null): String {
        val input = compact(value)
        val selected = region?.uppercase(Locale.ROOT)
        if (!input.startsWith('+') && selected !in phoneUtil.supportedRegions) invalid()
        val number = try {
            phoneUtil.parse(input, selected)
        } catch (_: NumberParseException) {
            invalid()
        }
        // Possible length, rather than strict carrier validity: Telegram decides whether the account exists.
        if (!phoneUtil.isPossibleNumber(number)) invalid()
        return phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /** A complete pasted international number updates the selector and leaves only the local number in the field. */
    fun splitInternational(value: String): TelegramPhoneInput? {
        return try {
            val input = compact(value)
            if (!input.startsWith('+')) return null
            val number = phoneUtil.parse(input, null)
            if (!phoneUtil.isPossibleNumber(number)) return null
            val region = phoneUtil.getRegionCodeForNumber(number)
                ?.takeIf { it in phoneUtil.supportedRegions }
                ?: phoneUtil.getRegionCodeForCountryCode(number.countryCode)
                    .takeIf { it in phoneUtil.supportedRegions }
                ?: return null
            TelegramPhoneInput(region, phoneUtil.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL))
        } catch (_: NumberParseException) {
            null
        } catch (_: TelegramException) {
            null
        }
    }

    fun example(region: String): String = phoneUtil.getExampleNumberForType(
        region,
        PhoneNumberUtil.PhoneNumberType.MOBILE,
    )
        ?.let { phoneUtil.format(it, PhoneNumberUtil.PhoneNumberFormat.NATIONAL) }.orEmpty()

    private fun compact(value: String): String {
        if (value.length > 256) invalid()
        val input = buildString {
            value.forEach { char ->
                when {
                    char.isDigit() -> append(Character.digit(char, 10))
                    char == '+' -> append(char)
                    char.isWhitespace() || Character.isSpaceChar(char) || char in "().-" -> Unit
                    else -> invalid()
                }
            }
        }.let { if (it.startsWith("00")) "+" + it.drop(2) else it }
        if (!input.matches(Regex("\\+?[0-9]{1,15}"))) invalid()
        return input
    }

    private fun invalid(): Nothing = throw TelegramException(TelegramFailure.PHONE_INVALID)
}
