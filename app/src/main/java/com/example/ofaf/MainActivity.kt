package com.example.ofaf

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import androidx.room.ColumnInfo
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// =====================================================================
// 1. STORAGE CONFIG
// =====================================================================
val Context.dataStore by preferencesDataStore(name = "performance_settings")
val USER_NAME_KEY = stringPreferencesKey("user_name")

// =====================================================================
// 2. ROOM DB
// =====================================================================
@Entity(tableName = "hotels")
data class Hotel(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "price") val price: Int,
    @ColumnInfo(name = "description") val description: String
)

@Dao
interface HotelDao {
    @Query("SELECT * FROM hotels WHERE price < 1000000")
    suspend fun getCheapRooms(): List<Hotel>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHotels(hotels: List<Hotel>)
    @Query("DELETE FROM hotels")
    suspend fun deleteAllHotels()
}

@Database(entities = [Hotel::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hotelDao(): HotelDao
}

// =====================================================================
// 3. WORKMANAGER - BACKOFF & QUEUE
// =====================================================================
class SyncWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val attempt = runAttemptCount
        // Giả lập: Lần đầu thất bại để kích hoạt Backoff
        return if (attempt < 1) Result.retry() else Result.success()
    }
}

data class HotelComplex(val hotel: Hotel, val roomTypes: List<String>, val reviews: List<String>)

// =====================================================================
// 4. VIEWMODEL
// =====================================================================
class DemoViewModel(
    private val hotelDao: HotelDao,
    private val context: Context,
    private val workManager: WorkManager
) : ViewModel() {

    val logs = MutableStateFlow<List<String>>(emptyList())
    private val memoryStorage = mutableListOf<HotelComplex>()
    val legacyName = MutableStateFlow(context.getSharedPreferences("legacy", Context.MODE_PRIVATE).getString("name", "Chưa có") ?: "Chưa có")

    val networkStatus = MutableStateFlow("Chưa kiểm tra")
    val fetchSource = MutableStateFlow("N/A")
    val isFetching = MutableStateFlow(false)

    val modernName = context.dataStore.data.map { it[USER_NAME_KEY] ?: "Chưa có" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Chưa có")

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.value = listOf("[$time] $msg") + logs.value
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val actNw = cm.getNetworkCapabilities(cm.activeNetwork)
        val online = actNw?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } ?: false
        networkStatus.value = if (online) "ONLINE" else "OFFLINE"
        return online
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            hotelDao.deleteAllHotels()
            val longDesc = "Mô tả dài... ".repeat(20)
            val samples = List(10000) { i -> Hotel(i.toString(), "Hotel $i", (500000..1500000).random(), longDesc) }
            hotelDao.insertHotels(samples)
            addLog("Đã nạp 10,000 bản ghi vào Room.")
        }
    }

    // --- WRITE STRATEGIES ---

    fun writeOnlyOnline(data: String) {
        if (!isOnline()) {
            addLog("LỖI: Only Online thất bại - Không có mạng.")
        } else {
            addLog("ONLINE: Đã ghi '$data' lên Cloud trực tiếp.")
        }
    }

    fun writeQueued(data: String) {
        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueue(workRequest)
        addLog("QUEUED: Đã đưa vào WorkManager (Thử lại Exponential Backoff nếu lỗi).")
    }

    fun writeLazy(data: String) {
        addLog("LAZY: Cập nhật UI ngay lập tức.")
        viewModelScope.launch {
            delay(3000) // Giả lập trì hoãn
            context.dataStore.edit { it[USER_NAME_KEY] = data }
            addLog("LAZY: Đã đồng bộ dữ liệu xuống Disk sau 3s.")
        }
    }

    // --- READ & PERFORMANCE ---
    fun readStrategyRAM() {
        viewModelScope.launch(Dispatchers.Default) {
            isFetching.value = true
            addLog("RAM: Nạp 10k đối tượng lồng nhau...")
            val longStr = "Nặng... ".repeat(30)
            if (memoryStorage.isEmpty()) repeat(10000) { i -> memoryStorage.add(HotelComplex(Hotel(i.toString(),"RAM $i", 800000, longStr), List(5){"RT"}, List(10){"REV"})) }
            val start = System.currentTimeMillis()
            val filtered = memoryStorage.filter { it.hotel.price < 1000000 }
            addLog("RAM: Xong trong ${System.currentTimeMillis() - start}ms.")
            isFetching.value = false
        }
    }

    fun readStrategyDisk() {
        viewModelScope.launch(Dispatchers.IO) {
            isFetching.value = true
            if (!isOnline()) {
                val rooms = hotelDao.getCheapRooms()
                addLog("DISK: Lấy ${rooms.size} phòng (Offline Mode).")
            } else {
                addLog("CLOUD: Đang tải từ server...")
                delay(2000)
                addLog("CLOUD: Xong.")
            }
            isFetching.value = false
        }
    }

    fun saveLegacy(n: String) { addLog("Chặn UI..."); Thread.sleep(2500); context.getSharedPreferences("legacy", 0).edit().putString("name", n).apply(); legacyName.value = n; addLog("Xong SP.") }
    fun saveModern(n: String) { addLog("Luồng nền..."); viewModelScope.launch{ delay(2500); context.dataStore.edit{ it[USER_NAME_KEY] = n }; addLog("Xong DS.") } }
    fun demoFiles() { viewModelScope.launch{ val f = File(context.getExternalFilesDir(null), "t.pdf"); f.writeBytes(ByteArray(100)); addLog("Ghi File: ${f.name}") } }
}

@Composable
fun AppUI(viewModel: DemoViewModel) {
    val logs by viewModel.logs.collectAsState()
    val modernName by viewModel.modernName.collectAsState()
    val legacyName by viewModel.legacyName.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isFetching by viewModel.isFetching.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val infiniteTransition = rememberInfiniteTransition()
    val angle by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 360f, animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing)))

    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Architecture & Persistence", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(modifier = Modifier.size(24.dp), progress = angle / 360f, color = Color.Cyan)
        }

        OutlinedTextField(value = inputText, onValueChange = { inputText = it }, label = { Text("Dữ liệu thử nghiệm") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))

        // Card Performance
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
            Column(Modifier.padding(8.dp)) {
                Text("Bài 1: SP (Lag) vs DataStore (Mượt)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.saveLegacy(inputText) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("SP", fontSize = 10.sp) }
                    Button(onClick = { viewModel.saveModern(inputText) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007A5E))) { Text("DS", fontSize = 10.sp) }
                }
            }
        }

        // Card Write Strategy (MỚI)
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
            Column(Modifier.padding(8.dp)) {
                Text("Bài 2: Write Strategy & Reliability", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { viewModel.writeOnlyOnline(inputText) }, modifier = Modifier.weight(1f)) { Text("OnlineOnly", fontSize = 8.sp) }
                    Button(onClick = { viewModel.writeQueued(inputText) }, modifier = Modifier.weight(1f)) { Text("Queued", fontSize = 8.sp) }
                    Button(onClick = { viewModel.writeLazy(inputText) }, modifier = Modifier.weight(1f)) { Text("Lazy", fontSize = 8.sp) }
                }
            }
        }

        // Card Read Strategy
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
            Column(Modifier.padding(8.dp)) {
                Text("Bài 3: Read Strategy ($networkStatus)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.readStrategyDisk() }, enabled = !isFetching, modifier = Modifier.weight(1f)) { Text("Disk (Room)", fontSize = 10.sp) }
                    Button(onClick = { viewModel.readStrategyRAM() }, enabled = !isFetching, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) { Text("RAM (Nested)", fontSize = 10.sp) }
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(8.dp), reverseLayout = true) {
            items(logs) { log -> Text(text = log, color = if(log.contains("LỖI")) Color.Red else if(log.contains("RAM")) Color.Yellow else Color.Green, style = MaterialTheme.typography.bodySmall) }
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { viewModel.demoFiles() }, modifier = Modifier.weight(1f)) { Text("ScopedFiles", fontSize = 10.sp) }
            Text("DS Val: $modernName", style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "hotel_complex_db").build()
        val wm = WorkManager.getInstance(this)
        setContent {
            val viewModel: DemoViewModel = viewModel { DemoViewModel(db.hotelDao(), applicationContext, wm) }
            MaterialTheme { Surface { AppUI(viewModel) } }
        }
    }
}
