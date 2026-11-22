package com.calenflex.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "workspace")
data class StoredState(@PrimaryKey val id: Int = 1, val payload: String, val revision: Int = 0, val dirty: Boolean = false)
@Dao interface StateDao {
    @Query("SELECT * FROM workspace WHERE id = 1") fun observe(): Flow<StoredState?>
    @Query("SELECT * FROM workspace WHERE id = 1") suspend fun get(): StoredState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: StoredState)
}
@Database(entities = [StoredState::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() { abstract fun stateDao(): StateDao }

val Context.connectionData by preferencesDataStore("connection")
data class Connection(val url: String = "", val token: String = "")
interface ConnectionProvider { suspend fun get(): Connection; suspend fun save(connection: Connection) }
class ConnectionStore(private val context: Context) : ConnectionProvider {
    private val urlKey = stringPreferencesKey("url")
    private val tokenKey = stringPreferencesKey("encryptedToken")
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("calenflex", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("calenflex", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }
    override suspend fun get(): Connection {
        val p = context.connectionData.data.first()
        return Connection(p[urlKey].orEmpty(), decrypt(p[tokenKey].orEmpty()))
    }
    override suspend fun save(connection: Connection) {
        require(connection.url.isBlank() || connection.url.startsWith("https://")) { "El servidor debe usar HTTPS" }
        context.connectionData.edit { it[urlKey] = connection.url.trim().trimEnd('/'); it[tokenKey] = encrypt(connection.token.trim()) }
    }
}
interface Api {
    @GET("api/sync") suspend fun pull(): SyncEnvelope
    @PUT("api/sync") suspend fun push(@Body body: SyncEnvelope): SyncEnvelope
    @POST("api/plan") suspend fun plan(@Body body: PlanRequest): PlanResult
    @POST("api/ai") suspend fun ai(@Body body: AIRequest): AIResponse
}
class Repository(private val dao: StateDao, val connection: ConnectionProvider, private val apiOverride: Api? = null) {
    private val mutex = Mutex()
    val stored = dao.observe()
    val state = stored.map { it?.let { json.decodeFromString<State>(it.payload) } ?: State() }
    suspend fun current() = dao.get() ?: StoredState(payload = json.encodeToString(State()))
    suspend fun edit(text: String, change: (State) -> State) = mutex.withLock {
        val previous = current()
        val old = json.decodeFromString<State>(previous.payload)
        val next = change(old).let { it.copy(history = (it.history + Event(Instant.now().toString(), text)).takeLast(10000)) }
        validateState(next)
        dao.put(previous.copy(payload = json.encodeToString(next), dirty = true))
    }
    suspend fun api(): Api {
        apiOverride?.let { return it }
        val settings = connection.get()
        require(settings.url.startsWith("https://") && settings.token.isNotBlank()) { "Configurá el servidor HTTPS y su credencial en Perfil" }
        val client = OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${settings.token}").build())
        }.build()
        return Retrofit.Builder().baseUrl(settings.url.trimEnd('/') + "/").client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build().create(Api::class.java)
    }
    suspend fun sync() = mutex.withLock {
        val local = current()
        val api = api()
        val remote = if (local.dirty) api.push(SyncEnvelope(local.revision, json.decodeFromString(local.payload))) else api.pull()
        validateState(remote.state)
        dao.put(StoredState(payload = json.encodeToString(remote.state), revision = remote.revision))
    }
    suspend fun useRemote() = mutex.withLock {
        val remote = api().pull()
        validateState(remote.state)
        dao.put(StoredState(payload = json.encodeToString(remote.state), revision = remote.revision))
    }
    suspend fun keepLocal() = mutex.withLock {
        val local = current()
        val api = api()
        val remote = api.pull()
        val saved = api.push(SyncEnvelope(remote.revision, json.decodeFromString(local.payload)))
        dao.put(StoredState(payload = json.encodeToString(saved.state), revision = saved.revision))
    }
    suspend fun applyPlan(base: String, result: PlanResult) = mutex.withLock {
        val local = current()
        require(local.payload == base) { "Los datos cambiaron. Generá otra previsualización." }
        require(result.conflicts.isEmpty()) { "Hay conflictos pendientes; corregí las restricciones antes de aplicar" }
        val old = json.decodeFromString<State>(base)
        val scheduledIds = result.schedule.map { it.activityId }.toSet()
        val next = old.copy(activities = old.activities.map { a -> if(a.status in listOf("Pendiente", "Programada")) a.copy(status = if(a.id in scheduledIds) "Programada" else "Pendiente") else a }, schedule = result.schedule, pending = result.pending, history = (old.history + Event(Instant.now().toString(), "Planificación aplicada")).takeLast(10000))
        validateState(next)
        dao.put(local.copy(payload = json.encodeToString(next), dirty = true))
    }
}
