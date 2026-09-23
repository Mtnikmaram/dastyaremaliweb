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

private fun Context.token(): String? = getSharedPreferences(PREF, 0).getString("access", null)
private fun Context.saveToken(v: String) = getSharedPreferences(PREF, 0).edit().putString("access", v).apply()
private fun Context.logout() = getSharedPreferences(PREF, 0).edit().clear().apply()

private suspend fun call(context: Context, path: String, method: String = "GET", body: String? = null): String =
    withContext(Dispatchers.IO) {
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        context.token()?.let { c.setRequestProperty("Authorization", "Bearer " + it) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299) {
            val msg = try { JSONObject(text).optString("detail", text) } catch (_: Exception) { text }
            throw IllegalStateException(if (msg.isBlank()) "خطا در ارتباط با سرور" else msg)
        }
        text
    }

private fun money(v: Any?): String {
    val n = v?.toString()?.toDoubleOrNull()?.toLong() ?: 0
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).format(n) + " تومان"
}

private fun today(): String {
    val c = Calendar.getInstance()
    return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

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
                    context.saveToken(r.getString("access")); done()
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
    var show by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    fun load() { scope.launch { try {
        val a = JSONArray(call(context, "/api/transactions/"))
        data = List(a.length()) { a.getJSONObject(it) }
    } catch(e: Exception) { error = e.message ?: "خطا" } } }
    LaunchedEffect(Unit) { load() }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("تراکنش‌ها", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { amount = ""; error = ""; show = true }) { Text("ثبت") }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn {
            items(data) { t ->
                ListItem(
                    headlineContent = { Text(if (t.optString("transaction_type") == "INCOME") "درآمد" else "هزینه") },
                    supportingContent = { Text(t.optString("description")) },
                    trailingContent = { Text(money(t.opt("amount"))) }
                )
                HorizontalDivider()
            }
        }
    }
    if (show) {
        AlertDialog(onDismissRequest = { show = false },
            title = { Text("ثبت هزینه") },
            text = { AmountField("مبلغ", amount) { amount = it } },
            confirmButton = { Button(onClick = {
                scope.launch { try {
                    val body = JSONObject().put("amount", amount.toLongOrNull() ?: 0).put("transaction_type", "EXPENSE").put("date", today())
                    call(context, "/api/transactions/", "POST", body.toString()); show = false; load()
                } catch(e: Exception) { error = e.message ?: "خطا"; show = false } }
            }) { Text("ثبت") } },
            dismissButton = { TextButton(onClick = { show = false }) { Text("انصراف") } })
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
    var data by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { try {
        val a = JSONArray(call(context, "/api/accounts/")); data = List(a.length()) { a.getJSONObject(it) }
    } catch(e: Exception) { error = e.message ?: "خطا" } }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("حساب‌ها", style = MaterialTheme.typography.headlineSmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn { items(data) { a -> ListItem(headlineContent={Text(a.optString("name"))}, trailingContent={Text(money(a.opt("initial_balance")))}) } }
    }
}

@Composable
fun Loans(modifier: Modifier) {
    val context = LocalContext.current
    var data by remember { mutableStateOf(listOf<JSONObject>()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { try {
        val a = JSONArray(call(context, "/api/loans/")); data = List(a.length()) { a.getJSONObject(it) }
    } catch(e: Exception) { error = e.message ?: "خطا" } }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("وام و اقساط", style = MaterialTheme.typography.headlineSmall)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn { items(data) { l -> ListItem(headlineContent={Text(l.optString("title"))}, trailingContent={Text(money(l.opt("principal_amount")))}) } }
    }
}
