package ir.dastyaremali.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONObject

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parsed = SmsTransactionParser.parseMessages(Telephony.Sms.Intents.getMessagesFromIntent(intent)) ?: return
        val json = JSONObject()
            .put("sender", parsed.sender)
            .put("amount", parsed.amount)
            .put("type", parsed.type)
            .put("receivedAt", parsed.receivedAt)
            .put("rawText", parsed.rawText)
        parsed.balance?.let { json.put("balance", it) }
        parsed.bank?.let { json.put("bank", it) }
        context.getSharedPreferences("dastyar_sms", 0).edit().putString("pending", json.toString()).apply()
    }
}