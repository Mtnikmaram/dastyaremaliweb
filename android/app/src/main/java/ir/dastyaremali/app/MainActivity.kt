package ir.dastyaremali.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

private const val API = "https://dastyarfinance.ir"
private const val PREF = "dastyar_auth"

private suspend fun uploadReceipt(context: Context, path: String, uri: android.net.Uri): String =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("فایل فیش قابل خواندن نیست.")
        if (bytes.size > 8 * 1024 * 1024) throw Exception("حجم فیش نباید بیشتر از ۸ مگابایت باشد.")
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val boundary = "----DastyarReceipt" + System.currentTimeMillis()
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer " + (context.token() ?: ""))
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary)
        c.outputStream.use { out ->
            fun w(v: String) { out.write(v.toByteArray(Charsets.UTF_8)) }
            w("--" + boundary + "\r\n")
            w("Content-Disposition: form-data; name=\"receipt_file\"; filename=\"receipt.jpg\"\r\n")
            w("Content-Type: " + mime + "\r\n\r\n")
            out.write(bytes)
            w("\r\n--" + boundary + "--\r\n")
        }
        val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299) {
            val detail = try { JSONObject(response).optString("detail") } catch (_: Exception) { "" }
            throw Exception(detail.ifBlank { "ارسال فیش انجام نشد." })
        }
        response
    }


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 1001)
        }
        setContent { DastyarApp() }
    }
}

private fun Context.prefs() = getSharedPreferences(PREF, 0)
private fun Context.token(): String? = prefs().getString("access", null)
private fun Context.refresh(): String? = prefs().getString("refresh", null)
private fun Context.saveTokens(access: String, refresh: String?) {
    prefs().edit().putString("access", access).apply { if (refresh != null) putString("refresh", refresh) }.apply()
}
private fun Context.logout() = prefs().edit().clear().apply()

private suspend fun rawCall(context: Context, token: String?, path: String, method: String, body: String?): Pair<Int,String> =
    withContext(Dispatchers.IO) {
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        Pair(c.responseCode, text)
    }

private suspend fun call(context: Context, path: String, method: String = "GET", body: String? = null): String {
    var (code, text) = rawCall(context, context.token(), path, method, body)
    if (code == 401 && context.refresh() != null) {
        val rr = JSONObject().put("refresh", context.refresh()).toString()
        val (rc, rt) = rawCall(context, null, "/api/auth/token/refresh/", "POST", rr)
        if (rc in 200..299) {
            val nr = JSONObject(rt).optString("access")
            if (nr.isNotBlank()) {
                context.saveTokens(nr, null)
                code to text
                val result = rawCall(context, nr, path, method, body)
                code = result.first; text = result.second
            }
        }
    }
    if (code !in 200..299) {
        val msg = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
        throw IllegalStateException(if (msg.isBlank()) "خطا در ارتباط با سرور ($code)" else msg)
    }
    return text
}

private fun fa(s: String): String = s.map { if (it in '0'..'9') ('۰'.code + (it - '0')).toChar() else it }.joinToString("")

private fun money(v: Any?): String {
    val raw = v?.toString()?.replace(",", "")?.trim() ?: "0"
    val n = raw.toDoubleOrNull()?.toLong() ?: 0L
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n) + " ریال"
}
private fun digitsOnly(s: String) = s.filter(Char::isDigit)
private fun formatInputMoney(s: String): String {
    val digits = digitsOnly(s).trimStart('0').ifBlank { "0" }
    return NumberFormat.getNumberInstance(Locale.US).format(digits.toLongOrNull() ?: 0L)
}
private fun parseMoney(s: String) = digitsOnly(s).toLongOrNull() ?: 0L

private fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int,Int,Int> {
    val gdm = intArrayOf(0,31,28,31,30,31,30,31,31,30,31,30,31)
    val jdm = intArrayOf(0,31,31,31,31,31,30,30,30,30,30,29,29)
    var gy2=gy-1600; var jy=979; var days=365*gy2+(gy2+3)/4-(gy2+99)/100+(gy2+399)/400
    for(i in 1 until gm) days += gdm[i]
    if(gm>2 && (gy%4==0 && gy%100!=0 || gy%400==0)) days++
    days += gd-1
    jy += 33*(days/12053); var r=days%12053
    jy += 4*(r/1461); r%=1461
    if(r>365){ jy += (r-1)/365; r=(r-1)%365 }
    val jm = if(r<186) 1+r/31 else 7+(r-186)/30
    val jd = 1 + if(r<186) r%31 else (r-186)%30
    return Triple(jy,jm,jd)
}
private fun jalaliToGregorian(jy0:Int,jm:Int,jd:Int): Triple<Int,Int,Int> {
    var jy=jy0-979; var days=365*jy+(jy/33)*8+((jy%33)+3)/4
    for(i in 1 until jm) days += if(i<=6)31 else 30
    days += jd-1
    var gy=1600+400*(days/146097); var r=days%146097
    if(r>=36525){ gy += 100*(--r/36524); r%=36524; if(r>=365) r++ }
    gy += 4*(r/1461); r%=1461
    if(r>=366){ gy += (r-1)/365; r=(r-1)%365 }
    val gd=r+1
    val gmLens=intArrayOf(31,if(gy%4==0&&gy%100!=0||gy%400==0)29 else 28,31,30,31,30,31,31,30,31,30,31)
    var gm=0; var rem=gd
    while(rem>gmLens[gm]){ rem-=gmLens[gm]; gm++ }
    return Triple(gy,gm+1,rem)
}
private fun todayJalali(): String { val c=Calendar.getInstance(); val j=gregorianToJalali(c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH)); return "%04d/%02d/%02d".format(j.first,j.second,j.third) }
private fun jalaliToApi(s:String): String {
    val p=s.replace("-","/").split("/").mapNotNull{it.toIntOrNull()}
    if(p.size!=3) return todayApi()
    val g=jalaliToGregorian(p[0],p[1],p[2]); return "%04d-%02d-%02d".format(g.first,g.second,g.third)
}
private fun todayApi(): String { val c=Calendar.getInstance(); return "%04d-%02d-%02d".format(c.get(Calendar.YEAR),c.get(Calendar.MONTH)+1,c.get(Calendar.DAY_OF_MONTH)) }
private fun timestampToJalali(timestamp: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = timestamp }
    val j = gregorianToJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    return "%04d/%02d/%02d".format(j.first, j.second, j.third)
}
private fun pendingSms(context: Context): JSONObject? = try {
    context.getSharedPreferences("dastyar_sms", 0).getString("pending", null)?.let { JSONObject(it) }
} catch (_: Exception) { null }
private fun clearPendingSms(context: Context) {
    context.getSharedPreferences("dastyar_sms", 0).edit().remove("pending").apply()
}
private fun apiToJalali(s:String): String { val p=s.split("-").mapNotNull{it.toIntOrNull()}; if(p.size!=3)return s; val j=gregorianToJalali(p[0],p[1],p[2]); return "%04d/%02d/%02d".format(j.first,j.second,j.third) }
private fun JSONArray.toObjects(): List<JSONObject> = List(length()) { getJSONObject(it) }

@Composable
fun DastyarApp() {
    val context = LocalContext.current
    var logged by remember { mutableStateOf(context.token() != null) }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0F766E))) {
            if (logged) {
                AppShell {
                    context.logout()
                    logged = false
                }
            } else {
                LoginScreen { logged = true }
            }
        }
    }
}

@Composable
fun LoginScreen(done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("دستیار مالی", style = MaterialTheme.typography.headlineMedium)
        Text(if (register) "ساخت حساب جدید" else "ورود به حساب")
        Spacer(Modifier.height(20.dp))
        if (register) TextField(name, { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth())
        TextField(user, { user = it }, label = { Text("نام کاربری") }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("گذرواژه") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "مخفی" else "نمایش")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        if (register) {
            OutlinedTextField(
                value = mobile,
                onValueChange = { mobile = it.filter(Char::isDigit).take(11) },
                label = { Text("شماره موبایل") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true; error = ""
                try {
                    if (register) {
                        if (mobile.length != 11 || !mobile.startsWith("09")) throw IllegalStateException("شماره موبایل معتبر وارد کنید.")
                        call(context, "/api/auth/register/", "POST",
                            JSONObject().put("username", user.trim()).put("password", pass).put("first_name", name.trim()).put("mobile", mobile).toString())
                    }
                    val r = JSONObject(call(context, "/api/auth/token/", "POST",
                        JSONObject().put("username", user.trim()).put("password", pass).toString()))
                    context.saveTokens(r.getString("access"), r.optString("refresh").ifBlank { null }); done()
                } catch (e: Exception) { error = e.message ?: "خطا" }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "در حال انجام..." else if (register) "ساخت حساب" else "ورود") }
        if (!register) {
            TextButton(onClick = { showReset = true }, modifier = Modifier.fillMaxWidth()) { Text("بازیابی رمز عبور") }
        }
        TextButton(onClick = { register = !register; error = ""; showReset = false }, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "ورود به حساب" else "ساخت حساب جدید")
        }
        if (showReset) PasswordResetDialog(context, onClose = { showReset = false })
    }
}

@Composable
fun AppShell(onLogout: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    var quickTransactionType by remember { mutableStateOf<String?>(null) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("خانه" to "⌂","تراکنش‌ها" to "▤","تعهدات" to "♢","پیامک‌ها" to "✉","اشتراک" to "♛","تنظیمات" to "⚙")
                .forEachIndexed { i, item ->
                    NavigationBarItem(selected = page == i, onClick = { page = i; if (i != 1) quickTransactionType = null },
                        icon = { Text(item.second, style = MaterialTheme.typography.titleMedium) }, label = { Text(item.first) })
                }
        }
    }) { p ->
        when (page) {
            1 -> Transactions(Modifier.padding(p), quickTransactionType, { quickTransactionType = null })
            2 -> Loans(Modifier.padding(p))
            3 -> SmsTransactions(Modifier.padding(p))
            4 -> SubscriptionScreen(Modifier.padding(p))
            5 -> SettingsScreen(Modifier.padding(p), onLogout)
            else -> Home(Modifier.padding(p), onLogout,
                onOpenTransaction = { type -> quickTransactionType = type; page = 1 },
                onOpenPage = { target -> page = target; quickTransactionType = null })
        }
    }
}

@Composable
private fun SimpleSectionScreen(modifier: Modifier, title: String, message: String) {
    Column(modifier.fillMaxSize().background(Color(0xFFF7F9FC)).padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFF111B4D), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Text(message, Modifier.padding(20.dp), color = Color(0xFF68738A))
        }
    }
}

@Composable
private fun SubscriptionScreen(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf(listOf<JSONObject>()) }
    var current by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(0) }
    var selectedPlan by remember { mutableStateOf<JSONObject?>(null) }
    var purchase by remember { mutableStateOf<JSONObject?>(null) }
    var receipt by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var discount by remember { mutableStateOf("") }

    val picker = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        receipt = it
    }

    LaunchedEffect(Unit) {
        try {
            val result = JSONObject(call(context, "/api/subscription/plans/"))
            plans = result.optJSONArray("plans")?.toObjects() ?: emptyList()
            val me = JSONObject(call(context, "/api/subscription/me/"))
            current = if (me.optBoolean("active")) me.optJSONObject("subscription") else null
        } catch (e: Exception) { error = e.message ?: "خطا در دریافت پلن‌ها" }
        finally { loading = false }
    }

    val navy = Color(0xFF111B4D)
    val green = Color(0xFF079B62)
    val darkGreen = Color(0xFF087E62)
    val pale = Color(0xFFE9FAF3)
    val line = Color(0xFFE4E8EE)
    val muted = Color(0xFF68738A)
    val orange = Color(0xFFF0A51A)
    val blue = Color(0xFF2583E8)
    val bg = Color(0xFFF7FBFA)

    fun priceRial(p: JSONObject): Any {
        val raw = p.opt("price")
        val n = if (raw is Number) raw.toDouble() else raw?.toString()?.toDoubleOrNull() ?: 0.0
        return n * 10.0
    }
    fun isCurrent(p: JSONObject) = current?.optJSONObject("plan")?.optString("code") == p.optString("code")
    fun back() { page = if (page > 0) page - 1 else 0 }

    fun startPurchase(p: JSONObject) {
        selectedPlan = p
        page = 1
    }

    fun createPurchase() {
        val p = selectedPlan ?: return
        scope.launch {
            busy = true
            try {
                purchase = JSONObject(call(context, "/api/subscription/purchase/", "POST",
                    JSONObject().put("plan_id", p.optInt("id")).toString()))
                page = 2
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, e.message ?: "ثبت درخواست انجام نشد", android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }

    fun sendReceipt() {
        val p = purchase ?: return
        val uri = receipt ?: run {
            android.widget.Toast.makeText(context, "لطفاً تصویر فیش را انتخاب کنید.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busy = true
            try {
                uploadReceipt(context, "/api/subscription/purchase/" + p.optInt("id") + "/receipt/", uri)
                page = 3
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, e.message ?: "ارسال فیش انجام نشد", android.widget.Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }

    if (loading) {
        Box(modifier.fillMaxSize().background(bg), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(color = green)
        }
        return
    }
    if (error.isNotBlank()) {
        Column(modifier.fillMaxSize().background(bg).padding(20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("اشتراک", color = navy, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text(error, Modifier.padding(20.dp), color = Color(0xFFDC3030))
            }
        }
        return
    }

    val ordered = plans.sortedWith(compareBy { p ->
        when (p.optString("code")) { "free" -> 0; "basic" -> 1; "pro" -> 2; else -> 3 }
    })

    if (page == 0) {
        LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(12.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("اشتراک", Modifier.fillMaxWidth(), color = navy, style = MaterialTheme.typography.headlineMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = green)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("دستیار مالی حرفه‌ای‌تر باش!", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        Text("با اشتراک، امکانات بیشتر و گزارش‌های پیشرفته در اختیار شماست.",
                            color = Color.White.copy(alpha = .92f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("♛", color = Color(0xFFFFD34E), style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { p ->
                        val code = p.optString("code")
                        val pro = code == "pro"
                        val basic = code == "basic"
                        val accent = if (pro) orange else if (basic) green else Color(0xFF9DA7B8)
                        val tint = if (pro) Color(0xFFFFF7DE) else if (basic) pale else Color(0xFFF3F5F8)
                        Card(Modifier.weight(1f), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(if (pro) 2.dp else 1.dp, if (pro) green else line)) {
                            Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (pro) Surface(color = darkGreen, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                                    Text("محبوب‌ترین", Modifier.padding(vertical = 5.dp), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                } else Spacer(Modifier.height(25.dp))
                                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    Surface(shape = MaterialTheme.shapes.large, color = tint) {
                                        Text("♛", Modifier.padding(9.dp), color = accent, style = MaterialTheme.typography.headlineMedium)
                                    }
                                }
                                Text(p.optString("name"), Modifier.fillMaxWidth(), color = navy, style = MaterialTheme.typography.titleLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Text(p.optString("description").ifBlank { if (pro) "مناسب برای کنترل کامل مالی" else if (basic) "مناسب برای مدیریت بهتر" else "مناسب برای شروع" },
                                    Modifier.fillMaxWidth().heightIn(min = 40.dp), color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall)
                                Surface(shape = MaterialTheme.shapes.medium, color = tint, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                        Text(if (p.optDouble("price", 0.0) <= 0) "۰" else money(priceRial(p)),
                                            color = if (pro || basic) darkGreen else navy, style = MaterialTheme.typography.titleLarge)
                                        Text("ریال / ماه", color = muted, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                PlanFeature("تعداد حساب‌ها: " + fa(p.optInt("max_accounts").toString()), true, if (code == "free") green else accent)
                                PlanFeature("تراکنش ماهانه: " + fa(p.optInt("max_transactions_per_month").toString()), true, if (code == "free") green else accent)
                                PlanFeature("مدیریت وام و اقساط", p.optBoolean("advanced_loans_enabled"), accent)
                                PlanFeature("دستیار هوشمند (AI)", p.optBoolean("ai_enabled"), accent)
                                if (p.optBoolean("ai_enabled")) PlanFeature("درخواست ماهانه AI: " + fa(p.optInt("ai_requests_per_month").toString()), true, accent)
                                PlanFeature("گزارش‌های پیشرفته", pro || basic, accent)
                                PlanFeature("خروجی گزارش (PDF/Excel)", pro, accent)
                                PlanFeature("مقایسه ماه به ماه", pro, accent)
                                Button(onClick = { if (code != "free") startPurchase(p) }, enabled = code != "free" && !isCurrent(p),
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (pro) darkGreen else Color.White,
                                        contentColor = if (pro) Color.White else darkGreen,
                                        disabledContainerColor = Color(0xFFEFF1F5), disabledContentColor = muted),
                                    border = if (!pro && code != "free" && !isCurrent(p)) androidx.compose.foundation.BorderStroke(1.dp, darkGreen) else null) {
                                    Text(if (isCurrent(p)) "پلن فعلی شما" else if (code == "free") "فعلی" else "مشاهده جزئیات و خرید")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, line), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Surface(shape = MaterialTheme.shapes.large, color = Color(0xFFF0E9FF)) { Text("🎁", Modifier.padding(12.dp)) }
                        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("کد تخفیف دارید؟", color = navy, style = MaterialTheme.typography.labelLarge)
                            Text("کد تخفیف خود را وارد کنید.", color = muted, style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedTextField(discount, { discount = it }, Modifier.weight(1.2f), label = { Text("کد تخفیف") }, singleLine = true)
                        Button(onClick = { android.widget.Toast.makeText(context, if (discount.isBlank()) "کد تخفیف را وارد کنید." else "کد تخفیف در حال بررسی است.", android.widget.Toast.LENGTH_SHORT).show() },
                            colors = ButtonDefaults.buttonColors(containerColor = darkGreen)) { Text("اعمال") }
                    }
                }
            }
        }
    } else if (page == 1) {
        val p = selectedPlan ?: return
        LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TopBackTitle("جزئیات اشتراک", ::back, navy) }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = pale), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("⭐", style = MaterialTheme.typography.displaySmall)
                        Text("اشتراک " + p.optString("name"), color = navy, style = MaterialTheme.typography.headlineSmall)
                        Text(money(priceRial(p)) + " ریال", color = navy, style = MaterialTheme.typography.headlineMedium)
                        Text("به‌صورت ماهانه", color = muted)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Text("امکانات این پلن", color = navy, style = MaterialTheme.typography.titleLarge)
                        PlanFeature("تمام امکانات نسخه رایگان", true, green)
                        PlanFeature("گزارش‌های پیشرفته و نمودارها", true, green)
                        PlanFeature("مدیریت کامل تعهدات و اقساط", true, green)
                        PlanFeature("پشتیبان‌گیری از اطلاعات", true, green)
                        PlanFeature("دسترسی به امکانات تکمیلی اپلیکیشن", true, green)
                        PlanFeature("به‌روزرسانی‌های منظم", true, green)
                        PlanFeature("پشتیبانی از طریق تلگرام و ایمیل", true, green)
                    }
                }
            }
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FF)), shape = MaterialTheme.shapes.large) {
                Text("بعد از پرداخت و ارسال فیش، درخواست شما بررسی و اشتراک فعال خواهد شد. معمولاً این فرایند طی چند ساعت انجام می‌شود.", Modifier.padding(15.dp), color = Color(0xFF245A93))
            } }
            item { Button(onClick = { createPurchase() }, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = darkGreen), shape = MaterialTheme.shapes.large) {
                Text(if (busy) "در حال ثبت درخواست..." else "ادامه و پرداخت")
            } }
        }
    } else if (page == 2) {
        val p = selectedPlan ?: return
        LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TopBackTitle("پرداخت و ارسال فیش", ::back, navy) }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = pale), shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("⭐", style = MaterialTheme.typography.headlineMedium)
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("اشتراک " + p.optString("name"), color = navy, style = MaterialTheme.typography.titleLarge)
                            Text(money(priceRial(p)) + " ریال", color = navy, style = MaterialTheme.typography.titleLarge)
                            Text("به‌صورت ماهانه", color = muted)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("➊  واریز مبلغ به شماره کارت", color = navy, style = MaterialTheme.typography.titleLarge)
                        Text("لطفاً مبلغ " + money(priceRial(p)) + " ریال را به شماره کارت زیر واریز کنید.", color = muted)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Button(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("شماره کارت", "6219••••••••3892"))
                                android.widget.Toast.makeText(context, "شماره کارت کپی شد.", android.widget.Toast.LENGTH_SHORT).show()
                            }, colors = ButtonDefaults.buttonColors(containerColor = darkGreen)) { Text("کپی") }
                            Text("۶۲۱۹ •••• •••• ۳۸۹۲", color = navy, style = MaterialTheme.typography.titleMedium)
                        }
                        HorizontalDivider(color = line)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("بانک", color = muted); Text("بلو", color = navy) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("به نام", color = muted); Text("صاحب حساب", color = navy) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("➋  ارسال تصویر فیش واریزی", color = navy, style = MaterialTheme.typography.titleLarge)
                        Text("بعد از پرداخت، تصویر فیش واریزی را در این بخش بارگذاری کنید.", color = muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        OutlinedButton(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (receipt == null) "↑  انتخاب تصویر فیش" else "✓  فیش انتخاب شد")
                        }
                        Text("JPG، PNG، PDF مجاز • حداکثر حجم: ۸ مگابایت", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Button(onClick = { sendReceipt() }, enabled = !busy && receipt != null, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = darkGreen), shape = MaterialTheme.shapes.large) {
                Text(if (busy) "در حال ارسال..." else "ارسال فیش به بررسی درخواست")
            } }
        }
    } else {
        LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TopBackTitle("درخواست ارسال شد", ::back, navy) }
            item {
                Column(Modifier.fillMaxWidth().padding(top = 45.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = pale) { Text("✓", Modifier.padding(24.dp), color = green, style = MaterialTheme.typography.displayLarge) }
                    Spacer(Modifier.height(18.dp))
                    Text("درخواست شما ثبت شد", color = navy, style = MaterialTheme.typography.headlineSmall)
                    Text("تصویر فیش واریزی با موفقیت ارسال شد.", color = muted, modifier = Modifier.padding(top = 7.dp))
                }
            }
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = pale), shape = MaterialTheme.shapes.large) {
                Text("درخواست شما در حال بررسی است. پس از تأیید پرداخت، اشتراک شما فعال خواهد شد. معمولاً این فرایند طی چند ساعت انجام می‌شود.", Modifier.padding(18.dp), color = Color(0xFF245A6A),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } }
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("نوع اشتراک", color = muted); Text(selectedPlan?.optString("name") ?: "اشتراک", color = navy, style = MaterialTheme.typography.titleMedium)
                    Text("مبلغ", color = muted); Text(if (selectedPlan != null) money(priceRial(selectedPlan!!)) + " ریال" else "-", color = navy, style = MaterialTheme.typography.titleMedium)
                    Text("تاریخ درخواست", color = muted); Text(todayJalali(), color = navy)
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFFFF1C9)) { Text("⏱ در حال بررسی", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = Color(0xFF8A6500)) }
                }
            } }
            item { Button(onClick = { page = 0 }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = darkGreen), shape = MaterialTheme.shapes.large) {
                Text("بازگشت به اشتراک‌ها")
            } }
        }
    }
}

@Composable
private fun TopBackTitle(title: String, onBack: () -> Unit, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("‹", color = color, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clickable { onBack() })
        Text(title, color = color, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(28.dp))
    }
}

@Composable
private fun PlanFeature(text: String, enabled: Boolean, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (enabled) "●" else "×", color = if (enabled) accent else Color(0xFFB8BEC8), style = MaterialTheme.typography.labelSmall)
        Text(text, Modifier.weight(1f), color = if (enabled) Color(0xFF17214D) else Color(0xFF68738A),
            style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, onLogout: () -> Unit) {
    val context = LocalContext.current
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var pendingCount by remember { mutableStateOf(0) }
    var darkMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { profile = JSONObject(call(context, "/api/auth/profile/")) } catch (_: Exception) {}
        pendingCount = if (pendingSms(context) != null) 1 else 0
    }

    val bg = Color(0xFFF7F9FC)
    val navy = Color(0xFF111B4D)
    val muted = Color(0xFF68738A)
    val green = Color(0xFF0F9F6E)
    val paleGreen = Color(0xFFE9F8F0)
    val blue = Color(0xFF1877E8)
    val orange = Color(0xFFF09A17)
    val purple = Color(0xFF6B28D9)
    val red = Color(0xFFDC3030)

    fun displayName(): String =
        profile?.optString("first_name").orEmpty().ifBlank {
            profile?.optString("username").orEmpty().ifBlank { "کاربر" }
        }

    LazyColumn(
        modifier.fillMaxSize().background(bg),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("تنظیمات", color = navy, style = MaterialTheme.typography.headlineSmall)
                Text("♧", color = red, style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                "مدیریت حساب و تنظیمات برنامه",
                Modifier.fillMaxWidth(),
                color = muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F5FF)), shape = MaterialTheme.shapes.large) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = paleGreen, contentColor = green),
                        shape = MaterialTheme.shapes.extraLarge
                    ) { Text("ویرایش اطلاعات  ✎") }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text(displayName(), color = navy, style = MaterialTheme.typography.titleLarge)
                        Text(profile?.optString("mobile").orEmpty().ifBlank { "شماره موبایل ثبت نشده" }, color = muted)
                        Text(profile?.optString("email").orEmpty().ifBlank { "ایمیل ثبت نشده" }, color = muted)
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF9F2)), shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = green), shape = MaterialTheme.shapes.extraLarge) {
                        Text("مدیریت اشتراک", color = Color.White)
                    }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text("وضعیت اشتراک", color = navy, style = MaterialTheme.typography.titleLarge)
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = green) {
                            Text("رایگان  ♛", Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = Color.White)
                        }
                        Text("دسترسی به امکانات پایه", color = muted, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }

        item { Text("تنظیمات حساب", Modifier.fillMaxWidth(), color = navy, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
        item {
            SettingsGroup(
                rows = listOf(
                    Triple("اطلاعات حساب کاربری", "مشاهده و ویرایش اطلاعات شخصی", blue),
                    Triple("تغییر رمز عبور", "به‌روزرسانی رمز عبور حساب", orange),
                    Triple("اعلان‌ها", "مدیریت اعلان‌های برنامه و پیامک‌ها", green),
                    Triple("تنظیمات ظاهر", if (darkMode) "حالت تاریک" else "حالت روشن", purple),
                    Triple("زبان و منطقه", "فارسی (ایران)", blue)
                ),
                onRow = { index -> if (index == 3) darkMode = !darkMode }
            )
        }

        item {
            SettingsGroup(
                rows = listOf(
                    Triple("پشتیبان‌گیری و بازیابی", "ذخیره و بازیابی اطلاعات شما", green),
                    Triple("درباره برنامه", "نسخه 1.0.0", purple),
                    Triple("خروج از حساب", "خروج و بازگشت به صفحه ورود", red)
                ),
                onRow = { index -> if (index == 2) onLogout() }
            )
        }
    }
}

@Composable
private fun SettingsGroup(
    rows: List<Triple<String, String, Color>>,
    onRow: (Int) -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE4E8EE)),
        shape = MaterialTheme.shapes.large
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                Row(
                    Modifier.fillMaxWidth().clickable { onRow(index) }.padding(horizontal = 12.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("›", color = Color(0xFF111B4D), style = MaterialTheme.typography.headlineSmall)
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(row.first, color = Color(0xFF111B4D), style = MaterialTheme.typography.titleMedium)
                            Text(row.second, color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = MaterialTheme.shapes.large, color = row.third.copy(alpha = 0.10f)) {
                            Text(
                                when (row.third) {
                                    Color(0xFFF09A17) -> "▢"
                                    Color(0xFF6B28D9) -> "◐"
                                    Color(0xFFDC3030) -> "⇥"
                                    Color(0xFF1877E8) -> "◎"
                                    else -> "♧"
                                },
                                Modifier.padding(10.dp),
                                color = row.third
                            )
                        }
                    }
                }
                if (index < rows.lastIndex) HorizontalDivider(color = Color(0xFFE9ECF1))
            }
        }
    }
}

@Composable
fun SmsTransactions(modifier: Modifier) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(pendingSms(context)) }
    var showDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { pending = pendingSms(context); kotlinx.coroutines.delay(1500) }
    }

    val green = Color(0xFF0F9F6E); val navy = Color(0xFF111B4D); val blue = Color(0xFF1877E8)
    val red = Color(0xFFE02B2B); val orange = Color(0xFFE99513); val bg = Color(0xFFF7F9FC)

    LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Spacer(Modifier.width(40.dp))
                Text("تراکنش‌های پیامکی", color = navy, style = MaterialTheme.typography.headlineSmall)
                Text("⚙", color = navy, style = MaterialTheme.typography.headlineMedium)
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3FF)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFDDE7F5)), shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFDCEBFF)) { Text("SMS", Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = blue) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Text("تراکنش‌های شناسایی شده از پیامک", color = navy, style = MaterialTheme.typography.titleMedium)
                        Text("تراکنش‌های بانکی شما از طریق پیامک شناسایی می‌شوند. لطفاً آن‌ها را بررسی و تأیید کنید.", color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                    }
                    Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFFE9F8F0)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("●  فعال", color = green, style = MaterialTheme.typography.labelMedium)
                            Text("دریافت خودکار پیامک‌ها", color = Color(0xFF4C7A6B), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmsStat("کل پیامک‌های خوانده شده", "۰", "✉", Color(0xFFEAF3FF), blue, Modifier.weight(1f))
                SmsStat("نیاز به بررسی", if (pending != null) "۱" else "۰", "!", Color(0xFFFFF5DE), orange, Modifier.weight(1f))
                SmsStat("در انتظار تأیید", if (pending != null) "۱" else "۰", "◷", Color(0xFFFFEDEF), red, Modifier.weight(1f))
                SmsStat("تأیید شده امروز", "۰", "✓", Color(0xFFE9F8F0), green, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val count = if (pending != null) 1 else 0
                listOf("همه (" + count + ")" to true,"در انتظار تأیید (" + count + ")" to false,"نیاز به بررسی (" + count + ")" to false,"رد شده (۰)" to false).forEach { (label, selected) ->
                    Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.medium, color = if (selected) green else Color(0xFFF0F2F7)) {
                        Text(label, Modifier.padding(horizontal = 5.dp, vertical = 10.dp), color = if (selected) Color.White else navy,
                            style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        if (pending != null) {
            item { SmsPendingCard(pending!!, { clearPendingSms(context); pending = null }, { showDialog = true }) }
        } else {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("تراکنشی در انتظار تأیید وجود ندارد.", color = navy, style = MaterialTheme.typography.titleMedium)
                        Text("پیامک بانکی جدید پس از شناسایی در اینجا نمایش داده می‌شود.", color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (showDialog && pending != null) SmsTransactionDialog(pending!!, { showDialog = false }, { showDialog = false; pending = pendingSms(context) })
}

@Composable
private fun SmsStat(title: String, value: String, icon: String, bg: Color, accent: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = bg), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(10.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text(icon, color = accent, style = MaterialTheme.typography.titleLarge)
            Text(title, color = Color(0xFF26304F), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(value, color = accent, style = MaterialTheme.typography.titleLarge)
            Text("تراکنش", color = accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SmsPendingCard(parsed: JSONObject, onReject: () -> Unit, onConfirm: () -> Unit) {
    val navy = Color(0xFF111B4D); val green = Color(0xFF0F9F6E); val red = Color(0xFFE02B2B)
    val amount = money(parsed.opt("amount")); val isIncome = parsed.optString("type") == "INCOME"
    val title = parsed.optString("merchant").ifBlank { parsed.optString("description").ifBlank { if (isIncome) "واریز" else "تراکنش بانکی" } }
    val detail = parsed.optString("description").ifBlank { if (isIncome) "واریز از بانک" else "خرید" }
    val date = timestampToJalali(parsed.optLong("receivedAt"))
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE5E8EE)), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.width(190.dp).padding(14.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Button(onClick = onReject, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE9EC), contentColor = red)) { Text("▣   رد") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = green)) { Text("✓   تأیید و ثبت", color = Color.White) }
            }
            Column(Modifier.weight(1f).padding(14.dp), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text((if (isIncome) "+" else "-") + amount, color = if (isIncome) green else red, style = MaterialTheme.typography.titleLarge)
                Text("ریال", color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                Text(title, color = navy, style = MaterialTheme.typography.titleMedium)
                Text(detail, color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                Text("بانک ملت  •  " + date, color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("بانک ملت") }
                    OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text(if (isIncome) "درآمد" else "هزینه") }
                    OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("یادداشت") }
                }
            }
        }
    }
}

@Composable
fun Home(modifier: Modifier, logout: () -> Unit, onOpenTransaction: (String) -> Unit, onOpenPage: (Int) -> Unit) {
    val context = LocalContext.current
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var profile by remember { mutableStateOf<JSONObject?>(null) }
    var upcoming by remember { mutableStateOf(listOf<JSONObject>()) }
    var recent by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf("") }
    var sms by remember { mutableStateOf<JSONObject?>(pendingSms(context)) }
    var showSms by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            d = JSONObject(call(context, "/api/dashboard/"))
            profile = JSONObject(call(context, "/api/auth/profile/"))
            upcoming = JSONArray(call(context, "/api/installments/upcoming/?days=7")).toObjects().take(3)
            recent = JSONArray(call(context, "/api/transactions/")).toObjects().take(3)
        } catch (e: Exception) { error = e.message ?: "خطا" }
    }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(1500); sms = pendingSms(context) }
    }

    val p = Color(0xFF0F766E); val bg = Color(0xFFF5F8F7); val card = Color.White
    val text = Color(0xFF17211F); val muted = Color(0xFF71807B); val line = Color(0xFFE1E9E6)
    val good = Color(0xFF15803D); val danger = Color(0xFFDC2626)
    val userName = profile?.optString("first_name").orEmpty().ifBlank {
        profile?.optString("username").orEmpty().ifBlank { "کاربر" }
    }

    LazyColumn(modifier = modifier.fillMaxSize().background(bg),
        contentPadding = PaddingValues(13.dp,18.dp,13.dp,24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFE8F7F3), modifier = Modifier.size(54.dp)) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text("▥", color = p, style = MaterialTheme.typography.headlineSmall) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(userName, style = MaterialTheme.typography.headlineSmall, color = text)
                    Text("مدیریت بهتر پول، زندگی آرام‌تر", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }

        d?.let { data ->
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = p),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.Top) {
                            Column {
                                Text("نسبت به ماه قبل", color = Color(0xFFCCE6E2), style = MaterialTheme.typography.labelMedium)
                                Text("+\${fa("0")}٪", color = Color(0xFF86EFAC), style = MaterialTheme.typography.titleLarge)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text("موجودی کل", color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text(money(data.opt("current_balance")), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                        Spacer(Modifier.height(18.dp)); HorizontalDivider(color = Color.White.copy(alpha = .18f)); Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("ریال", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Text("امروز: " + todayJalali() + "  •  " + fa(data.opt("days_remaining").toString()) + " روز تا پایان ماه",
                                color = Color(0xFFCCE6E2), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenTransaction("EXPENSE") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F2222)),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) { Text("ثبت هزینه", color = Color(0xFFFF8A8A)) }
                    Button(onClick = { onOpenTransaction("INCOME") }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF153A31)),
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) { Text("ثبت درآمد", color = Color(0xFF63E6A7)) }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = card),
                    border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("تشخیص تراکنش بانکی از پیامک", style = MaterialTheme.typography.titleMedium, color = text)
                            Text(if (sms != null) "یک تراکنش جدید از پیامک بانکی آماده بررسی است."
                                 else "پیامک‌های بانکی را خودکار شناسایی و برای ثبت آماده می‌کنیم.",
                                color = muted, style = MaterialTheme.typography.bodySmall)
                            if (sms != null) { Spacer(Modifier.height(8.dp)); Button(onClick = { showSms = true }) { Text("بررسی و ثبت") } }
                        }
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFE8F7F3)) {
                            Text("✉", modifier = Modifier.padding(14.dp), color = p)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = card),
                    border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column {
                            Text("مانده تعهدات این ماه", style = MaterialTheme.typography.titleMedium, color = text)
                            Text(money(data.opt("current_month_commitments")), color = text, style = MaterialTheme.typography.headlineSmall)
                        }
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFEAF5F3)) {
                            Text("▣", modifier = Modifier.padding(14.dp), color = p)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashboardMetric("درآمد ماه جاری", money(data.opt("monthly_income")), "↑", Color(0xFFECFDF3), good, Modifier.weight(1f))
                    DashboardMetric("هزینه ماه جاری", money(data.opt("monthly_expense")), "!", Color(0xFFFEF2F2), danger, Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("اقساط نزدیک", style = MaterialTheme.typography.titleMedium, color = text)
                    TextButton(onClick = { onOpenPage(2) }) { Text("مشاهده همه", color = p) }
                }
            }
            if (upcoming.isEmpty()) {
                item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card)) {
                    Text("در ۷ روز آینده قسطی برای پرداخت ندارید.", Modifier.padding(16.dp), color = muted)
                } }
            } else {
                items(upcoming) { inst ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F6F5)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column {
                                Text((inst.optString("loan_title").ifBlank { "قسط" }) + "  •  قسط " + fa(inst.optInt("installment_number").toString()), color = text)
                                Text("سررسید " + apiToJalali(inst.optString("due_date")), color = muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(money(inst.opt("remaining_amount").takeIf { it != null } ?: inst.opt("amount")), color = text)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("آخرین تراکنش‌ها", style = MaterialTheme.typography.titleMedium, color = text)
                    TextButton(onClick = { onOpenPage(1) }) { Text("مشاهده همه", color = p) }
                }
            }
            if (recent.isEmpty()) {
                item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card)) {
                    Text("هنوز تراکنشی ثبت نشده است.", Modifier.padding(16.dp), color = muted)
                } }
            } else {
                items(recent) { tx ->
                    val income = tx.optString("transaction_type") == "INCOME"
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card),
                        border = androidx.compose.foundation.BorderStroke(1.dp, line)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column {
                                Text(tx.optString("description").ifBlank { if (income) "درآمد" else "هزینه" }, color = text)
                                Text(apiToJalali(tx.optString("date")), color = muted, style = MaterialTheme.typography.bodySmall)
                            }
                            Text((if (income) "+" else "-") + money(tx.opt("amount")), color = if (income) good else danger)
                        }
                    }
                }
            }
        } ?: item {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = p)
            }
        }
        if (error.isNotBlank()) { item { Text(error, color = danger) } }
    }

    if (showSms && sms != null) {
        SmsTransactionDialog(parsed = sms!!, onClose = { showSms = false }, onSaved = { showSms = false; sms = pendingSms(context) })
    }
}

@Composable
fun SmsTransactionDialog(parsed: JSONObject, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(parsed.optString("type").ifBlank { "EXPENSE" }) }
    var amount by remember { mutableStateOf(parsed.optLong("amount").toString()) }
    var account by remember { mutableStateOf(0) }
    var category by remember { mutableStateOf(0) }
    var accounts by remember { mutableStateOf(listOf<JSONObject>()) }
    var categories by remember { mutableStateOf(listOf<JSONObject>()) }
    var date by remember { mutableStateOf(timestampToJalali(parsed.optLong("receivedAt"))) }
    var description by remember { mutableStateOf("ثبت از پیامک بانکی") }
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            accounts = JSONArray(call(context, "/api/accounts/")).toObjects()
            categories = JSONArray(call(context, "/api/categories/")).toObjects()
            account = accounts.firstOrNull()?.optInt("id", 0) ?: 0
        } catch (e: Exception) {
            error = e.message ?: "خطا در دریافت حساب‌ها و دسته‌بندی‌ها"
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("تأیید تراکنش پیامکی") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اطلاعات از پیامک بانکی استخراج شده؛ قبل از ثبت بررسی کنید.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("هزینه") })
                    FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("درآمد") })
                }
                AmountField("مبلغ", amount) { amount = it }
                SimpleSelector("حساب", accounts, account) { account = it }
                SimpleSelector("دسته‌بندی", categories.filter { it.optString("category_type") == type }, category) { category = it }
                TextField(description, { description = it }, label = { Text("توضیح") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) { Text("تاریخ: $date") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    try {
                        val n = parseMoney(amount)
                        if (n <= 0 || account == 0 || category == 0) {
                            error = "مبلغ، حساب و دسته‌بندی را کامل کنید"
                        } else {
                            val body = JSONObject()
                                .put("account", account)
                                .put("category", category)
                                .put("transaction_type", type)
                                .put("amount", n)
                                .put("date", jalaliToApi(date))
                                .put("description", description.trim())
                            call(context, "/api/transactions/", "POST", body.toString())
                            clearPendingSms(context)
                            onClose()
                            onSaved()
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "خطا در ثبت تراکنش"
                    }
                }
            }) { Text("تأیید و ثبت") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("انصراف") } }
    )
    if (showDate) JalaliDateDialog(date) { date = it; showDate = false }
}

@Composable
fun FinanceRow(label: String, value: String, valueColor: Color, muted: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun InfoCard(
    title: String,
    rows: List<Pair<String,String>>,
    modifier: Modifier,
    background: Color,
    border: Color,
    text: Color,
    muted: Color,
    danger: Color
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = background),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column(Modifier.padding(19.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = text)
            rows.forEachIndexed { i, row ->
                FinanceRow(row.first, row.second, if (i == 0) danger else text, muted)
                if (i < rows.lastIndex) HorizontalDivider(color = border)
            }
        }
    }
}

@Composable
fun DashboardMetric(
    title: String,
    value: String,
    symbol: String,
    background: Color,
    accent: Color,
    modifier: Modifier
) {
    Card(modifier, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(15.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Surface(shape = MaterialTheme.shapes.small, color = background) {
                    Text(symbol, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color(0xFF17211F))
        }
    }
}

private val jalaliMonths = listOf("فروردین","اردیبهشت","خرداد","تیر","مرداد","شهریور","مهر","آبان","آذر","دی","بهمن","اسفند")

private fun jalaliYearMonthFromApi(date: String): Pair<Int, Int>? = try {
    val p = apiToJalali(date).split("/").map { it.toInt() }
    if (p.size == 3) p[0] to p[1] else null
} catch (_: Exception) { null }

@Composable
fun MonthPickerDialog(year: Int, month: Int, onPicked: (Int, Int) -> Unit, onClose: () -> Unit) {
    var selectedYear by remember { mutableStateOf(year) }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("انتخاب ماه")
                Row {
                    TextButton(onClick = { selectedYear-- }) { Text("‹") }
                    Text(fa(selectedYear.toString()), modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { selectedYear++ }) { Text("›") }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                jalaliMonths.chunked(3).forEachIndexed { rowIndex, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEachIndexed { colIndex, name ->
                            val m = rowIndex * 3 + colIndex + 1
                            val selected = selectedYear == year && m == month
                            Button(onClick = { onPicked(selectedYear, m); onClose() }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) Color(0xFF0F766E) else Color(0xFFE8EEEC),
                                    contentColor = if (selected) Color.White else Color(0xFF34423E)
                                )
                            ) { Text(name) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("بستن") } }
    )
}

@Composable
fun Transactions(modifier: Modifier, quickType: String? = null, onQuickTypeConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(listOf<JSONObject>()) }
    var accounts by remember { mutableStateOf(listOf<JSONObject>()) }
    var categories by remember { mutableStateOf(listOf<JSONObject>()) }
    var show by remember { mutableStateOf(false) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val todayParts = todayJalali().split("/").map { it.toInt() }
    var selectedYear by remember { mutableStateOf(todayParts[0]) }
    var selectedMonth by remember { mutableStateOf(todayParts[1]) }

    LaunchedEffect(quickType) { if (quickType != null) show = true }

    fun load() {
        scope.launch {
            try {
                data = JSONArray(call(context, "/api/transactions/")).toObjects()
                accounts = JSONArray(call(context, "/api/accounts/")).toObjects()
                categories = JSONArray(call(context, "/api/categories/")).toObjects()
            } catch (e: Exception) { error = e.message ?: "خطا" }
        }
    }
    LaunchedEffect(Unit) { load() }

    val monthData = data.filter { jalaliYearMonthFromApi(it.optString("date")) == (selectedYear to selectedMonth) }
        .sortedByDescending { it.optString("date") }
    val grouped = monthData.groupBy { apiToJalali(it.optString("date")) }

    val p = Color(0xFF0F766E)
    val bg = Color(0xFF17312C)
    val card = Color(0xFF223B36)
    val expense = Color(0xFF7F2630)
    val expenseText = Color(0xFFFF8B8F)
    val income = Color(0xFF0B6B52)
    val incomeText = Color(0xFF59F0B0)
    val text = Color.White
    val muted = Color(0xFFC2D1CD)

    Column(modifier.fillMaxSize().background(bg).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedButton(onClick = { }, colors = ButtonDefaults.outlinedButtonColors(contentColor = p)) { Text("گزارشات") }
            Text("تراکنش‌ها", style = MaterialTheme.typography.headlineSmall, color = text)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { show = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF75E7CC), contentColor = Color(0xFF18443A)), modifier = Modifier.weight(1f)) { Text("ثبت هزینه") }
            Button(onClick = { show = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF75E7CC), contentColor = Color(0xFF18443A)), modifier = Modifier.weight(1f)) { Text("ثبت درآمد") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { showMonthPicker = true }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = text),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB8C0BC))) {
            Text("تقویم ماه: " + jalaliMonths[selectedMonth - 1] + " " + fa(selectedYear.toString()), modifier = Modifier.weight(1f))
            Text("▣", color = p)
        }
        Spacer(Modifier.height(12.dp))

        if (monthData.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = card)) {
                Text("برای " + jalaliMonths[selectedMonth - 1] + " " + fa(selectedYear.toString()) + " تراکنشی ثبت نشده است.", Modifier.padding(18.dp), color = muted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                grouped.forEach { (day, transactions) ->
                    item { Text(formatJalaliDayTitle(day), color = text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) }
                    items(transactions) { t ->
                        val isIncome = t.optString("transaction_type") == "INCOME"
                        val title = t.optString("description").ifBlank { if (isIncome) "درآمد" else "هزینه" }
                        val container = if (isIncome) income else expense
                        val amountColor = if (isIncome) incomeText else expenseText
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container), shape = MaterialTheme.shapes.large) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text((if (isIncome) "+" else "-") + " " + money(t.opt("amount")), color = amountColor, style = MaterialTheme.typography.titleMedium)
                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                    Text(apiToJalali(t.optString("date")) + " • " + transactionTime(t), color = Color(0xFFD7E2DF), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFFF8B8F), modifier = Modifier.padding(top = 8.dp))
    }

    if (showMonthPicker) MonthPickerDialog(selectedYear, selectedMonth, onPicked = { y, m -> selectedYear = y; selectedMonth = m }, onClose = { showMonthPicker = false })
    if (show) TransactionDialog(quickType, accounts, categories, { show = false; onQuickTypeConsumed() }, { load() })
}

private fun formatJalaliDayTitle(jalali: String): String {
    val p = jalali.split("/").mapNotNull { it.toIntOrNull() }
    return if (p.size == 3) fa(p[2].toString()) + " " + (jalaliMonths.getOrNull(p[1] - 1) ?: "") + " " + fa(p[0].toString()) else fa(jalali)
}

private fun transactionTime(t: JSONObject): String {
    val raw = t.optString("created_at").ifBlank { t.optString("timestamp") }
    return if (raw.length >= 16 && raw.contains("T")) raw.substring(11, 16) else ""
}

@Composable
fun JalaliDateDialog(initial: String, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val parts = initial.replace("-", "/").split("/").mapNotNull { it.toIntOrNull() }
    val g = if (parts.size == 3) jalaliToGregorian(parts[0], parts[1], parts[2]) else {
        val c = Calendar.getInstance()
        Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            onPicked(apiToJalali("%04d-%02d-%02d".format(year, month + 1, day)))
        },
        g.first, g.second - 1, g.third
    ).show()
}

@Composable
fun TransactionDialog(initialType: String?, accounts: List<JSONObject>, categories: List<JSONObject>, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initialType ?: "EXPENSE") }
    var amount by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(accounts.firstOrNull()?.optInt("id", 0) ?: 0) }
    var category by remember { mutableStateOf(0) }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayJalali()) }
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text("تراکنش جدید") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("ثبت هزینه") })
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("ثبت درآمد") })
            }
            AmountField("مبلغ", amount) { amount = it }
            SimpleSelector("حساب", accounts, account) { account = it }
            SimpleSelector("دسته‌بندی", categories.filter { it.optString("category_type") == type }, category) { category = it }
            TextField(description, { description = it }, label = { Text("توضیح") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) { Text("تاریخ: " + date) }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button(onClick = { scope.launch { try {
            val n = parseMoney(amount)
            if (n <= 0 || account == 0 || category == 0) error = "مبلغ، حساب و دسته‌بندی را کامل کنید"
            else {
                val body = JSONObject().put("account", account).put("category", category).put("transaction_type", type).put("amount", n).put("date", jalaliToApi(date)).put("description", description.trim())
                call(context, "/api/transactions/", "POST", body.toString())
                onClose(); onSaved()
            }
        } catch(e: Exception) { error = e.message ?: "خطا" } } }) { Text("ثبت") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("انصراف") } })
    if (showDate) JalaliDateDialog(date) { date = it; showDate = false }
}

@Composable
fun SimpleSelector(label: String, items: List<JSONObject>, selected: Int, onSelected: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = items.firstOrNull { it.optInt("id") == selected }?.optString("name") ?: "انتخاب کنید"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label + ": " + name) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item -> DropdownMenuItem(text = { Text(item.optString("name")) }, onClick = { onSelected(item.optInt("id")); open = false }) }
        }
    }
}

@Composable
fun AmountField(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(formatInputMoney(value), { change(digitsOnly(it)) }, label = { Text(label) },
        suffix = { Text("ریال") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth())
}

@Composable
fun Accounts(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(listOf<JSONObject>()) }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    fun load() { scope.launch { try { data = JSONArray(call(context, "/api/accounts/")).toObjects() } catch(e: Exception) { error = e.message ?: "خطا" } } }
    LaunchedEffect(Unit) { load() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("حساب‌ها", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { show = true }) { Text("حساب جدید") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn { items(data) { a ->
            ListItem(headlineContent = { Text(a.optString("name")) }, supportingContent = { Text(a.optString("account_type")) }, trailingContent = { Text(money(a.opt("initial_balance"))) })
            HorizontalDivider()
        } }
    }
    if (show) AccountDialog({ show = false }, { load() })
}

@Composable
fun AccountDialog(onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("BANK") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text("حساب جدید") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(name, { name = it }, label = { Text("نام حساب") }, modifier = Modifier.fillMaxWidth())
            AmountField("موجودی اولیه", balance) { balance = it }
            SimpleChoice("نوع حساب", listOf("BANK" to "بانک", "CASH" to "نقدی", "WALLET" to "کیف پول", "OTHER" to "سایر"), type) { type = it }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button(onClick = { scope.launch { try {
            if (name.trim().isBlank()) error = "نام حساب را وارد کنید"
            else {
                val body = JSONObject().put("name", name.trim()).put("account_type", type).put("initial_balance", parseMoney(balance)).put("is_active", true)
                call(context, "/api/accounts/", "POST", body.toString())
                onClose(); onSaved()
            }
        } catch(e: Exception) { error = e.message ?: "خطا" } } }) { Text("ثبت") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("انصراف") } })
}

@Composable
fun SimpleChoice(label: String, choices: List<Pair<String,String>>, selected: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = choices.firstOrNull { it.first == selected }?.second ?: "انتخاب کنید"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(label + ": " + name) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { pair -> DropdownMenuItem(text = { Text(pair.second) }, onClick = { onSelected(pair.first); open = false }) }
        }
    }
}
@Composable
fun Loans(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loans by remember { mutableStateOf(listOf<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var installments by remember { mutableStateOf(listOf<JSONObject>()) }
    var showNew by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }

    val green = Color(0xFF0F9F6E)
    val navy = Color(0xFF111B4D)
    val bg = Color(0xFFF7F9FC)
    val muted = Color(0xFF68738A)
    val red = Color(0xFFDC3030)
    val blue = Color(0xFF1877E8)

    fun load() {
        scope.launch {
            try { loans = JSONArray(call(context, "/api/loans/")).toObjects() }
            catch (e: Exception) { error = e.message ?: "خطا" }
        }
    }
    fun loadInstallments(loan: JSONObject) {
        scope.launch {
            try {
                installments = JSONArray(call(context, "/api/installments/?loan=" + loan.optInt("id"))).toObjects()
                selected = loan
            } catch (e: Exception) { error = e.message ?: "خطا" }
        }
    }
    LaunchedEffect(Unit) { load() }

    if (showNew) {
        LoanCreateScreen(onBack = { showNew = false }, onSaved = { showNew = false; load() })
        return
    }
    if (selected != null) {
        LoanDetailScreen(loan = selected!!, installments = installments, onBack = { selected = null },
            onPay = { inst ->
                scope.launch {
                    try {
                        val accounts = JSONArray(call(context, "/api/accounts/")).toObjects()
                        val categories = JSONArray(call(context, "/api/categories/")).toObjects()
                        val aid = accounts.firstOrNull()?.optInt("id", 0) ?: 0
                        val cid = categories.firstOrNull { it.optString("category_type") == "EXPENSE" }?.optInt("id", 0) ?: 0
                        if (aid == 0 || cid == 0) error = "ابتدا یک حساب و دسته‌بندی هزینه داشته باشید"
                        else {
                            call(context, "/api/installments/" + inst.optInt("id") + "/pay/", "POST",
                                JSONObject().put("amount", inst.opt("amount")).put("account_id", aid)
                                    .put("category_id", cid).put("paid_date", todayApi()).toString())
                            loadInstallments(selected!!)
                        }
                    } catch (e: Exception) { error = e.message ?: "خطا" }
                }
            })
        return
    }

    Column(modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("تعهدات", style = MaterialTheme.typography.headlineSmall, color = navy)
            Button(onClick = { showNew = true }, colors = ButtonDefaults.buttonColors(containerColor = green),
                shape = MaterialTheme.shapes.medium) { Text("+  افزودن تعهد جدید", color = Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("همه", "وام و اقساط", "هزینه‌های ثابت").forEachIndexed { i, label ->
                Button(onClick = { tab = i }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (tab == i) green else Color(0xFFF0F3F8),
                        contentColor = if (tab == i) Color.White else navy), shape = MaterialTheme.shapes.medium) { Text(label) }
            }
        }
        if (tab == 2) {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E8))) {
                Text("هزینه‌های ثابت در این بخش نمایش داده می‌شوند.", Modifier.padding(18.dp), color = navy)
            }
        } else {
            val currentMonth = loans.sumOf { it.optLong("installment_amount") }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommitmentStat("کل باقیمانده", money(loans.sumOf { it.optLong("principal_amount") }), "▣", Color(0xFFF2E8FF), Color(0xFF6A21D8), Modifier.weight(1f))
                CommitmentStat("ماه آینده", money(currentMonth), "▤", Color(0xFFE9FAF3), green, Modifier.weight(1f))
                CommitmentStat("این ماه", money(currentMonth), "□", Color(0xFFFFF1DD), Color(0xFFED9411), Modifier.weight(1f))
                CommitmentStat("معوقه", money(0L), "◷", Color(0xFFEAF3FF), blue, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("تعهدات نزدیک", style = MaterialTheme.typography.titleLarge, color = navy)
                TextButton(onClick = {}) { Text("مشاهده همه  ‹", color = blue) }
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(loans.take(3)) { loan -> CommitmentRow(loan, 12, { loadInstallments(loan) }, true) }
                item { Text("تعهدات فعال", style = MaterialTheme.typography.titleLarge, color = navy, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                items(loans) { loan -> CommitmentRow(loan, 12, { loadInstallments(loan) }, false) }
                if (loans.isEmpty()) item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(22.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("هنوز تعهدی ثبت نشده", color = navy, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { showNew = true }, colors = ButtonDefaults.buttonColors(containerColor = green)) { Text("افزودن تعهد", color = Color.White) }
                        }
                    }
                }
                if (error.isNotBlank()) item { Text(error, color = red) }
            }
        }
    }
}

@Composable private fun CommitmentStat(title: String, value: String, icon: String, bg: Color, accent: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = bg), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(10.dp)) {
            Text(icon, color = accent, style = MaterialTheme.typography.titleLarge)
            Text(title, color = Color(0xFF26304F), style = MaterialTheme.typography.labelSmall)
            Text(value, color = accent, style = MaterialTheme.typography.titleMedium)
            Text("ریال", color = accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun CommitmentRow(loan: JSONObject, days: Int, onClick: () -> Unit, compact: Boolean) {
    val green = Color(0xFF0F9F6E); val navy = Color(0xFF111B4D)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE7EAF0)), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compact) 11.dp else 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("›", color = navy, style = MaterialTheme.typography.headlineSmall)
            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFE9F6FF)) { Text("▣", modifier = Modifier.padding(10.dp), color = Color(0xFF1877E8)) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(loan.optString("title").ifBlank { "تعهد" }, color = navy, style = MaterialTheme.typography.titleMedium)
                Text("وام و اقساط", color = Color(0xFF7A8499), style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(money(loan.opt("installment_amount")), color = navy, style = MaterialTheme.typography.titleMedium)
                Text("ریال", color = Color(0xFF7A8499), style = MaterialTheme.typography.bodySmall)
            }
            Surface(shape = MaterialTheme.shapes.medium, color = if (days <= 3) Color(0xFFFFE8E8) else Color(0xFFE9F8F0)) {
                Text(fa(days.toString()) + " روز مانده", Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = if (days <= 3) Color(0xFFC82A2A) else green, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun LoanDetailScreen(loan: JSONObject, installments: List<JSONObject>, onBack: () -> Unit, onPay: (JSONObject) -> Unit) {
    val navy = Color(0xFF111B4D); val green = Color(0xFF0F9F6E); val red = Color(0xFFDC3030); val blue = Color(0xFF1877E8)
    val principal = loan.optLong("principal_amount")
    val paid = installments.filter { it.optString("status") == "PAID" }.sumOf { it.optLong("amount") }
    val total = loan.optLong("installment_amount") * loan.optInt("total_installments")
    val remaining = (total - paid).coerceAtLeast(0)
    val count = loan.optInt("total_installments"); val paidCount = installments.count { it.optString("status") == "PAID" }
    val progress = if (count > 0) paidCount.toFloat() / count else 0f
    Column(Modifier.fillMaxSize().background(Color(0xFFF7F9FC))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("‹", color = navy, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clickable(onClick = onBack))
            Text("جزئیات تعهد", color = navy, style = MaterialTheme.typography.headlineSmall)
            Text("⋮", color = navy, style = MaterialTheme.typography.headlineMedium)
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE6E9EF))) {
                    Column(Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(loan.optString("title"), color = navy, style = MaterialTheme.typography.headlineSmall)
                                Text("وام و بدهی", color = Color(0xFF68738A))
                                Surface(shape = MaterialTheme.shapes.medium, color = Color(0xFFE9F8F0)) { Text("●  در حال پرداخت", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = green) }
                            }
                            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color(0xFFE9F8F0)) { Text("▤", Modifier.padding(18.dp), color = green) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("شروع: " + apiToJalali(loan.optString("start_date")), color = Color(0xFF68738A))
                            Text("پایان: " + apiToJalali(loan.optString("end_date")), color = Color(0xFF68738A))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            DetailValue("مبلغ اصلی", money(principal), "ریال")
                            DetailValue("نرخ سود سالانه", fa(loan.optString("interest_rate").ifBlank { "0" }) + "%", "")
                            DetailValue("تعداد اقساط", fa(count.toString()), "قسط")
                            DetailValue("مبلغ هر قسط", money(loan.opt("installment_amount")), "ریال")
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fa(paidCount.toString()) + " از " + fa(count.toString()) + " قسط پرداخت شده", color = navy)
                            Text(fa((progress * 100).toInt().toString()) + "%", color = navy, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = green, trackColor = Color(0xFFE5E9F0))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailBox("مبلغ کل پرداختی", money(paid), "ریال", Color(0xFFFFEEF0), red)
                    DetailBox("مانده کل", money(remaining), "ریال", Color(0xFFEAF3FF), blue)
                    DetailBox("مانده اصل", money((principal - paid.coerceAtMost(principal)).coerceAtLeast(0)), "ریال", Color(0xFFE9F8F0), green)
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = {}) { Text("اقساط", color = green) }
                    TextButton(onClick = {}) { Text("اطلاعات", color = navy) }
                    TextButton(onClick = {}) { Text("نمودار", color = navy) }
                    TextButton(onClick = {}) { Text("ویرایش", color = navy) }
                }
            }
            item { Text("لیست اقساط", color = navy, style = MaterialTheme.typography.titleLarge) }
            items(installments) { inst ->
                val paidStatus = inst.optString("status") == "PAID"
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (!paidStatus) Color(0xFFF0F6FF) else Color.White)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("⋮", color = navy)
                        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text("قسط " + fa(inst.optInt("installment_number").toString()), color = navy)
                            Text(apiToJalali(inst.optString("due_date")), color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(money(inst.opt("amount")), color = navy)
                            Text("ریال", color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = MaterialTheme.shapes.medium, color = if (paidStatus) Color(0xFFE9F8F0) else Color(0xFFEAF3FF)) {
                            Text(if (paidStatus) "✓ پرداخت شده" else "در انتظار پرداخت", Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                color = if (paidStatus) green else blue, style = MaterialTheme.typography.labelSmall)
                        }
                        if (!paidStatus) {
                            Spacer(Modifier.width(4.dp))
                            Button(onClick = { onPay(inst) }, colors = ButtonDefaults.buttonColors(containerColor = green)) { Text("پرداخت", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DetailValue(title: String, value: String, unit: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.width(82.dp)) {
        Text(title, color = Color(0xFF68738A), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color(0xFF0F9F6E), style = MaterialTheme.typography.titleMedium)
        if (unit.isNotBlank()) Text(unit, color = Color(0xFF68738A), style = MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun DetailBox(title: String, value: String, unit: String, bg: Color, accent: Color) {
    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(10.dp)) { Text(title, color = Color(0xFF68738A), style = MaterialTheme.typography.labelSmall); Text(value, color = accent, style = MaterialTheme.typography.titleMedium); Text(unit, color = accent, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun LoanCreateScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }; var principal by remember { mutableStateOf("") }; var interest by remember { mutableStateOf("") }
    var installment by remember { mutableStateOf("") }; var count by remember { mutableStateOf("") }; var start by remember { mutableStateOf(todayJalali()) }
    var showDate by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; var saving by remember { mutableStateOf(false) }
    val p = parseMoney(principal); val i = parseMoney(interest); val inst = parseMoney(installment); val n = count.toIntOrNull() ?: 0
    val calculatedInterest = if (interest.isBlank() && inst > 0 && n > 0) (inst * n - p).coerceAtLeast(0) else null
    val calculatedInstallment = if (installment.isBlank() && n > 0 && p > 0) ((p + i + n - 1) / n) else null
    val finalInterest = if (interest.isBlank()) calculatedInterest ?: 0L else i
    val finalInstallment = if (installment.isBlank()) calculatedInstallment ?: 0L else inst
    val totalRepayment = p + finalInterest
    Column(Modifier.fillMaxSize().background(Color(0xFFF7F9FC))) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("‹", color = Color(0xFF111B4D), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.clickable(onClick = onBack))
            Text("افزودن تعهد", color = Color(0xFF111B4D), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(40.dp))
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F8F0))) { Column(Modifier.padding(16.dp)) { Text("●", color = Color(0xFF0F9F6E)); Text("وام / بدهی", color = Color(0xFF111B4D)); Text("با اقساط و سود", color = Color(0xFF68738A)) } }
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2E2))) { Column(Modifier.padding(16.dp)) { Text("▣", color = Color(0xFFED9411)); Text("هزینه ثابت", color = Color(0xFF111B4D)); Text("مصارف ماهانه و دوره‌ای", color = Color(0xFF68738A)) } }
                }
            }
            item { OutlinedTextField(title, { title = it }, label = { Text("عنوان تعهد") }, placeholder = { Text("مثلاً وام خودرو، وام بانک، اجاره و ...") }, modifier = Modifier.fillMaxWidth()) }
            item { AmountField("مبلغ اصلی (ریال)", principal) { principal = it } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("سود کل", interest, Modifier.weight(1f)) { interest = it }
                    OutlinedTextField(count, { count = it.filter(Char::isDigit) }, label = { Text("تعداد اقساط") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F8F0))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("مبلغ هر قسط (ریال)", color = Color(0xFF111B4D))
                        AmountField("", installment) { installment = it }
                        Text("مبلغ قسط بر اساس مبلغ اصلی، سود و تعداد اقساط به صورت خودکار محاسبه می‌شود.", color = Color(0xFF68738A), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) { Text("تاریخ شروع\n" + start) }
                    OutlinedButton(onClick = { }, modifier = Modifier.weight(1f)) { Text("روز سررسید هر ماه\n۵") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF4FF))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("خلاصه تعهد", color = Color(0xFF111B4D), style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            DetailValue("مبلغ اصلی", money(p), "ریال"); DetailValue("سود کل", money(finalInterest), "ریال"); DetailValue("مبلغ هر قسط", money(finalInstallment), "ریال"); DetailValue("مبلغ کل پرداختی", money(totalRepayment), "ریال")
                        }
                    }
                }
            }
            if (error.isNotBlank()) item { Text(error, color = Color(0xFFDC3030)) }
            item {
                Button(enabled = !saving, onClick = {
                    scope.launch {
                        try {
                            if (title.trim().isBlank() || p <= 0 || finalInstallment <= 0 || n <= 0) error = "عنوان، مبلغ اصلی، مبلغ قسط و تعداد اقساط را کامل کنید"
                            else {
                                saving = true
                                val body = JSONObject().put("title", title.trim()).put("loan_type", "LOAN").put("principal_amount", p)
                                    .put("interest_amount", finalInterest).put("installment_amount", finalInstallment).put("total_installments", n)
                                    .put("start_date", jalaliToApi(start)).put("is_active", true).put("history_mode", "ALL_PAID").put("overdue_count", 0)
                                call(context, "/api/loans/", "POST", body.toString()); onSaved()
                            }
                        } catch (e: Exception) { error = e.message ?: "خطا" }
                        saving = false
                    }
                }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F9F6E)),
                    shape = MaterialTheme.shapes.large) { Text(if (saving) "در حال ثبت..." else "ثبت تعهد", color = Color.White, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
    }
    if (showDate) JalaliDateDialog(start) { start = it; showDate = false }
}

@Composable
private fun PasswordResetDialog(context: Context, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("بازیابی رمز عبور") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("نام کاربری را وارد کنید تا درخواست بازیابی برای مدیر ثبت شود.")
                OutlinedTextField(username, { username = it }, label = { Text("نام کاربری") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = {
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    message = ""
                    try {
                        message = JSONObject(call(context, "/api/auth/password-reset-request/", "POST", JSONObject().put("username", username.trim()).toString())).optString("detail", "درخواست ثبت شد.")
                    } catch (e: Exception) { message = e.message ?: "خطا" }
                    busy = false
                }
            }) { Text(if (busy) "در حال ارسال..." else "ثبت درخواست") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("بستن") } }
    )
}
