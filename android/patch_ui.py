from pathlib import Path
import re

p = Path("android/app/src/main/java/ir/dastyaremali/app/MainActivity.kt")
s = p.read_text()

def sub(pattern, repl, count=1):
    global s
    ns, n = re.subn(pattern, repl, s, count=count, flags=re.S)
    if n != count:
        raise SystemExit(f"Patch pattern not found: {pattern[:120]}")
    s = ns

sub(r'fun AppShell\(onLogout: \(\) -> Unit\) \{.*?\n\}\n\n@Composable\nfun Home',
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

@Composable
fun Home''')

sub(r'fun Home\(modifier: Modifier, logout: \(\) -> Unit, openTransactions: \(\) -> Unit\) \{',
    'fun Home(modifier: Modifier, logout: () -> Unit, openTransaction: (String) -> Unit) {')
sub(r'onClick = openTransactions,\n(\s*colors = ButtonDefaults\.buttonColors',
    r'onClick = { openTransaction("INCOME") },\n\1colors = ButtonDefaults.buttonColors', count=1)
sub(r'onClick = openTransactions,\n(\s*colors = ButtonDefaults\.outlinedButtonColors',
    r'onClick = { openTransaction("EXPENSE") },\n\1colors = ButtonDefaults.outlinedButtonColors', count=1)

sub(r'fun Transactions\(modifier: Modifier\) \{',
    'fun Transactions(modifier: Modifier, quickType: String? = null, onQuickTypeConsumed: () -> Unit = {}) {')
sub(r'(var error by remember \{ mutableStateOf\("") \}\n)',
    r'\1    LaunchedEffect(quickType) { if (quickType != null) show = true }\n', count=1)
sub(r'if \(show\) TransactionDialog\(accounts, categories, \{ show = false \}, \{ load\(\) \}\)',
    'if (show) TransactionDialog(quickType, accounts, categories, { show = false; onQuickTypeConsumed() }, { load() })')

sub(r'@Composable\nfun JalaliDateDialog\(.*?\n\}\n\n@Composable\nfun TransactionDialog',
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

@Composable
fun TransactionDialog''')

sub(r'fun TransactionDialog\(accounts: List<JSONObject>, categories: List<JSONObject>,',
    'fun TransactionDialog(initialType: String?, accounts: List<JSONObject>, categories: List<JSONObject>,')
sub(r'var type by remember \{ mutableStateOf\("EXPENSE"\) \}',
    'var type by remember { mutableStateOf(initialType ?: "EXPENSE") }', count=1)
sub(r'title = \{ Text\(if \(type == "INCOME"\) "ثبت درآمد" else "ثبت هزینه"\) \}',
    'title = { Text("تراکنش جدید") }', count=1)
sub(r'''Row \{
                FilterChip\(selected = type == "EXPENSE", onClick = \{ type = "EXPENSE"; category = 0 \}, label = \{ Text\("هزینه"\) \}\)
                Spacer\(Modifier\.width\(8\.dp\)\)
                FilterChip\(selected = type == "INCOME", onClick = \{ type = "INCOME"; category = 0 \}, label = \{ Text\("درآمد"\) \}\)
            \}''',
'''Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = type == "EXPENSE", onClick = { type = "EXPENSE"; category = 0 }, label = { Text("ثبت هزینه") })
                FilterChip(selected = type == "INCOME", onClick = { type = "INCOME"; category = 0 }, label = { Text("ثبت درآمد") })
            }''')

p.write_text(s)
