package ir.dastyaremali.app

import android.telephony.SmsMessage
import java.util.Locale

data class ParsedSmsTransaction(
    val sender: String,
    val amount: Long,
    val type: String,
    val receivedAt: Long,
    val rawText: String,
    val balance: Long? = null,
    val bank: String? = null
)

object SmsTransactionParser {
    private val amountPatterns = listOf(
        Regex("(?i)(واریز|برداشت|خرید|انتقال|پرداخت|مبلغ)\\s*[:：]?\\s*([0-9۰-۹][0-9۰-۹,٬. ]{2,})\\s*(ریال|تومان)?"),
        Regex("(?i)([0-9۰-۹][0-9۰-۹,٬. ]{3,})\\s*(ریال|تومان)")
    )
    private val balancePattern = Regex("(?i)(?:موجودی|مانده)\\s*[:：]?\\s*([0-9۰-۹][0-9۰-۹,٬. ]+)")

    fun parse(sender: String, body: String, timestamp: Long): ParsedSmsTransaction? {
        val text = body.trim()
        if (text.isBlank()) return null
        val normalized = normalize(text)
        var amount: Long? = null
        for (pattern in amountPatterns) {
            val match = pattern.find(normalized) ?: continue
            val raw = match.groupValues.lastOrNull().orEmpty()
            val unit = if (match.groupValues.size >= 3) match.groupValues[match.groupValues.size - 1] else ""
            val number = toLong(if (pattern == amountPatterns[0]) match.groupValues[2] else match.groupValues[1]) ?: continue
            amount = if (unit == "تومان") number * 10L else number
            if (amount > 0) break
        }
        val finalAmount = amount ?: return null
        val lower = normalized.lowercase(Locale.ROOT)
        val type = when {
            lower.contains("واریز") || lower.contains("دریافت") || lower.contains("بستانکار") -> "INCOME"
            lower.contains("برداشت") || lower.contains("خرید") || lower.contains("پرداخت") || lower.contains("بدهکار") || lower.contains("انتقال وجه") -> "EXPENSE"
            else -> return null
        }
        val balance = balancePattern.find(normalized)?.groupValues?.getOrNull(1)?.let(::toLong)
        return ParsedSmsTransaction(sender, finalAmount, type, timestamp, text, balance, detectBank(sender, text))
    }

    fun parseMessages(messages: Array<SmsMessage>): ParsedSmsTransaction? {
        if (messages.isEmpty()) return null
        return parse(
            messages.first().originatingAddress.orEmpty(),
            messages.joinToString(" ") { it.messageBody.orEmpty() },
            messages.first().timestampMillis
        )
    }

    private fun normalize(value: String): String = value.map { c ->
        when (c) {
            in '۰'..'۹' -> ('0'.code + c.code - '۰'.code).toChar()
            in '٠'..'٩' -> ('0'.code + c.code - '٠'.code).toChar()
            '٬' -> ','
            else -> c
        }
    }.joinToString("")

    private fun toLong(value: String): Long? =
        value.replace(",", "").replace(".", "").replace(" ", "").toLongOrNull()

    private fun detectBank(sender: String, text: String): String? {
        val s = (sender + " " + text).lowercase(Locale.ROOT)
        val banks = listOf(
            "ملت" to "ملت", "mellat" to "ملت", "ملی" to "ملی", "melli" to "ملی",
            "صادرات" to "صادرات", "saderat" to "صادرات", "تجارت" to "تجارت", "tejarat" to "تجارت",
            "رفاه" to "رفاه", "پاسارگاد" to "پاسارگاد", "سامان" to "سامان", "پارسیان" to "پارسیان",
            "کشاورزی" to "کشاورزی", "مسکن" to "مسکن", "مهر ایران" to "مهر ایران", "رسالت" to "رسالت",
            "ایران زمین" to "ایران زمین", "آینده" to "آینده", "اقتصاد نوین" to "اقتصاد نوین"
        )
        return banks.firstOrNull { s.contains(it.first.lowercase(Locale.ROOT)) }?.second
    }
}