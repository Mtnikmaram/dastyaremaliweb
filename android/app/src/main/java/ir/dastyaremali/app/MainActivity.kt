package ir.dastyaremali.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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

private fun money(v: Any?): String {
    val n = v?.toString()?.toDoubleOrNull()?.toLong() ?: 0
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n) + " تومان"
}

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
        TextField(pass, { pass = it }, label = { Text("گذرواژه") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
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
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("خانه", "تراکنش", "اقساط", "حساب").forEachIndexed { i, title ->
                NavigationBarItem(selected = page == i, onClick = { page = i }, icon = {}, label = { Text(title) })
            }
        }
    }) { p ->
        when (page) {
            1 -> Transactions(Modifier.padding(p))
            2 -> Loans(Modifier.padding(p))
            3 -> Accounts(Modifier.padding(p))
            else -> Home(Modifier.padding(p), onLogout)
        }
    }
}

@Composable
fun Home(modifier: Modifier, logout: () -> Unit) {
    val context = LocalContext.current
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { d = JSONObject(call(context, "/api/dashboard/")) } catch (e: Exception) { error = e.message ?: "خطا" }
    }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("داشبورد مالی", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = logout) { Text("خروج") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        d?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("موجودی فعلی"); Text(money(it.opt("current_balance")), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp)); Text("قابل خرج امن"); Text(money(it.opt("safe_to_spend")), style = MaterialTheme.typography.titleLarge)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("درآمد این ماه: " + money(it.opt("monthly_income")))
            Text("هزینه این ماه: " + money(it.opt("monthly_expense")))
            Text("کل بدهی: " + money(it.opt("total_debt")))
        } ?: CircularProgressIndicator()
    }
}

@Composable
fun Transactions(modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(listOf<JSONObject>()) }
    var accounts by remember { mutableStateOf(listOf<JSONObject>()) }
    var categories by remember { mutableStateOf(listOf<JSONObject>()) }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
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
    if (show) TransactionDialog(accounts, categories, { show = false }, { load() })
}

@Composable
fun JalaliDateDialog(initial:String, onPicked:(String)->Unit) {
    var show by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf(initial) }
    if(show) AlertDialog(onDismissRequest={show=false}, title={Text("انتخاب تاریخ شمسی")}, text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            TextField(value,{ value=it.filter{ch->ch.isDigit()||ch=='/'} },label={Text("تاریخ (مثلاً 1405/07/01)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            Text("تاریخ را به صورت سال/ماه/روز وارد کنید.")
        }
    },confirmButton={Button(onClick={if(value.matches(Regex("\\d{4}/\\d{1,2}/\\d{1,2}"))){onPicked(value);show=false}}){Text("تأیید")}},
      dismissButton={TextButton(onClick={show=false}){Text("انصراف")}})
}

@Composable
fun TransactionDialog(accounts: List<JSONObject>, categories: List<JSONObject>, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("EXPENSE") }
    var amount by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(accounts.firstOrNull()?.optInt("id", 0) ?: 0) }
    var category by remember { mutableStateOf(0) }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(todayJalali()) }
    var error by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text(if (type == "INCOME") "ثبت درآمد" else "ثبت هزینه") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("هزینه") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("درآمد") })
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
            val n = amount.toLongOrNull() ?: 0
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
    OutlinedTextField(value, { change(it.filter(Char::isDigit)) }, label = { Text(label) },
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
                val body = JSONObject().put("name", name.trim()).put("account_type", type).put("initial_balance", balance.toLongOrNull() ?: 0).put("is_active", true)
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
    fun load() { scope.launch { try {
        loans = JSONArray(call(context, "/api/loans/")).toObjects()
        accounts = JSONArray(call(context, "/api/accounts/")).toObjects()
        categories = JSONArray(call(context, "/api/categories/")).toObjects()
        selected?.let { loan -> installments = JSONArray(call(context, "/api/installments/?loan=" + loan.optInt("id"))).toObjects() }
    } catch (e: Exception) { error = e.message ?: "خطا" } } }
    LaunchedEffect(Unit) { load() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selected == null) "وام و اقساط" else selected!!.optString("title"), style = MaterialTheme.typography.headlineSmall)
            if (selected == null) Button(onClick = { showNew = true }) { Text("وام جدید") }
            else TextButton(onClick = { selected = null }) { Text("بازگشت") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (selected == null) LazyColumn { items(loans) { loan ->
            ListItem(headlineContent = { Text(loan.optString("title")) },
                supportingContent = { Text("اصل: " + money(loan.opt("principal_amount")) + " | هر قسط: " + money(loan.opt("installment_amount"))) },
                trailingContent = { TextButton(onClick = {
                    selected = loan
                    scope.launch { try { installments = JSONArray(call(context, "/api/installments/?loan=" + loan.optInt("id"))).toObjects() }
                    catch (e: Exception) { error = e.message ?: "خطا" } }
                }) { Text("اقساط") } })
            HorizontalDivider()
        } } else LazyColumn { items(installments) { inst ->
            val paid = inst.optString("status") == "PAID"
            ListItem(headlineContent = { Text("قسط " + inst.optInt("installment_number")) },
                supportingContent = { Text("سررسید: " + apiToJalali(inst.optString("due_date")) + " | " + inst.optString("status")) },
                trailingContent = { androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(money(inst.opt("amount")))
                    if (!paid) TextButton(onClick = { scope.launch { try {
                        val aid = accounts.firstOrNull()?.optInt("id", 0) ?: 0
                        val cid = categories.firstOrNull { it.optString("category_type") == "EXPENSE" }?.optInt("id", 0) ?: 0
                        if (aid == 0 || cid == 0) error = "ابتدا یک حساب و دسته‌بندی هزینه داشته باشید"
                        else { call(context, "/api/installments/" + inst.optInt("id") + "/pay/", "POST", JSONObject().put("amount", inst.opt("amount")).put("account_id", aid).put("category_id", cid).put("paid_date", todayApi()).toString()); load() }
                    } catch (e: Exception) { error = e.message ?: "خطا" } } }) { Text("پرداخت") }
                } })
            HorizontalDivider()
        } } }
    if (showNew) LoanDialog(onClose = { showNew = false }, onSaved = { showNew = false; load() })
}
@Composable
fun LoanDialog(onClose:()->Unit,onSaved:()->Unit){
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var showDate by remember{mutableStateOf(false)}; var title by remember{mutableStateOf("")}; var principal by remember{mutableStateOf("")}; var installment by remember{mutableStateOf("")}; var count by remember{mutableStateOf("")}; var start by remember{mutableStateOf(todayJalali())}; var error by remember{mutableStateOf("")}
    AlertDialog(onDismissRequest=onClose,title={Text("وام جدید")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        TextField(title,{title=it},label={Text("عنوان")},modifier=Modifier.fillMaxWidth())
        AmountField("مبلغ وام",principal){principal=it}; AmountField("مبلغ قسط",installment){installment=it}
        OutlinedTextField(count,{count=it.filter(Char::isDigit)},label={Text("تعداد اقساط")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={showDate=true},modifier=Modifier.fillMaxWidth()){Text("شروع: "+start)}
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    }},confirmButton={Button(onClick={scope.launch{try{
        val body=JSONObject().put("title",title.trim()).put("loan_type","LOAN").put("principal_amount",principal.toLongOrNull()?:0).put("installment_amount",installment.toLongOrNull()?:0).put("total_installments",count.toIntOrNull()?:0).put("start_date",jalaliToApi(start)).put("is_active",true)
        if(title.isBlank()||principal.toLongOrNull()?:0<=0||installment.toLongOrNull()?:0<=0||count.toIntOrNull()?:0<1)error="اطلاعات وام را کامل کنید" else {call(context,"/api/loans/","POST",body.toString());onClose();onSaved()}
    }catch(e:Exception){error=e.message?:"خطا"}}}){Text("ثبت")}},dismissButton={TextButton(onClick=onClose){Text("انصراف")}})
    if (showDate) JalaliDateDialog(start) { start = it; showDate = false }
}
