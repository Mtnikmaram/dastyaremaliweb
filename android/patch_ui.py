from pathlib import Path

p = Path("android/app/src/main/java/ir/dastyaremali/app/MainActivity.kt")
s = p.read_text()

def replace_once(old, new):
    global s
    if old not in s:
        raise SystemExit("Missing expected text: " + old[:100])
    s = s.replace(old, new, 1)

def replace_block(start_marker, end_marker, new_block):
    global s
    start = s.find(start_marker)
    if start < 0:
        raise SystemExit("Missing start marker: " + start_marker)
    end = s.find(end_marker, start)
    if end < 0:
        raise SystemExit("Missing end marker: " + end_marker)
    s = s[:start] + new_block + s[end:]

replace_block(
    "fun AppShell(onLogout: () -> Unit) {",
    "@Composable\nfun Home",
'''fun AppShell(onLogout: () -> Unit) {
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

''')

replace_once("fun Home(modifier: Modifier, logout: () -> Unit, openTransactions: () -> Unit) {",
            "fun Home(modifier: Modifier, logout: () -> Unit, openTransaction: (String) -> Unit) {")
replace_once("onClick = openTransactions,\n                    colors = ButtonDefaults.buttonColors",
            "onClick = { openTransaction(\"INCOME\") },\n                    colors = ButtonDefaults.buttonColors")
replace_once("onClick = openTransactions,\n                    colors = ButtonDefaults.outlinedButtonColors",
            "onClick = { openTransaction(\"EXPENSE\") },\n                    colors = ButtonDefaults.outlinedButtonColors")

replace_once("fun Transactions(modifier: Modifier) {",
            "fun Transactions(modifier: Modifier, quickType: String? = null, onQuickTypeConsumed: () -> Unit = {}) {")
replace_once('    var error by remember { mutableStateOf("") }\n    fun load()',
            '    var error by remember { mutableStateOf("") }\n    LaunchedEffect(quickType) { if (quickType != null) show = true }\n    fun load()')
replace_once("if (show) TransactionDialog(accounts, categories, { show = false }, { load() })",
            "if (show) TransactionDialog(quickType, accounts, categories, { show = false; onQuickTypeConsumed() }, { load() })")

replace_block(
    "@Composable\nfun JalaliDateDialog",
    "@Composable\nfun TransactionDialog",
'''@Composable
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

''')

replace_once("fun TransactionDialog(accounts: List<JSONObject>, categories: List<JSONObject>, onClose: () -> Unit, onSaved: () -> Unit) {",
            "fun TransactionDialog(initialType: String?, accounts: List<JSONObject>, categories: List<JSONObject>, onClose: () -> Unit, onSaved: () -> Unit) {")
replace_once('var type by remember { mutableStateOf("EXPENSE") }',
            'var type by remember { mutableStateOf(initialType ?: "EXPENSE") }')
replace_once('title = { Text(if (type == "INCOME") "ثبت درآمد" else "ثبت هزینه") }',
            'title = { Text("تراکنش جدید") }')
replace_once('''            Row {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("هزینه") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("درآمد") })
            }''',
'''            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("ثبت هزینه") })
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("ثبت درآمد") })
            }''')

p.write_text(s)
