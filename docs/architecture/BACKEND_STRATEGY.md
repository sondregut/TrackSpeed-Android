# Backend & Database Strategy

**Last Updated:** January 2026

This document outlines the backend architecture decisions for cross-platform compatibility between iOS and Android apps.

---

## 1. Decision: Shared Backend

**Recommendation: Share the existing Supabase backend with the iOS app.**

### Rationale

| Approach | Pros | Cons |
|----------|------|------|
| **Shared Backend** | Cross-platform sessions work seamlessly, single source of truth, reduced maintenance | Must coordinate schema changes |
| **Separate Backend** | Independent development, no coordination needed | Cross-platform pairing won't work via cloud, duplicate infrastructure |

Since the primary feature is **cross-platform multi-device timing** (iOS ↔ Android), sharing the backend is essential.

---

## 2. Existing Supabase Configuration

### Connection Details

```kotlin
// From iOS app - use same credentials
object SupabaseConfig {
    const val URL = "https://hkvrttatbpjwzuuckbqj.supabase.co"
    const val ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
    // Store in local.properties, not in code
}
```

**Project**: `sprint-timer-race` (ID: `hkvrttatbpjwzuuckbqj`)

### Existing Tables (Actual Schema from Supabase)

#### `race_events` (1,581 rows)
Real-time cross-device timing events for multi-phone sessions.

```sql
CREATE TABLE race_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN ('start', 'finish')),
    crossing_time_nanos BIGINT NOT NULL,
    device_id TEXT NOT NULL,
    device_name TEXT,
    image_path TEXT,
    clock_offset_nanos BIGINT,
    uncertainty_ms DOUBLE PRECISION,
    created_at TIMESTAMPTZ DEFAULT now()
);
-- RLS: ENABLED
```

#### `sessions` (306 rows)
Training session metadata.

```sql
CREATE TABLE sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id TEXT NOT NULL,
    name TEXT,
    location TEXT,
    notes TEXT,
    distance DOUBLE PRECISION NOT NULL,
    start_type TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);
-- RLS: ENABLED
```

#### `runs` (1,176 rows)
Individual timing runs within sessions.

```sql
CREATE TABLE runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID REFERENCES sessions(id),
    athlete_id UUID,
    athlete_name TEXT,
    athlete_color TEXT,
    run_number INTEGER NOT NULL,
    time_seconds DOUBLE PRECISION NOT NULL,
    distance DOUBLE PRECISION NOT NULL,
    start_type TEXT NOT NULL,
    reaction_time DOUBLE PRECISION,
    is_personal_best BOOLEAN DEFAULT false,
    is_season_best BOOLEAN DEFAULT false,
    thumbnail_url TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
-- RLS: ENABLED
-- FK: session_id -> sessions.id
```

#### `crossings` (1,470 rows)
Detailed crossing data for each gate.

```sql
CREATE TABLE crossings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id TEXT NOT NULL,
    run_id UUID NOT NULL,
    gate_role TEXT NOT NULL CHECK (gate_role IN ('start', 'split_1', 'split_2', 'split_3', 'finish', 'lap')),
    device_id TEXT NOT NULL,
    crossing_time_nanos BIGINT NOT NULL,
    thumbnail_url TEXT,
    full_res_url TEXT,
    created_at TIMESTAMPTZ DEFAULT now()
);
-- RLS: ENABLED
```

#### `athletes` (0 rows)
Athlete profiles (not heavily used yet).

```sql
CREATE TABLE athletes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id TEXT NOT NULL,
    name TEXT NOT NULL,
    nickname TEXT,
    color TEXT,
    photo_url TEXT,
    birthdate DATE,
    gender TEXT,
    personal_bests JSONB DEFAULT '{}',
    season_bests JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    updated_at TIMESTAMPTZ DEFAULT now()
);
-- RLS: ENABLED
```

#### `pairing_requests` (0 rows)
Session code pairing for device discovery.

```sql
CREATE TABLE pairing_requests (
    session_code TEXT PRIMARY KEY,
    host_device_id TEXT NOT NULL,
    host_device_name TEXT,
    joiner_device_id TEXT,
    joiner_device_name TEXT,
    status TEXT DEFAULT 'waiting' CHECK (status IN ('waiting', 'matched', 'connected', 'expired')),
    created_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ DEFAULT (now() + interval '5 minutes')
);
-- RLS: ENABLED
```

---

## 3. Data Flow Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           SUPABASE BACKEND                               │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │    Auth     │  │  Database   │  │  Realtime   │  │   Storage   │    │
│  │             │  │ (Postgres)  │  │ (WebSocket) │  │  (Images)   │    │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘    │
└─────────┼────────────────┼────────────────┼────────────────┼───────────┘
          │                │                │                │
          │    ┌───────────┴────────────────┴────────────────┘
          │    │
     ┌────┴────┴────┐                              ┌────────────────┐
     │              │                              │                │
     │  iOS App     │◄────── BLE / WiFi ──────────►│  Android App   │
     │ (Speed Swift)│                              │ (TrackSpeed)   │
     │              │                              │                │
     └──────────────┘                              └────────────────┘
           │                                              │
           │              LOCAL STORAGE                   │
           ▼                                              ▼
     ┌──────────────┐                              ┌────────────────┐
     │  SwiftData   │                              │     Room       │
     │  (SQLite)    │                              │   (SQLite)     │
     └──────────────┘                              └────────────────┘
```

---

## 4. What's Shared vs. Local

### Shared (Supabase)

| Data | Table | Purpose |
|------|-------|---------|
| Race events | `race_events` | Cross-device timing sync |
| Session metadata | `race_sessions` | Session discovery, join codes |
| User accounts | `auth.users` + `profiles` | Authentication (optional) |
| Images (future) | Storage bucket | Cloud backup of photo-finish |

### Local Only (Room/SwiftData)

| Data | Why Local |
|------|-----------|
| Training sessions | Offline-first, user owns data |
| Run history | Fast access, no network needed |
| Athlete profiles | Personal data, privacy |
| Composite images | Large files, local display |
| App settings | Device-specific preferences |

---

## 5. Sync Strategy

### 5.1 Race Events (Real-Time Sync)

**Purpose**: Cross-device timing during active sessions

```kotlin
// Subscribe to race events for current session
fun subscribeToRaceEvents(sessionId: String): Flow<RaceEvent> {
    return supabase.realtime
        .channel("race_events:session_id=eq.$sessionId")
        .postgresChangeFlow<RaceEvent>(
            schema = "public",
            table = "race_events",
            filter = "session_id=eq.$sessionId"
        )
        .map { it.record }
}

// Insert local crossing event
suspend fun insertRaceEvent(event: RaceEvent) {
    supabase.postgrest["race_events"].insert(event)
}
```

### 5.2 Training Sessions (Optional Cloud Backup)

**Purpose**: Backup/restore across devices (post-MVP)

```kotlin
// Export session to cloud
suspend fun backupSession(session: TrainingSession) {
    val dto = session.toCloudDto()
    supabase.postgrest["training_sessions"].upsert(dto)
}

// Import sessions from cloud
suspend fun restoreSessions(userId: String): List<TrainingSession> {
    return supabase.postgrest["training_sessions"]
        .select { filter { eq("user_id", userId) } }
        .decodeList<TrainingSessionDto>()
        .map { it.toDomain() }
}
```

### 5.3 Images (Future)

**Purpose**: Cloud backup of photo-finish images

```kotlin
// Upload to Supabase Storage
suspend fun uploadImage(sessionId: String, runId: String, imageBytes: ByteArray): String {
    val path = "sessions/$sessionId/runs/$runId/finish.jpg"
    supabase.storage["images"].upload(path, imageBytes)
    return path
}

// Download from Supabase Storage
suspend fun downloadImage(path: String): ByteArray {
    return supabase.storage["images"].downloadPublic(path)
}
```

---

## 6. Schema Compatibility

### Cross-Platform DTOs (Kotlin)

All DTOs must match the Supabase table schemas exactly:

```kotlin
@Serializable
data class RaceEventDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("event_type") val eventType: String,  // "start" or "finish"
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("clock_offset_nanos") val clockOffsetNanos: Long? = null,
    @SerialName("uncertainty_ms") val uncertaintyMs: Double? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class SessionDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    val name: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val distance: Double,
    @SerialName("start_type") val startType: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class RunDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("athlete_id") val athleteId: String? = null,
    @SerialName("athlete_name") val athleteName: String? = null,
    @SerialName("athlete_color") val athleteColor: String? = null,
    @SerialName("run_number") val runNumber: Int,
    @SerialName("time_seconds") val timeSeconds: Double,
    val distance: Double,
    @SerialName("start_type") val startType: String,
    @SerialName("reaction_time") val reactionTime: Double? = null,
    @SerialName("is_personal_best") val isPersonalBest: Boolean = false,
    @SerialName("is_season_best") val isSeasonBest: Boolean = false,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class CrossingDto(
    val id: String? = null,
    @SerialName("session_id") val sessionId: String,
    @SerialName("run_id") val runId: String,
    @SerialName("gate_role") val gateRole: String,  // start, split_1, split_2, split_3, finish, lap
    @SerialName("device_id") val deviceId: String,
    @SerialName("crossing_time_nanos") val crossingTimeNanos: Long,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("full_res_url") val fullResUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class PairingRequestDto(
    @SerialName("session_code") val sessionCode: String,
    @SerialName("host_device_id") val hostDeviceId: String,
    @SerialName("host_device_name") val hostDeviceName: String? = null,
    @SerialName("joiner_device_id") val joinerDeviceId: String? = null,
    @SerialName("joiner_device_name") val joinerDeviceName: String? = null,
    val status: String = "waiting",  // waiting, matched, connected, expired
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class AthleteDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    val name: String,
    val nickname: String? = null,
    val color: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    val birthdate: String? = null,  // ISO date
    val gender: String? = null,
    @SerialName("personal_bests") val personalBests: Map<String, Double> = emptyMap(),
    @SerialName("season_bests") val seasonBests: Map<String, Double> = emptyMap(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
```

**Critical**: Field names must match exactly (snake_case for Postgres columns).

### Device Identification

```kotlin
// Generate consistent device ID
fun getDeviceId(context: Context): String {
    val prefs = context.getSharedPreferences("trackspeed", Context.MODE_PRIVATE)
    return prefs.getString("device_id", null) ?: run {
        val newId = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", newId).apply()
        newId
    }
}

// Device name for display
fun getDeviceName(): String {
    return "${Build.MANUFACTURER} ${Build.MODEL}"
}
```

---

## 7. Authentication Strategy

### Option A: Anonymous Sessions (MVP)

No authentication required for basic timing:
- Generate random session codes
- Anyone with code can join
- Data expires after session ends

```kotlin
// Create anonymous session
suspend fun createAnonymousSession(): String {
    val sessionId = UUID.randomUUID().toString()
    val code = generateSessionCode()  // 6 random digits

    supabase.postgrest["race_sessions"].insert(
        RaceSessionDto(
            id = sessionId,
            sessionCode = code,
            hostDeviceId = getDeviceId(),
            expiresAt = Instant.now().plus(24, ChronoUnit.HOURS).toString()
        )
    )

    return code
}
```

### Option B: Authenticated Users (Post-MVP)

For cloud backup and cross-device sync:

```kotlin
// Sign in with Google (matches iOS Apple Sign-In)
suspend fun signInWithGoogle(idToken: String) {
    supabase.auth.signInWith(Google) {
        this.idToken = idToken
    }
}

// Check auth state
val isAuthenticated: Boolean
    get() = supabase.auth.currentUserOrNull() != null
```

---

## 8. Local Database Schema (Room)

### Entities

```kotlin
@Entity(tableName = "training_sessions")
data class TrainingSessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val date: Long,  // Epoch millis
    val name: String? = null,
    val location: String? = null,
    val notes: String? = null,
    val distance: Double,
    val startType: String,  // StartType.rawValue
    val numberOfPhones: Int = 2,
    val numberOfGates: Int = 2,
    val gateConfigJson: String? = null,
    val thumbnailPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Cloud sync
    val cloudId: String? = null,  // Supabase ID if synced
    val lastSyncedAt: Long? = null
)

@Entity(
    tableName = "runs",
    foreignKeys = [ForeignKey(
        entity = TrainingSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class RunEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val athleteId: String? = null,
    val athleteName: String? = null,
    val athleteColor: String? = null,
    val runNumber: Int,
    val timestamp: Long,
    val time: Double,  // seconds
    val reactionTime: Double? = null,
    val distance: Double,
    val startType: String,
    val numberOfPhones: Int,
    val isPersonalBest: Boolean = false,
    val isSeasonBest: Boolean = false,
    val startImagePath: String? = null,
    val finishImagePath: String? = null,
    val lapImagePathsJson: String? = null,
    val splitsJson: String? = null
)

@Entity(tableName = "athletes")
data class AthleteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val nickname: String? = null,
    val color: String,  // AthleteColor.rawValue
    val photoPath: String? = null,
    val birthdate: Long? = null,
    val gender: String? = null,
    val personalBestsJson: String? = null,  // {"60m": 7.45, "100m": 11.2}
    val seasonBestsJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

### DAOs

```kotlin
@Dao
interface TrainingSessionDao {
    @Query("SELECT * FROM training_sessions ORDER BY date DESC")
    fun getAllSessions(): Flow<List<TrainingSessionEntity>>

    @Query("SELECT * FROM training_sessions WHERE id = :id")
    suspend fun getSession(id: String): TrainingSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: TrainingSessionEntity)

    @Update
    suspend fun update(session: TrainingSessionEntity)

    @Delete
    suspend fun delete(session: TrainingSessionEntity)

    @Query("SELECT * FROM training_sessions WHERE cloudId IS NULL OR lastSyncedAt < updatedAt")
    suspend fun getUnsyncedSessions(): List<TrainingSessionEntity>
}

@Dao
interface RunDao {
    @Query("SELECT * FROM runs WHERE sessionId = :sessionId ORDER BY runNumber")
    fun getRunsForSession(sessionId: String): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE athleteId = :athleteId ORDER BY timestamp DESC")
    fun getRunsForAthlete(athleteId: String): Flow<List<RunEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: RunEntity)

    @Query("SELECT MIN(time) FROM runs WHERE athleteId = :athleteId AND distance = :distance")
    suspend fun getPersonalBest(athleteId: String, distance: Double): Double?
}

@Dao
interface AthleteDao {
    @Query("SELECT * FROM athletes ORDER BY name")
    fun getAllAthletes(): Flow<List<AthleteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(athlete: AthleteEntity)

    @Update
    suspend fun update(athlete: AthleteEntity)

    @Delete
    suspend fun delete(athlete: AthleteEntity)
}
```

---

## 9. Offline-First Architecture

### Principle

1. **All reads from local database** (Room)
2. **Writes go to local first**, then sync to cloud
3. **Real-time events** bypass local (direct Supabase Realtime)

### Implementation

```kotlin
class SessionRepository(
    private val sessionDao: TrainingSessionDao,
    private val supabase: SupabaseClient
) {
    // Read: Always from local
    fun getSessions(): Flow<List<TrainingSession>> {
        return sessionDao.getAllSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    // Write: Local first, then sync
    suspend fun saveSession(session: TrainingSession) {
        // 1. Save locally
        sessionDao.insert(session.toEntity())

        // 2. Sync to cloud (fire-and-forget)
        try {
            supabase.postgrest["training_sessions"].upsert(session.toCloudDto())
            sessionDao.update(session.toEntity().copy(
                cloudId = session.id,
                lastSyncedAt = System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            // Will sync later
            Log.w("SessionRepository", "Cloud sync failed, will retry", e)
        }
    }

    // Background sync
    suspend fun syncPendingSessions() {
        val unsynced = sessionDao.getUnsyncedSessions()
        for (session in unsynced) {
            try {
                supabase.postgrest["training_sessions"].upsert(session.toCloudDto())
                sessionDao.update(session.copy(
                    cloudId = session.id,
                    lastSyncedAt = System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                // Continue with others
            }
        }
    }
}
```

---

## 10. Migration Considerations

### Adding New Fields

When iOS adds new fields to shared tables:

1. **Android must handle gracefully** - Unknown fields ignored
2. **Use nullable types** for new fields
3. **Coordinate major changes** between platforms

```kotlin
// Handle unknown fields gracefully
@Serializable
data class RaceEventDto(
    // Known fields...
    @SerialName("new_field") val newField: String? = null  // Default null
)
```

### Schema Versioning

```kotlin
// Track schema version in app
const val SCHEMA_VERSION = 1

// On app update, check if migration needed
suspend fun checkSchemaMigration() {
    val serverVersion = supabase.postgrest.rpc("get_schema_version")
    if (serverVersion > SCHEMA_VERSION) {
        // Prompt user to update app
    }
}
```

---

## 11. Security Considerations

### Row-Level Security (RLS)

If RLS is enabled on Supabase tables:

```sql
-- Example: Users can only access their own sessions
CREATE POLICY "Users can access own sessions"
ON race_sessions
FOR ALL
USING (host_device_id = current_setting('request.jwt.claims')::json->>'device_id');

-- Or for authenticated users
USING (auth.uid() = user_id);
```

### API Key Protection

```kotlin
// DON'T commit to git
// Store in local.properties
SUPABASE_URL=https://xxx.supabase.co
SUPABASE_ANON_KEY=eyJhbGci...

// Access via BuildConfig
val client = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
)
```

### Session Expiration

```kotlin
// Auto-expire old sessions
suspend fun cleanupExpiredSessions() {
    supabase.postgrest["race_sessions"]
        .delete { filter { lt("expires_at", Instant.now().toString()) } }
}
```

---

## 12. Summary

| Component | Strategy |
|-----------|----------|
| **Supabase Backend** | Shared with iOS |
| **race_events table** | Real-time sync for cross-device timing |
| **Training sessions** | Local-first (Room), optional cloud backup |
| **Authentication** | Anonymous MVP, optional accounts later |
| **Images** | Local storage, optional cloud backup |
| **Schema changes** | Coordinate between platforms |
| **Offline mode** | Full functionality without internet (local timing) |
