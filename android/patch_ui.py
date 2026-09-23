from pathlib import Path

p = Path("android/app/src/main/java/ir/dastyaremali/app/MainActivity.kt")
s = p.read_text()

repls = [
("""fun AppShell(onLogout: () -> Unit) {
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
            else -> Home(Modifier.padding(p), onLogout, { page = 1 })
        }
    }
}""",
"""fun AppShell(onLogout: () -> Unit) {
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
}"""),
("""fun Home(modifier: Modifier, logout: () -> Unit, openTransactions: () -> Unit) {""",
 """fun Home(modifier: Modifier, logout: () -> Unit, openTransaction: (String) -> Unit) {"""),
("""onClick = openTransactions,""",
 """onClick = { openTransaction("INCOME") },"""),
("""onClick = openTransactions,""",
 """onClick = { openTransaction("EXPENSE") },"""),
("""fun Transactions(modifier: Modifier) {""",
 """fun Transactions(modifier: Modifier, quickType: String? = null, onQuickTypeConsumed: () -> Unit = {}) {"""),
("""    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }""",
 """    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(quickType) {
        if (quickType != null) show = true
    }"""),
("""            Button(onClick = { show = true }) { Text("تراکنش جدید") }""",
 """            Button(onClick = { show = true }) { Text("تراکنش جدید") }"""),
("""    if (show) TransactionDialog(accounts, categories, { show = false }, { load() })""",
 """    if (show) TransactionDialog(quickType, accounts, categories, { show = false; onQuickTypeConsumed() }, { load() })"""),
("""fun JalaliDateDialog(initial:String, onPicked:(String)->Unit) {
    var show by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf(initial) }
    if(show) AlertDialog(onDismissRequest={show=false}, title={Text("انتخاب تاریخ شمسی")}, text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            TextField(value,{ value=it.filter{ch->ch.isDigit()||ch=='/'} },label={Text("تاریخ شمسی")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
            Text("فرمت: سال/ماه/روز — مثال ۱۴۰۵/۰۷/۰۱")
        }
    },confirmButton={Button(onClick={if(value.matches(Regex("\\d{4}/\\d{1,2}/\\d{1,2}"))){onPicked(value);show=false}}){Text("تأیید")}},
      dismissButton={TextButton(onClick={show=false}){Text("انصراف")}})
}""",
 """@Composable
fun JalaliDateDialog(initial: String, onPicked: (String) -> Unit) {
    val context = LocalContext.current
    val parts = initial.replace("-", "/").split("/").mapNotNull { it.toIntOrNull() }
    val g = if (parts.size == 3) jalaliToGregorian(parts[0], parts[1], parts[2]) else {
        val c = Calendar.getInstance()
        Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
    DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(apiToJalali("%04d-%02d-%02d".format(year, month + 1, day))) },
        g.first, g.second - 1, g.third
    ).show()
}"""),
("""fun TransactionDialog(accounts: List<JSONObject>, categories: List<JSONObject>, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("EXPENSE") }""",
 """fun TransactionDialog(initialType: String?, accounts: List<JSONObject>, categories: List<JSONObject>, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initialType ?: "EXPENSE") }"""),
("""    AlertDialog(onDismissRequest = onClose, title = { Text(if (type == "INCOME") "ثبت درآمد" else "ثبت هزینه") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("هزینه") })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("درآمد") })
            }""",
 """    AlertDialog(onDismissRequest = onClose, title = { Text("تراکنش جدید") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("ثبت هزینه") })
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("ثبت درآمد") })
            }""")
]
for old, new in repls:
    if old not in s:
        raise SystemExit("Missing expected code block: " + old[:80].replace("\n"," "))
    s = s.replace(old, new, 1)

p.write_text(s)
