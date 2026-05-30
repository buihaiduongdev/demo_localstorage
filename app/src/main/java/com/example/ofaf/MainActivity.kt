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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow

// KEY-VALUE
val Context.dataStore by preferencesDataStore(name = "performance_settings")
val USER_NAME_KEY = stringPreferencesKey("user_name")

//STRUCTURED
@Entity(tableName = "hotels")
data class Hotel(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "price") val price: Int,
    @ColumnInfo(name = "description") val description: String
)

@Entity(tableName = "room_types")
data class RoomType(
    @PrimaryKey(autoGenerate = true) val rtId: Int = 0,
    val hotelId: String,
    val typeName: String,
    val info: String
)

@Entity(tableName = "reviews")
data class Review(
    @PrimaryKey(autoGenerate = true) val revId: Int = 0,
    val hotelId: String,
    val user: String,
    val comment: String
)

//STRUCTURED DATA MODELS FOR ROOM
data class HotelWithDetails(
    @Embedded val hotel: Hotel,
    @Relation(
        parentColumn = "id",
        entityColumn = "hotelId"
    )
    val roomTypes: List<RoomType>,
    @Relation(
        parentColumn = "id",
        entityColumn = "hotelId"
    )
    val reviews: List<Review>
)

@Dao
interface HotelDao {
    @Query("SELECT COUNT(*) FROM hotels")
    suspend fun getCount(): Int

    @Transaction
    @Query("SELECT * FROM hotels WHERE price < 1000000")
    suspend fun getCheapHotelsWithDetails(): List<HotelWithDetails>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHotels(hotels: List<Hotel>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoomTypes(types: List<RoomType>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReviews(reviews: List<Review>)

}

//ROOM
@Database(entities = [Hotel::class, RoomType::class, Review::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hotelDao(): HotelDao
}

data class HotelComplex(
    val hotel: Hotel,
    val roomTypes: List<RoomType>,
    val reviews: List<Review>
)

//QUEUE
class SyncFavoriteWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        delay(1000)
        
        val attempt = runAttemptCount
        if (attempt < 3) {
            return Result.retry()
        }
        return Result.success()
    }
}


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

    // Theo dõi trạng thái Worker
    val workInfo = workManager.getWorkInfosByTagFlow("sync_demo")
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Lazy Write state
    val videoProgress = MutableStateFlow(0f)
    val serverVideoProgress = MutableStateFlow(0f)
    val isSyncing = MutableStateFlow(false)

    val modernName = context.dataStore.data.map { it[USER_NAME_KEY] ?: "Chưa có" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Chưa có")

    private fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs.value = listOf("[$time] $msg") + logs.value
    }

    private fun checkOnline(): Boolean {
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
            val currentCount = hotelDao.getCount()
            if (currentCount > 0) {
                addLog("Disk: Đã có $currentCount bản ghi. Không nạp thêm (Ưu điểm của Room: Dữ liệu tồn tại vĩnh viễn trên ổ đĩa).")
                return@launch
            }

            addLog("Disk: Đang nạp 100 Hotel mẫu (lần đầu khởi tạo)...")
            val startTime = System.currentTimeMillis()
            
            val hotels = mutableListOf<Hotel>()
            val types = mutableListOf<RoomType>()
            val reviews = mutableListOf<Review>()

            repeat(100) { i ->
                val uniqueStr = "Mô tả của khách sạn $i " + UUID.randomUUID().toString().take(8)
                val hId = UUID.randomUUID().toString().take(8)
                
                val hotel = Hotel(hId, "Hotel $i", (500000..1500000).random(), uniqueStr)
                hotels.add(hotel)
                
                repeat(2) { types.add(RoomType(hotelId = hId, typeName = "Loại $it", info = "Phòng $it của $hId")) }
                repeat(2) { reviews.add(Review(hotelId = hId, user = "User $it", comment = "Review $it cho $hId")) }
            }

            hotelDao.insertHotels(hotels)
            hotelDao.insertRoomTypes(types)
            hotelDao.insertReviews(reviews)
            
            val endTime = System.currentTimeMillis()
            addLog("Xong! Đã lưu 100 khách sạn vào Room trong ${endTime - startTime}ms.")
        }

        // Hiện thực Lazy Write: Gom các thay đổi và đồng bộ sau 3 giây không có thay đổi mới (Debounce)
        viewModelScope.launch {
            videoProgress
                .debounce(3000) 
                .collect { progress ->
                    if (progress > 0) {
                        isSyncing.value = true
                        addLog("Lazy Write: Đang bắt đầu đồng bộ ${progress.toInt()}% lên Server...")
                        delay(1500) // Giả lập độ trễ truyền tải
                        serverVideoProgress.value = progress
                        isSyncing.value = false
                        addLog("Lazy Write: Đã cập nhật Server thành công.")
                    }
                }
        }
    }

    fun saveLegacy(newName: String) {
        addLog("Bắt đầu lưu SharedPreferences (Chặn UI)...")
        Thread.sleep(2500) 
        context.getSharedPreferences("legacy", Context.MODE_PRIVATE).edit().putString("name", newName).apply()
        legacyName.value = newName
    }

    fun saveModern(newName: String) {
        addLog("Bắt đầu lưu DataStore (Luồng nền)...")
        viewModelScope.launch(Dispatchers.IO) {
            delay(2500) 
            context.dataStore.edit {
                it[USER_NAME_KEY] = newName
            }
        }
    }

    fun demoScopedStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pdfFile = File(context.getExternalFilesDir(null), "ticket.pdf")
                pdfFile.writeBytes(ByteArray(1024 * 100))
                addLog("Thành công Scoped Storage: Lưu tại storage/emulated/0/Android/data/com.example.ofaf/files/${pdfFile.name}")
            } catch (e: Exception) { addLog("Lỗi: ${e.message}") }
        }
    }

    fun readStrategyRAM() {
        viewModelScope.launch(Dispatchers.Default) {
            isFetching.value = true
            memoryStorage.clear() // Xóa cũ để nạp mới
            addLog("RAM Test: Đang nạp 100 đối tượng vào RAM...")

            try {
                repeat(100) { i ->
                    val hId = "RAM_$i"
                    val h = Hotel(hId, "RAM Hotel $i", (100000..2000000).random(), "RAM Data $i")
                    val complex = HotelComplex(
                        hotel = h,
                        roomTypes = List(2) { RoomType(hotelId = hId, typeName = "Type $it", info = "Info $it") },
                        reviews = List(2) { Review(hotelId = hId, user = "User $it", comment = "Comment $it") }
                    )
                    memoryStorage.add(complex)
                }
                
                val startTime = System.currentTimeMillis()
                val filtered = memoryStorage.filter { it.hotel.price < 1000000 }
                val endTime = System.currentTimeMillis()

                fetchSource.value = "RAM"
                addLog("RAM: Đã lọc ${filtered.size} khách sạn < 1tr trong ${endTime - startTime}ms.")
                
                // Log chi tiết từng object
                filtered.forEach { 
                    addLog("-> [RAM] ${it.hotel.name}: ${it.hotel.price}đ (ID: ${it.hotel.id})")
                }
            } catch (e: Exception) {
                addLog("Lỗi RAM: ${e.message}")
            }
            isFetching.value = false
        }
    }

    fun readStrategyNetworkMonitor() {
        viewModelScope.launch(Dispatchers.IO) {
            isFetching.value = true
            val online = checkOnline()
            if (!online) {
                val startTime = System.currentTimeMillis()
                val results = hotelDao.getCheapHotelsWithDetails()
                val endTime = System.currentTimeMillis()
                fetchSource.value = "LOCAL (Room)"

                addLog("Disk: Lấy ${results.size} Hotel < 1tr từ Room trong ${endTime - startTime}ms.")
                // Log chi tiết từng object
                results.forEach {
                    addLog("-> [Room] ${it.hotel.name}: ${it.hotel.price}đ - ${it.roomTypes.size} loại phòng")
                }
            } else {
                fetchSource.value = "REMOTE (Cloud API)"
                addLog("Online: Đang tải dữ liệu từ Server...")
                delay(2000)
                addLog("Online: Đã tải dữ liệu mới nhất.")
            }
            isFetching.value = false
        }
    }

    // 1. Online-only Write: Chỉ cho phép khi có mạng
    fun onlineOnlyWritePayment() {
        viewModelScope.launch {
            if (checkOnline()) {
                addLog("Online-only: Đang xử lý thanh toán...")
                delay(1500)
                addLog("Thành công: Đã thanh toán trực tuyến.")
            } else {
                addLog("Lỗi: Không có mạng. Tác vụ thanh toán bị hủy để bảo mật.")
            }
        }
    }

    // 2. Queued Write with Exponential Backoff
    fun writeStrategyQueued() {
        // Hủy worker cũ nếu có để demo lại từ đầu
        workManager.cancelAllWorkByTag("sync_demo")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val work = OneTimeWorkRequestBuilder<SyncFavoriteWorker>()
            .setConstraints(constraints)
            .addTag("sync_demo")
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                2,
                TimeUnit.SECONDS
            )
            .build()

        workManager.enqueue(work)
        addLog("Queued: Đã bắt đầu Task với Exponential Backoff (2s, 4s, 8s...).")
    }

    //3. Lazy
    fun updateVideoProgress(progress: Float) {
        videoProgress.value = progress
        // Lưu local ngay lập tức (nhanh)
        // syncToServer sẽ được xử lý bởi collect bên trên (Lazy)
    }
}

@Composable
fun AppUI(viewModel: DemoViewModel) {
    val logs by viewModel.logs.collectAsState()
    val modernName by viewModel.modernName.collectAsState()
    val legacyName by viewModel.legacyName.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val isFetching by viewModel.isFetching.collectAsState()
    val videoProgress by viewModel.videoProgress.collectAsState()
    val serverVideoProgress by viewModel.serverVideoProgress.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val workInfo by viewModel.workInfo.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val infiniteTransition = rememberInfiniteTransition()
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing))
    )

    Column(modifier = Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync Strategies & Performance", style = MaterialTheme.typography.titleMedium, color = Color.Black, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            CircularProgressIndicator(modifier = Modifier.size(20.dp), progress = angle / 360f, color = Color.Blue)
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            label = { Text("Dữ liệu test") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )

        Spacer(Modifier.height(8.dp))

        // BÀI 1 & 2
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))) {
            Column(Modifier.padding(12.dp)) {
                Text("Bài 1: Key-Value & File Data", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.saveLegacy(inputText) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) { Text("SharedPrefs", fontSize = 10.sp) }
                    Button(onClick = { viewModel.saveModern(inputText) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007A5E))) { Text("DataStore", fontSize = 10.sp) }
                }
                Text("SP: $legacyName | DS: $modernName", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { viewModel.demoScopedStorage() }, modifier = Modifier.fillMaxWidth()) { Text("Scoped Files (Internal Storage)", fontSize = 10.sp) }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
            Column(Modifier.padding(12.dp)) {
                Text("Bài 2: Structured Data", style = MaterialTheme.typography.titleSmall)
                Text("Trạng thái mạng: $networkStatus", color = if(networkStatus == "ONLINE") Color.Green else Color.Red, style = MaterialTheme.typography.bodySmall)

                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.readStrategyNetworkMonitor() }, enabled = !isFetching, modifier = Modifier.weight(1f)) { Text("Disk (Room)", fontSize = 10.sp) }
                    Button(onClick = { viewModel.readStrategyRAM() }, enabled = !isFetching, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) { Text("RAM (Array Map)", fontSize = 10.sp) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF9C4))) {
            Column(Modifier.padding(12.dp)) {
                Text("Bài 3: Sync Strategies", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.onlineOnlyWritePayment() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBC02D))) { Text("Write Online Only", fontSize = 10.sp) }
                    Button(onClick = { viewModel.writeStrategyQueued() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))) { Text("Write Queued + Backoff", fontSize = 10.sp) }
                }

                // UI Theo dõi Worker trực quan
                workInfo?.let { info ->
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.5f)).padding(8.dp)) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Worker Status: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text(info.state.name, color = if(info.state == WorkInfo.State.SUCCEEDED) Color(0xFF007A5E) else Color.Blue, style = MaterialTheme.typography.bodySmall)
                                if (info.state == WorkInfo.State.RUNNING) {
                                    Spacer(Modifier.width(8.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                }
                            }
                            Text("Số lần thử (Run Attempt): ${info.runAttemptCount}", style = MaterialTheme.typography.bodySmall)
                            if (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Đang chờ thử lại (Backoff)...", color = Color(0xFFF57C00), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(4.dp))
                                    CircularProgressIndicator(modifier = Modifier.size(8.dp), strokeWidth = 1.dp, color = Color(0xFFF57C00))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Lazy Write (Video Progress): ${videoProgress.toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = videoProgress,
                    onValueChange = { viewModel.updateVideoProgress(it) },
                    valueRange = 0f..100f,
                    modifier = Modifier.height(20.dp)
                )

                // Label hiển thị dữ liệu "trên Server"
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Dữ liệu trên Server: ${serverVideoProgress.toInt()}%", 
                        style = MaterialTheme.typography.bodySmall, 
                        color = Color(0xFF007A5E), 
                        fontWeight = FontWeight.Bold
                    )
                    if (isSyncing) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 2.dp, color = Color(0xFF007A5E))
                        Spacer(Modifier.width(4.dp))
                        Text("Syncing...", fontSize = 9.sp, color = Color(0xFF007A5E))
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.DarkGray).padding(8.dp), reverseLayout = true) {
            items(logs) { log ->
                Text(text = log, color = if(log.contains("Lỗi")) Color.Red else if(log.contains("Lazy") || log.contains("Backoff")) Color.Yellow else Color.Green, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
            }
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
