package ir.dastyaremali.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
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

private const val API = "https://n8n.etedak.ir/dastyar-api"
private const val PREF = "dastyar_auth"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n) + " تومان"
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
private fun apiToJalali(s:String): String { val p=s.split("-").mapNotNull{it.toIntOrNull()}; if(p.size!=3)return s; val j=gregorianToJalali(p[0],p[1],p[2]); return "%04d/%02d/%02d".format(j.first,j.second,j.third) }
private fun JSONArray.toObjects(): List<JSONObject> = List(length()) { getJSONObject(it) }

@Composable
fun DastyarApp() {
    val context = LocalContext.current
    var logged by remember { mutableStateOf(context.token() != null) }
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0F766E))) {
            if (logged) AppShell { context.logout(); logged = false }
            else LoginScreen { logged = true }
        }
    }
}

@Composable
fun LoginScreen(done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
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
        OutlinedTextField(pass, { pass = it }, label = { Text("گذرواژه") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true; error = ""
                try {
                    if (register) call(context, "/api/auth/register/", "POST",
                        JSONObject().put("username", user.trim()).put("password", pass).put("first_name", name.trim()).toString())
                    val r = JSONObject(call(context, "/api/auth/token/", "POST",
                        JSONObject().put("username", user.trim()).put("password", pass).toString()))
                    context.saveTokens(r.getString("access"), r.optString("refresh").ifBlank { null }); done()
                } catch (e: Exception) { error = e.message ?: "خطا" }
                busy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "در حال انجام..." else if (register) "ساخت حساب" else "ورود") }
        TextButton(onClick = { register = !register; error = "" }, modifier = Modifier.fillMaxWidth()) {
            Text(if (register) "ورود به حساب" else "ساخت حساب جدید")
        }
    }
}

@Composable
fun AppShell(onLogout: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    var quickTransactionType by remember { mutableStateOf<String?>(null) }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf(
                "خانه" to "⌂",
                "تراکنش" to "↕",
                "اقساط" to "▣",
                "حساب" to "●"
            ).forEachIndexed { i, item ->
                NavigationBarItem(
                    selected = page == i,
                    onClick = { page = i; if (i != 1) quickTransactionType = null },
                    icon = { Text(item.second, style = MaterialTheme.typography.titleMedium) },
                    label = { Text(item.first) }
                )
            }
        }
    }) { p ->
        when (page) {
            1 -> Transactions(Modifier.padding(p), quickTransactionType, { quickTransactionType = null })
            2 -> Loans(Modifier.padding(p))
            3 -> Accounts(Modifier.padding(p))
            else -> Home(Modifier.padding(p), onLogout) { type ->
                quickTransactionType = type
                page = 1
            }
        }
    }
}

@Composable
fun Home(modifier: Modifier, logout: () -> Unit, openTransaction: (String) -> Unit) {
    val context = LocalContext.current
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { d = JSONObject(call(context, "/api/dashboard/")) }
        catch (e: Exception) { error = e.message ?: "خطا" }
    }

    val p = Color(0xFF0F766E)
    val p2 = Color(0xFF115E59)
    val bg = Color(0xFFF5F8F7)
    val card = Color.White
    val text = Color(0xFF17211F)
    val muted = Color(0xFF71807B)
    val line = Color(0xFFE1E9E6)
    val good = Color(0xFF15803D)
    val danger = Color(0xFFDC2626)
    val warn = Color(0xFFB45309)

    LazyColumn(
        modifier = modifier.fillMaxSize().background(bg),
        contentPadding = PaddingValues(13.dp, 18.dp, 13.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column {
                    Text("داشبورد مالی", style = MaterialTheme.typography.headlineSmall, color = text)
                    Text("همه اعداد از موتور مالی Django می‌آیند.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                TextButton(onClick = logout) { Text("خروج", color = muted) }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openTransaction("INCOME") },
                    colors = ButtonDefaults.buttonColors(containerColor = p),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) { Text("＋ ثبت درآمد") }
                OutlinedButton(
                    onClick = { openTransaction("EXPENSE") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8B5B5)),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f)
                ) { Text("− ثبت هزینه") }
            }
        }

        if (error.isNotBlank()) item { Text(error, color = danger) }

        d?.let { data ->
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = p2),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("موجودی فعلی", color = Color(0xFFCCE6E2), style = MaterialTheme.typography.labelMedium)
                        Text(money(data.opt("current_balance")), color = Color.White, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(18.dp))
                        Text("قابل خرج کردن امن", color = Color(0xFFCCE6E2), style = MaterialTheme.typography.labelMedium)
                        Text(money(data.opt("safe_to_spend")), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = .2f))
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                when(data.optString("risk_level")) {
                                    "SAFE" -> "وضعیت خوب"
                                    "WARNING" -> "نیازمند توجه"
                                    else -> "وضعیت بحرانی"
                                },
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(fa(data.opt("days_remaining").toString()) + " روز باقی‌مانده", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item { Text("این ماه", style = MaterialTheme.typography.titleMedium, color = text) }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = card),
                    border = androidx.compose.foundation.BorderStroke(1.dp, line)
                ) {
                    Column(Modifier.padding(19.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashboardMetric("درآمد", money(data.opt("monthly_income")), "↑", Color(0xFFECFDF3), good, Modifier.weight(1f))
                            DashboardMetric("هزینه", money(data.opt("monthly_expense")), "↓", Color(0xFFFEF2F2), danger, Modifier.weight(1f))
                            DashboardMetric("خالص", money(data.opt("net_monthly_cashflow")), "=", Color(0xFFF2F6F5), text, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = line)
                        FinanceRow("کل بدهی", money(data.opt("total_debt")), text, muted)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCard(
                        "تعهدات",
                        listOf(
                            "معوق" to money(data.opt("overdue_commitments")),
                            "این ماه" to money(data.opt("current_month_commitments")),
                            "ماه بعد" to money(data.opt("next_month_commitments")),
                            "آینده" to money(data.opt("future_commitments"))
                        ),
                        Modifier.weight(1f),
                        card, line, text, muted, danger
                    )
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = card),
                    border = androidx.compose.foundation.BorderStroke(1.dp, line)
                ) {
                    Column(Modifier.padding(19.dp)) {
                        Text("پیش‌بینی", style = MaterialTheme.typography.titleMedium, color = text)
                        FinanceRow("بودجه امن روزانه", money(data.opt("daily_safe_budget")), text, muted)
                        FinanceRow("خرج برآوردی باقی‌مانده", money(data.opt("projected_remaining_spend")), text, muted)
                        FinanceRow("موجودی پایان ماه", money(data.opt("projected_month_end_balance")), text, muted)
                    }
                }
            }
        } ?: item {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator(color = p)
            }
        }
    }
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

@Composable
fun Transactions(modifier: Modifier, quickType: String? = null, onQuickTypeConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(listOf<JSONObject>()) }
    var accounts by remember { mutableStateOf(listOf<JSONObject>()) }
    var categories by remember { mutableStateOf(listOf<JSONObject>()) }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(quickType) { if (quickType != null) show = true }
    fun load() { scope.launch { try {
        data = JSONArray(call(context, "/api/transactions/")).toObjects()
        accounts = JSONArray(call(context, "/api/accounts/")).toObjects()
        categories = JSONArray(call(context, "/api/categories/")).toObjects()
    } catch(e: Exception) { error = e.message ?: "خطا" } } }
    LaunchedEffect(Unit) { load() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("تراکنش‌ها", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { show = true }) { Text("تراکنش جدید") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn { items(data) { t ->
            ListItem(
                headlineContent = { Text(if (t.optString("transaction_type") == "INCOME") "درآمد" else "هزینه") },
                supportingContent = { Text(t.optString("description").ifBlank { t.optString("date") }) },
                trailingContent = { Text(money(t.opt("amount"))) }
            )
            HorizontalDivider()
        } }
    }
    if (show) TransactionDialog(quickType, accounts, categories, { show = false; onQuickTypeConsumed() }, { load() })
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
        suffix = { Text("تومان") }, singleLine = true,
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
    var installments by remember { mutableStateOf(listOf<JSONObject>()) }
    var accounts by remember { mutableStateOf(listOf<JSONObject>()) }
    var categories by remember { mutableStateOf(listOf<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var showNew by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            try {
                loans = JSONArray(call(context, "/api/loans/")).toObjects()
                accounts = JSONArray(call(context, "/api/accounts/")).toObjects()
                categories = JSONArray(call(context, "/api/categories/")).toObjects()
                selected?.let { loan ->
                    installments = JSONArray(
                        call(context, "/api/installments/?loan=" + loan.optInt("id"))
                    ).toObjects()
                }
            } catch (e: Exception) {
                error = e.message ?: "خطا"
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (selected == null) "وام و اقساط" else selected!!.optString("title"),
                style = MaterialTheme.typography.headlineSmall
            )
            if (selected == null) {
                Button(onClick = { showNew = true }) { Text("وام جدید") }
            } else {
                TextButton(onClick = { selected = null }) { Text("بازگشت") }
            }
        }

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (selected == null) {
            if (loans.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("هنوز وامی ثبت نشده", style = MaterialTheme.typography.titleMedium)
                        Text("برای ساخت برنامه اقساط، روی «وام جدید» بزنید.")
                    }
                }
            }

            LazyColumn {
                items(loans) { loan ->
                    ListItem(
                        headlineContent = { Text(loan.optString("title")) },
                        supportingContent = {
                            Text(
                                "اصل: " + money(loan.opt("principal_amount")) +
                                    " | هر قسط: " + money(loan.opt("installment_amount"))
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = {
                                selected = loan
                                scope.launch {
                                    try {
                                        installments = JSONArray(
                                            call(
                                                context,
                                                "/api/installments/?loan=" + loan.optInt("id")
                                            )
                                        ).toObjects()
                                    } catch (e: Exception) {
                                        error = e.message ?: "خطا"
                                    }
                                }
                            }) {
                                Text("اقساط")
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        } else {
            if (installments.isEmpty()) {
                Text("برای این وام هنوز قسطی وجود ندارد.")
            }

            LazyColumn {
                items(installments) { inst ->
                    val paid = inst.optString("status") == "PAID"
                    ListItem(
                        headlineContent = {
                            Text("قسط " + inst.optInt("installment_number"))
                        },
                        supportingContent = {
                            Text(
                                "سررسید: " + apiToJalali(inst.optString("due_date")) +
                                    " | " + inst.optString("status")
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(money(inst.opt("amount")))
                                if (!paid) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            try {
                                                val aid = accounts.firstOrNull()?.optInt("id", 0) ?: 0
                                                val cid = categories.firstOrNull {
                                                    it.optString("category_type") == "EXPENSE"
                                                }?.optInt("id", 0) ?: 0
                                                if (aid == 0 || cid == 0) {
                                                    error = "ابتدا یک حساب و دسته‌بندی هزینه داشته باشید"
                                                } else {
                                                    call(
                                                        context,
                                                        "/api/installments/" + inst.optInt("id") + "/pay/",
                                                        "POST",
                                                        JSONObject()
                                                            .put("amount", inst.opt("amount"))
                                                            .put("account_id", aid)
                                                            .put("category_id", cid)
                                                            .put("paid_date", todayApi())
                                                            .toString()
                                                    )
                                                    load()
                                                }
                                            } catch (e: Exception) {
                                                error = e.message ?: "خطا"
                                            }
                                        }
                                    }) {
                                        Text("پرداخت")
                                    }
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showNew) {
        LoanDialog(
            onClose = { showNew = false },
            onSaved = {
                showNew = false
                load()
            }
        )
    }
}

@Composable
fun LoanDialog(onClose:()->Unit,onSaved:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var showDate by remember{mutableStateOf(false)}
    var title by remember{mutableStateOf("")}
    var principal by remember{mutableStateOf("")}
    var interest by remember{mutableStateOf("")}
    var installment by remember{mutableStateOf("")}
    var count by remember{mutableStateOf("")}
    var start by remember{mutableStateOf(todayJalali())}
    var historyMode by remember{mutableStateOf("ALL_PAID")}
    var overdueCount by remember{mutableStateOf("")}
    var error by remember{mutableStateOf("")}

    val p = parseMoney(principal)
    val i = parseMoney(interest)
    val inst = parseMoney(installment)
    val n = count.toIntOrNull() ?: 0

    // Exactly two of interest / installment / count determine the third.
    val calculatedInterest = if (interest.isBlank() && inst > 0 && n > 0) (inst * n - p).coerceAtLeast(0) else null
    val calculatedInstallment = if (installment.isBlank() && i >= 0 && n > 0 && p > 0) ((p + i + n - 1) / n) else null
    val calculatedCount = if (count.isBlank() && i >= 0 && inst > 0 && p > 0) ((p + i + inst - 1) / inst).toInt() else null

    AlertDialog(
        onDismissRequest=onClose,
        title={Text("وام جدید")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                TextField(title,{title=it},label={Text("عنوان")},modifier=Modifier.fillMaxWidth())
                AmountField("اصل مبلغ وام",principal){principal=it}
                AmountField("سود کل وام (اختیاری)",interest){interest=it}
                AmountField("مبلغ هر قسط (اختیاری)",installment){installment=it}
                OutlinedTextField(
                    count,
                    {count=it.filter(Char::isDigit)},
                    label={Text("تعداد اقساط (اختیاری)")},
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                    modifier=Modifier.fillMaxWidth()
                )

                if (calculatedInterest != null) {
                    Text("سود محاسبه‌شده: " + money(calculatedInterest))
                }
                if (calculatedInstallment != null) {
                    Text("قسط محاسبه‌شده: " + money(calculatedInstallment))
                }
                if (calculatedCount != null) {
                    Text("تعداد اقساط محاسبه‌شده: " + fa(calculatedCount.toString()))
                }

                val finalInterest = if (interest.isBlank()) calculatedInterest ?: 0L else i
                val finalInstallment = if (installment.isBlank()) calculatedInstallment ?: 0L else inst
                val finalCount = if (count.isBlank()) calculatedCount ?: 0 else n
                val totalRepayment = p + finalInterest
                if (p > 0 && finalInterest >= 0) {
                    Text("مجموع بازپرداخت: " + money(totalRepayment))
                }
                if (finalCount > 0 && finalInstallment > 0) {
                    val last = totalRepayment - finalInstallment * (finalCount - 1)
                    Text("قسط آخر: " + money(last))
                }

                OutlinedButton(onClick={showDate=true},modifier=Modifier.fillMaxWidth()){
                    Text("شروع: "+start)
                }

                SimpleChoice(
                    "وضعیت اقساط گذشته",
                    listOf(
                        "ALL_PAID" to "اقساط گذشته پرداخت شده",
                        "OVERDUE_COUNT" to "تعدادی از اقساط گذشته باقی مانده"
                    ),
                    historyMode
                ) { historyMode = it }

                if(historyMode=="OVERDUE_COUNT"){
                    OutlinedTextField(
                        overdueCount,
                        {overdueCount=it.filter(Char::isDigit)},
                        label={Text("تعداد اقساط عقب‌افتاده")},
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                        modifier=Modifier.fillMaxWidth()
                    )
                }

                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
            }
        },
        confirmButton={
            Button(onClick={
                scope.launch{
                    try{
                        val finalInterest = if (interest.isBlank()) calculatedInterest ?: 0L else i
                        val finalInstallment = if (installment.isBlank()) calculatedInstallment ?: 0L else inst
                        val finalCount = if (count.isBlank()) calculatedCount ?: 0 else n
                        val overdue = overdueCount.toIntOrNull() ?: 0

                        if(title.trim().isBlank() || p<=0){
                            error="عنوان و اصل مبلغ وام را وارد کنید"
                        } else if(
                            finalInterest < 0 || finalInstallment <= 0 || finalCount < 1 ||
                            (interest.isBlank() && calculatedInterest == null) ||
                            (installment.isBlank() && calculatedInstallment == null) ||
                            (count.isBlank() && calculatedCount == null)
                        ){
                            error="حداقل دو مورد از سود، مبلغ قسط و تعداد اقساط را وارد کنید"
                        } else if(historyMode=="OVERDUE_COUNT" && overdue > finalCount){
                            error="تعداد اقساط عقب‌افتاده نمی‌تواند بیشتر از کل اقساط باشد"
                        } else {
                            val body=JSONObject()
                                .put("title",title.trim())
                                .put("loan_type","LOAN")
                                .put("principal_amount",p)
                                .put("interest_amount",finalInterest)
                                .put("installment_amount",finalInstallment)
                                .put("total_installments",finalCount)
                                .put("start_date",jalaliToApi(start))
                                .put("is_active",true)
                                .put("history_mode",historyMode)
                                .put("overdue_count",if(historyMode=="OVERDUE_COUNT") overdue else 0)

                            call(context,"/api/loans/","POST",body.toString())
                            onClose()
                            onSaved()
                        }
                    }catch(e:Exception){
                        error=e.message?:"خطا"
                    }
                }
            }){Text("ثبت")}
        },
        dismissButton={TextButton(onClick=onClose){Text("انصراف")}}
    )
    if (showDate) JalaliDateDialog(start) { start = it; showDate = false }
}
